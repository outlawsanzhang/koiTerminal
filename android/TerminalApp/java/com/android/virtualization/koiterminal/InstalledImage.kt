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
import android.os.FileUtils
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.android.virtualization.terminal.MainActivity.Companion.TAG
import java.io.BufferedReader
import java.io.FileReader
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.ceil

/** Collection of files that consist of a VM image. */
public class InstalledImage private constructor(val installDir: Path) {
    private val rootPartition: Path = installDir.resolve(ROOTFS_FILENAME)
    val backupFile: Path = installDir.resolve(BACKUP_FILENAME)

    /** The path to the VM config file. */
    val configPath: Path = installDir.resolve(CONFIG_FILENAME)
    private val marker: Path = installDir.resolve(MARKER_FILENAME)
    /** The build ID of the installed image */
    val buildId: String by lazy { readBuildId() }

    /** Tests if this InstalledImage is actually installed. */
    fun isInstalled(): Boolean {
        return Files.exists(marker)
    }

    /** Fully uninstall this InstalledImage by deleting everything. */
    @Throws(IOException::class)
    fun uninstallFully() {
        FileUtils.deleteContentsAndDir(installDir.toFile())
    }

    private fun readBuildId(): String {
        val file = installDir.resolve(BUILD_ID_FILENAME)
        if (!Files.exists(file)) {
            return "<no build id>"
        }
        try {
            BufferedReader(FileReader(file.toFile())).use { r ->
                return r.readLine()
            }
        } catch (e: IOException) {
            throw RuntimeException("Failed to read build ID", e)
        }
    }

    fun isOlderThanCurrentVersion(): Boolean {
        val year =
            try {
                buildId.split(" ").last().toInt()
            } catch (_: Exception) {
                0
            }
        return year < RELEASE_YEAR
    }

    @Throws(IOException::class)
    fun uninstallAndBackup(): Path {
        Files.delete(marker)
        Files.move(rootPartition, backupFile, StandardCopyOption.REPLACE_EXISTING)
        return backupFile
    }

    fun hasBackup(): Boolean {
        return Files.exists(backupFile)
    }

    @Throws(IOException::class)
    fun deleteBackup() {
        Files.deleteIfExists(backupFile)
    }

    @Throws(IOException::class)
    fun getApparentSize(): Long {
        return Files.size(rootPartition)
    }

    @Throws(IOException::class)
    fun getPhysicalSize(): Long {
        val stat = RandomAccessFile(rootPartition.toFile(), "rw").use { raf -> Os.fstat(raf.fd) }
        // The unit of st_blocks is 512 byte in Android.
        return 512L * stat.st_blocks
    }

    @Throws(IOException::class)
    fun getSmallestSizePossible(): Long {
        runE2fsck(rootPartition)
        val p: String = rootPartition.toAbsolutePath().toString()
        val result = runCommand("/system/bin/resize2fs", "-P", p)
        val regex = "Estimated minimum size of the filesystem: ([0-9]+)".toRegex()
        val matchResult = result.lines().firstNotNullOfOrNull { regex.find(it) }
        if (matchResult != null) {
            try {
                val size = matchResult.groupValues[1].toLong()
                // The return value is the number of 4k block
                return roundUp(size * 4 * 1024)
            } catch (e: NumberFormatException) {
                // cannot happen
            }
        }
        val msg = "Failed to get min size, p=$p, result=$result"
        Log.e(TAG, msg)
        throw RuntimeException(msg)
    }

    @Throws(IOException::class)
    fun resize(desiredSize: Long): Long {
        val roundedUpDesiredSize = roundUp(desiredSize)
        val curSize = getApparentSize()

        runE2fsck(rootPartition)

        if (roundedUpDesiredSize == curSize) {
            return roundedUpDesiredSize
        }

        if (roundedUpDesiredSize > curSize) {
            if (!allocateSpace(roundedUpDesiredSize)) {
                return curSize
            }
        }
        resizeFilesystem(rootPartition, roundedUpDesiredSize)
        return getApparentSize()
    }

