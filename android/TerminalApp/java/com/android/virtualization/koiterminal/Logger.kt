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
package com.android.virtualization.koiterminal

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.virtualmachine.VirtualMachine
import android.system.virtualmachine.VirtualMachineConfig
import android.system.virtualmachine.VirtualMachineException
import android.util.Log
import androidx.annotation.WorkerThread
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.util.concurrent.ExecutorService
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import libcore.io.Streams

/**
 * Forwards VM's console output to a file on the Android side, and VM's log output to Android logd.
 */
internal object Logger {
    fun setup(context: Context, vm: VirtualMachine, executor: ExecutorService): SerialIOManager? {
        val tag = vm.name
        val dir = context.getFileStreamPath(vm.name + ".log").toPath()

        if (vm.config.debugLevel != VirtualMachineConfig.DEBUG_LEVEL_FULL) {
            Log.i(tag, "Logs are not captured. Non-debuggable VM.")
            return null
        }

        if (Files.isRegularFile(dir)) {
            Log.i(tag, "Removed legacy log file: $dir")
            Files.delete(dir)
        }
        Files.createDirectories(dir)
        deleteOldLogs(dir, 10)
        val logPath = dir.resolve(LocalDateTime.now().toString() + ".txt")
        val serialSplitter = SerialIOManager(vm, executor, tag)
        val serial = ParcelFileDescriptor.AutoCloseInputStream(serialSplitter.newOutSplitPipe())
        val file = Files.newOutputStream(logPath, StandardOpenOption.CREATE)
        executor.execute({
            try {
                serial.use { serial ->
                    LineBufferedOutputStream(file).use { fileOutput ->
                        Streams.copy(serial, fileOutput)
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed to log serial console output. VM may be shutting down. $e")
            }
        })

        val log = vm.getLogOutput()
        executor.execute({
            log.use {
                try {
                    writeToLogd(it, tag)
                } catch (e: Exception) {
                    Log.w(tag, "Failed to log VM log output. VM may be shutting down", e)
                }
            }
        })
        return serialSplitter
    }

    // Called by ErrorActivity in another process.
    @WorkerThread
    public fun zipLogs(context: Context, logZipFilePath: Path) {
        Files.newOutputStream(logZipFilePath, StandardOpenOption.CREATE).use { fos ->
            ZipOutputStream(fos).use { zos ->
                val logDirs =
                    context.filesDir.listFiles { it.isDirectory() && it.name.endsWith(".log") }
                for (dir in logDirs) {
                    val logFiles = dir.listFiles { it.isFile() && it.name.endsWith(".txt") }
                    for (file in logFiles) {
                        // To prevent absolute paths in the zip
                        val entryName = file.toRelativeString(context.filesDir)

                        zos.putNextEntry(ZipEntry(entryName))
                        Files.copy(file.toPath(), zos)
                        zos.closeEntry()
                    }
                }
            }
        }
    }

    fun deleteOldLogs(dir: Path, numLogsToKeep: Long) {
        Files.list(dir)
            .filter { Files.isRegularFile(it) }
            .sorted(
                Comparator.comparingLong { f: Path ->
                        // for some reason, type inference didn't work here!
                        Files.getLastModifiedTime(f).toMillis()
                    }
                    .reversed()
            )
            .skip(numLogsToKeep)
            .forEach {
                try {
                    Files.delete(it)
                } catch (e: IOException) {
                    // don't bother
                }
            }
    }

    @Throws(IOException::class)
    private fun writeToLogd(input: InputStream?, tag: String?) {
        val reader = BufferedReader(InputStreamReader(input))
        reader
            .useLines { lines -> lines.takeWhile { !Thread.interrupted() } }
            .forEach { Log.d(tag, it) }
    }

    private class LineBufferedOutputStream(out: OutputStream?) : BufferedOutputStream(out) {
        @Throws(IOException::class)
        override fun write(buf: ByteArray, off: Int, len: Int) {
            super.write(buf, off, len)
            (0 until len).firstOrNull { buf[off + it] == '\n'.code.toByte() }?.let { flush() }
        }
    }
}

class SerialIOManager(
    vm: VirtualMachine,
    private val executor: ExecutorService,
    private val tag: String
) {
    private val serialOutReadingStream = vm.getConsoleOutput()
    private val serialInWritingStream = try { vm.getConsoleInput() } catch (e: VirtualMachineException) { null }
    private val writingPfd = serialInStreamToPfd(serialInWritingStream)
    public fun getSerialInPfd() = writingPfd
    private val cache: ArrayList<Byte> = arrayListOf()
    private val cacheLock = ReentrantReadWriteLock()
    @Volatile
    private var shutdownFlagged = false

    init {
        startReading()
    }

    private fun startReading() {
        executor.execute {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            try {
                var l = 0
                while (!shutdownFlagged) {
                    val read = serialOutReadingStream.read(buffer)
                    if (read < 0) {
                        Log.w(tag, "IO-check SerialIOManager Reading: read issue (read = $read); has read ${l} so far")
                        continue
                    }

                    // Cache atomic
                    cacheLock.writeLock().lock()
                    cache.addAll(buffer.slice(0..read-1))
                    l = cache.size
                    cacheLock.writeLock().unlock()
                }
            } catch (e: IOException) {
                Log.w(tag, "IO-check SerialIOManager Reading: Failed to read VM serial console output. VM may be shutting down", e)
            } finally {
                serialOutReadingStream.close()
            }
        }
    }

    fun newOutSplitPipe(): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]

        executor.execute {
            try {
                val writingStream = ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)
                var index = 0
                var skipLog = false
                while (!shutdownFlagged || index == 0) {
                    var dataToWrite: ByteArray? = null
                    // Cache atomic
                    cacheLock.readLock().lock()
                    // Follow new content
                    if (cache.size > index) {
                        dataToWrite = cache.subList(index, cache.size).toByteArray()
                        index = cache.size
                    }
                    cacheLock.readLock().unlock()
                    skipLog = dataToWrite == null
                    // Write could block
                    if (dataToWrite == null){
                        Thread.sleep(50)
                    } else {
                        writingStream.write(dataToWrite)
                    }
                }
            } catch (e: IOException) {
                Log.w(tag, "Failed to write VM serial console output", e)
            } catch (e: InterruptedException) {
                Log.w(tag, "Writing VM serial console output interrupted. Is the appVM shutting down?", e)
            } finally {
                // Nothing. Pipes are auto-closed.
            }
        }

        return readEnd
    }

    fun flagForShutdown() {
        shutdownFlagged = true
    }

    fun serialInStreamToPfd(serialIn: OutputStream?): ParcelFileDescriptor? {
        if (serialIn == null) {
            Log.w(tag, "VM does not support serial serial console input. Ignoring input...")
            return devNullWritingPfd()
        }
        val serialIn = serialIn as FileOutputStream
        val serialInPfd = ParcelFileDescriptor.dup(serialIn.getFD())
        return serialInPfd
    }

    fun devNullWritingPfd(): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]

        executor.execute {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val readingStream = ParcelFileDescriptor.AutoCloseInputStream(readEnd)
            try {
                while (!shutdownFlagged) {
                    val read = readingStream.read(buffer)
                }
            } catch (e: IOException) {
                Log.w(tag, "Failed to discard VM serial console input.", e)
            } finally {
                readingStream.close()
            }
        }

        return writeEnd
    }

    companion object {
        const val DEFAULT_BUFFER_SIZE = 4096
    }
}

