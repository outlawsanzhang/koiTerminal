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
import android.system.virtualmachine.VirtualMachine
import android.system.virtualmachine.VirtualMachineConfig
import android.util.Log
import androidx.annotation.WorkerThread
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.util.concurrent.ExecutorService
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import libcore.io.Streams

/**
 * Forwards VM's console output to a file on the Android side, and VM's log output to Android logd.
 */
internal object Logger {
    fun setup(context: Context, vm: VirtualMachine, executor: ExecutorService) {
        val tag = vm.name
        val dir = context.getFileStreamPath(vm.name + ".log").toPath()

        if (vm.config.debugLevel != VirtualMachineConfig.DEBUG_LEVEL_FULL) {
            Log.i(tag, "Logs are not captured. Non-debuggable VM.")
            return
        }

        if (Files.isRegularFile(dir)) {
            Log.i(tag, "Removed legacy log file: $dir")
            Files.delete(dir)
        }
        Files.createDirectories(dir)
        deleteOldLogs(dir, 10)
        val logPath = dir.resolve(LocalDateTime.now().toString() + ".txt")
        val console = vm.getConsoleOutput()
        val file = Files.newOutputStream(logPath, StandardOpenOption.CREATE)
        executor.execute({
            try {
                console.use { console ->
                    LineBufferedOutputStream(file).use { fileOutput ->
                        Streams.copy(console, fileOutput)
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed to log console output. VM may be shutting down", e)
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
    }

    // Called by ErrorActivity in another process.
    @WorkerThread
    public fun zipLogs(context: Context, logZipFilePath: Path) {
        Files.newOutputStream(logZipFilePath, StandardOpenOption.CREATE).use { fos ->
            ZipOutputStream(fos).use { zos ->
                val logDirs =
                    context.filesDir.listFiles { it.isDirectory() && it.name.endsWith(".log") }
                        ?: return
                for (dir in logDirs) {
                    val logFiles =
                        dir.listFiles { it.isFile() && it.name.endsWith(".txt") } ?: continue
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
