/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.virtualization.terminal

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import com.android.virtualization.terminal.MainActivity.Companion.TAG
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.lang.RuntimeException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.function.Function
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * ImageArchive models the archive file (images.tar.gz) where VM payload files are in. This class
 * provides methods for handling the archive file, most importantly installing it.
 */
internal class ImageArchive {
    // Only one can be non-null
    private sealed class Source<out A, out B>

    private data class UrlSource<out Url>(val value: Url) : Source<Url, Nothing>()

    private data class PathSource<out Path>(val value: Path) : Source<Nothing, Path>()

    private val source: Source<URL, Path>
    private val extra_source: Path

    private constructor(url: URL, extra_path: Path) {
        source = UrlSource(url)
        extra_source = extra_path
    }

    private constructor(path: Path, extra_path: Path) {
        source = PathSource(path)
        extra_source = extra_path
    }

    /** Tests if ImageArchive exists on the medium. */
    fun exists(): Boolean {
        return when (source) {
            is UrlSource -> true
            is PathSource -> Files.exists(source.value)
        }
    }

    /** Returns path to the archive. */
    fun getPath(): String {
        return when (source) {
            is UrlSource -> source.value.toString()
            is PathSource -> source.value.toString()
        }
    }

    /** Returns size of the archive in bytes */
    @Throws(IOException::class)
    fun getSize(): Long {
        check(exists()) { "Cannot get size of non existing archive" }
        return when (source) {
            is UrlSource -> {
                val conn = source.value.openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "HEAD"
                    conn.getInputStream()
                    return conn.contentLength.toLong()
                } finally {
                    conn.disconnect()
                }
            }
            is PathSource -> Files.size(source.value)
        }
    }

    /**
     * Returns an input stream for reading the archive, starting from the specified [offset]. If the
     * source is a URL, it uses the HTTP "Range" header to request the data. If the source is a
     * local file, it skips to the specified offset.
     */
    @Throws(IOException::class)
    internal fun getInputStream(offset: Long = 0L): InputStream {
        check(exists()) { "Cannot get input stream of non existing archive" }
        return when (source) {
            is UrlSource -> {
                val conn = source.value.openConnection() as HttpURLConnection
                if (offset > 0) {
                    conn.setRequestProperty("Range", "bytes=$offset-")
                }
                conn.inputStream
            }
            is PathSource -> {
                val stream = FileInputStream(source.value.toFile())
                if (offset > 0) {
                    stream.skip(offset)
                }
                stream
            }
        }
    }

    fun copyAsset(context: Context, srcAssetFileName: String, dstDir: Path) {
        val dst = dstDir.resolve(srcAssetFileName)
        context.assets.open(srcAssetFileName).use { input ->
            FileOutputStream(dst.toFile()).use { os -> input.copyTo(os) }
        }
    }

    /**
     * Installs this ImageArchive to a directory pointed by path. filter can be supplied to provide
     * an additional input stream which will be used during the installation.
     */
    fun installTo(
        context: Context,
        dir: Path,
        filter: Function<InputStream, InputStream>?,
    ): Flow<Long> = callbackFlow {
        val job =
            launch(Dispatchers.IO) {
                val sourceString =
                    when (source) {
                        is PathSource -> source.value.toString()
                        is UrlSource -> source.value.toString()
                    }
                Log.d(TAG, "Install started. source: $sourceString, destination: $dir")

                try {
                    val rawStream =
                        when (source) {
                            is UrlSource -> source.value.openStream()
                            is PathSource -> FileInputStream(source.value.toFile())
                        }
                    val bufferedStream = BufferedInputStream(rawStream)
                    val filteredStream = filter?.apply(bufferedStream) ?: bufferedStream
                    val countingStream =
                        CountingInputStream(filteredStream) { bytes ->
                            if (!isActive) {
                                throw CancellationException("Installation cancelled")
                            }
                            trySend(bytes)
                        }

                    var hasCidataInImage = false
                    var hasCidataBuildIdInImage = false
                    TarArchiveInputStream(GzipCompressorInputStream(countingStream)).use { tarStream
                        ->
                        Files.createDirectories(dir)
                        var entry: ArchiveEntry?
                        while ((tarStream.nextEntry.also { entry = it }) != null) {
                            val name = entry!!.getName().removePrefix("./")
                            hasCidataInImage = if (name == CIDATA_NAME) true else hasCidataInImage
                            hasCidataBuildIdInImage =
                                if (name == InstalledImage.CIDATA_BUILD_ID_FILENAME) true
                                else hasCidataBuildIdInImage
                            val to = dir.resolve(name)
                            if (Files.isDirectory(to)) {
                                Files.createDirectories(to)
                                continue
                            }
                            Log.d(TAG, "Installing " + to)
                            Files.copy(tarStream, to, StandardCopyOption.REPLACE_EXISTING)
                        }
                    }

                    // Override cidata if necessary. Assume overridden cidata uses AIDL guest agent.
                    if (Build.isDebuggable() && extra_source.exists() == true) {
                        Log.d(TAG, "Installing /sdcard/linux/cidata.iso")

                        val cidataPath = dir.resolve(extra_source.fileName)
                        Files.copy(extra_source, cidataPath, StandardCopyOption.REPLACE_EXISTING)

                        // Also create cidata.build_id, so it can be recognized as AIDL guest
                        // agent.
                        val cidataBuildIdPath = dir.resolve(InstalledImage.CIDATA_BUILD_ID_FILENAME)
                        cidataBuildIdPath.writeText("dev")
                    } else if (hasCidataInImage) {
                        // Something went bad, and we need to revert to use cidata.iso inside
                        // images.tar.gz.
                        if (!hasCidataBuildIdInImage) {
                            val cidataBuildId = dir.resolve(InstalledImage.CIDATA_BUILD_ID_FILENAME)
                            Files.deleteIfExists(cidataBuildId)
                        }
                    } else {
                        val installedCidata = dir.resolve(CIDATA_NAME)
                        Log.d(TAG, "Installing bundled cidata.iso")
                        copyAsset(context, CIDATA_NAME, dir)
                        copyAsset(context, InstalledImage.CIDATA_BUILD_ID_FILENAME, dir)
                    }

                    commitInstallationAt(dir)
                    close()
                } catch (e: Exception) {
                    Log.e(TAG, "Installing images.tar.gz failed", e)
                    close(e)
                }
            }
        awaitClose { job.cancel() }
    }

    @Throws(IOException::class)
    private fun commitInstallationAt(dir: Path) {
        // Mark the completion
        val marker = dir.resolve(InstalledImage.MARKER_FILENAME)
        try {
            Files.createFile(marker)
        } catch (e: FileAlreadyExistsException) {
            Log.i(TAG, "Marker file already exists. Something's wrong, but continue anyway")
        }
    }

    private class CountingInputStream(
        private val stream: InputStream,
        private val listener: ((Long) -> Unit)?,
    ) : InputStream() {
        var bytesRead = 0L
            private set

        override fun read(): Int {
            val result = stream.read()
            if (result != -1) {
                bytesRead++
                listener?.invoke(bytesRead)
            }
            return result
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val result = stream.read(b, off, len)
            if (result != -1) {
                bytesRead += result
                listener?.invoke(bytesRead)
            }
            return result
        }
    }

    companion object {
        private const val DIR_IN_SDCARD = "linux"
        private const val ARCHIVE_NAME = "images.tar.gz"
        private const val CIDATA_NAME = "cidata.iso"
        // TODO(b/403131508): externalize the version number
        private const val VERSION_INT = 5_100_000 // 5.1.0
        private val BUILD_TAG =
            (VERSION_INT / 1_000 * 1_000).toString() // Ignore patch version number
        private val HOST_URL = "https://example.com/android/ferrochrome/$BUILD_TAG" // disable downloading

        fun getSdcardPathForTesting(): Path {
            return Environment.getExternalStoragePublicDirectory(DIR_IN_SDCARD).toPath()
        }

        /**
         * Creates ImageArchive which is located in the sdcard. This archive is for testing only.
         */
        fun fromSdCard(): ImageArchive {
            return ImageArchive(
                getSdcardPathForTesting().resolve(ARCHIVE_NAME),
                getSdcardPathForTesting().resolve(CIDATA_NAME),
            )
        }

        /**
         * Creates ImageArchive which is hosted in the Google server. This is the official archive.
         */
        fun fromInternet(): ImageArchive {
            val arch =
                if (listOf<String?>(*Build.SUPPORTED_ABIS).contains("x86_64")) "x86_64"
                else "aarch64"
            try {
                return ImageArchive(
                    URL("$HOST_URL/$arch/$ARCHIVE_NAME"),
                    getSdcardPathForTesting().resolve(CIDATA_NAME),
                )
            } catch (e: MalformedURLException) {
                // cannot happen
                throw RuntimeException(e)
            }
        }

        /** Return whether sdcard image would be used for debugging purpose. */
        fun isLocalImage(): Boolean {
            return fromSdCard().exists()
        }

        /**
         * Creates ImageArchive from either SdCard or Internet. SdCard is used only when the build
         * is debuggable and the file actually exists.
         */
        fun getDefault(): ImageArchive {
            return if (isLocalImage()) {
                fromSdCard()
            } else {
                fromInternet()
            }
        }
    }
}