    @Throws(IOException::class)
    private fun allocateSpace(sizeInBytes: Long): Boolean {
        val curSizeInBytes = getApparentSize()
        try {
            RandomAccessFile(rootPartition.toFile(), "rw").use { raf ->
                Os.posix_fallocate(raf.fd, 0, sizeInBytes)
            }
            Log.d(TAG, "Allocated space to: $sizeInBytes bytes")
            return true
        } catch (e: ErrnoException) {
            Log.e(TAG, "Failed to allocate space", e)
            if (e.errno == OsConstants.ENOSPC) {
                Log.d(TAG, "Trying to truncate disk into the original size")
                truncate(curSizeInBytes)
                return false
            } else {
                throw IOException("Failed to allocate space", e)
            }
        }
    }

    @Throws(IOException::class)
    fun shrinkToMinimumSize(): Long {
        // Fix filesystem before resizing.
        runE2fsck(rootPartition)

        val p: String = rootPartition.toAbsolutePath().toString()
        runCommand("/system/bin/resize2fs", "-M", p)
        Log.d(TAG, "resize2fs -M completed: $rootPartition")

        // resize2fs may result in an inconsistent filesystem state. Fix with e2fsck.
        runE2fsck(rootPartition)
        return getApparentSize()
    }

    @Throws(IOException::class)
    fun truncate(size: Long) {
        try {
            RandomAccessFile(rootPartition.toFile(), "rw").use { raf -> Os.ftruncate(raf.fd, size) }
            Log.d(TAG, "Truncated space to: $size bytes")
        } catch (e: ErrnoException) {
            Log.e(TAG, "Failed to truncate space", e)
            throw IOException("Failed to truncate space", e)
        }
    }

    companion object {
        private const val INSTALL_DIRNAME = "linux"
        private const val ROOTFS_FILENAME = "root_part"
        private const val BACKUP_FILENAME = "root_part_backup"
        private const val CONFIG_FILENAME = "vm_config.json"
        private const val BUILD_ID_FILENAME = "build_id"
        const val MARKER_FILENAME: String = "completed"

        const val RESIZE_STEP_BYTES: Long = 4 shl 20 // 4 MiB
        const val RELEASE_YEAR: Int = 2025

        /** Returns InstalledImage for a given app context */
        fun getDefault(context: Context): InstalledImage {
            val installDir = context.getFilesDir().toPath().resolve(INSTALL_DIRNAME)
            return InstalledImage(installDir)
        }

        @Throws(IOException::class)
        private fun runE2fsck(path: Path) {
            val p: String = path.toAbsolutePath().toString()
            runCommand("/system/bin/e2fsck", "-y", "-f", p)
            Log.d(TAG, "e2fsck completed: $path")
        }

        @Throws(IOException::class)
        private fun resizeFilesystem(path: Path, sizeInBytes: Long) {
            val sizeInMB = sizeInBytes / (1024 * 1024)
            if (sizeInMB == 0L) {
                Log.e(TAG, "Invalid size: $sizeInBytes bytes")
                throw IllegalArgumentException("Size cannot be zero MB")
            }
            val sizeArg = sizeInMB.toString() + "M"
            val p: String = path.toAbsolutePath().toString()
            runCommand("/system/bin/resize2fs", p, sizeArg)
            Log.d(TAG, "resize2fs completed: $path, size: $sizeArg")
        }

        @Throws(IOException::class)
        private fun runCommand(vararg command: String): String {
            try {
                val process = ProcessBuilder(*command).redirectErrorStream(true).start()
                process.waitFor()
                val result = String(process.inputStream.readAllBytes())
                if (process.exitValue() != 0) {
                    Log.w(
                        TAG,
                        "Process returned with error, command=${listOf(*command).joinToString(" ")}," +
                            "exitValue=${process.exitValue()}, result=$result",
                    )
                }
                return result
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Command interrupted", e)
            }
        }

        internal fun roundUp(bytes: Long): Long {
            // Round up every diskSizeStep MB
            return ceil((bytes.toDouble()) / RESIZE_STEP_BYTES).toLong() * RESIZE_STEP_BYTES
        }
    }
}
