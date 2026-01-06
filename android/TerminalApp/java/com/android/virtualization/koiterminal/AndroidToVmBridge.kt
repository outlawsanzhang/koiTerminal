/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.VmSocketAddress
import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AndroidToVmBridge(
    private val vmCid: Int,
    private val vmPort: Int = 7681,
    public val secretKey: String = UUID.randomUUID().toString(),
) {

    companion object {
        private const val TAG = "AndroidToVmBridge"
        // 600 retries * 100ms = 60 seconds total wait
        private const val MAX_RETRIES = 600
        private const val RETRY_DELAY_MS = 100L
        private const val BUFFER_SIZE = 8192
    }

    private val authCookie = "access_token=$secretKey"
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)

    /**
     * Starts the bridge.
     *
     * @return The bound local TCP port, or null if failed.
     */
    // TODO(b/464250786): we should initiate this function when a guest agent reports its readiness
    fun start(): Int? {
        if (isRunning.get()) return serverSocket?.localPort

        return try {
            val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            this.serverSocket = socket
            this.isRunning.set(true)

            val port = socket.localPort
            Log.i(TAG, "Bridge started on 127.0.0.1:$port (Target VM: $vmCid:$vmPort)")

            thread(name = "Bridge-Listener") { listenLoop(socket) }
            port
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start bridge", e)
            null
        }
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            try {
                serverSocket?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error while closing server socket", e)
            }
            serverSocket = null
        }
    }

    private fun listenLoop(socket: ServerSocket) {
        try {
            while (isRunning.get()) {
                val clientSocket = socket.accept()
                thread(name = "Bridge-Client") { handleClient(clientSocket) }
            }
        } catch (e: Exception) {
            // If the socket is closed intentionally, isRunning will be false.
            if (isRunning.get()) {
                Log.w(TAG, "Accept loop stopped unexpectedly", e)
            } else {
                Log.d(TAG, "Accept loop finished")
            }
        }
    }

    private fun handleClient(clientSocket: Socket) {
        var vsockFd: FileDescriptor? = null
        try {
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            // 1. Peek headers for auth check
            val buffer = ByteArray(4096)
            val bytesRead = clientIn.read(buffer)
            if (bytesRead == -1) return

            val headers = String(buffer, 0, bytesRead, StandardCharsets.UTF_8)

            if (!headers.contains(authCookie)) {
                Log.w(TAG, "Auth failed: Missing cookie")
                clientOut.write("HTTP/1.1 403 Forbidden\r\n\r\nAccess Denied".toByteArray())
                return
            }

            // 2. Connect to VM (Silent retry until failure)
            vsockFd = connectWithRetry()

            if (vsockFd == null) {
                clientOut.write("HTTP/1.1 504 Gateway Timeout\r\n\r\nVM Not Ready".toByteArray())
                return
            }

            // 3. Forward buffered headers first
            val vmOut = FileOutputStream(vsockFd)
            val vmIn = FileInputStream(vsockFd)

            vmOut.write(buffer, 0, bytesRead)
            vmOut.flush()

            // 4. Start bidirectional pipe
            val closeConnection = {
                try {
                    clientSocket.close()
                } catch (e: Exception) {
                    Log.v(TAG, "Error closing client socket", e)
                }
                if (vsockFd != null && vsockFd.valid()) {
                    try {
                        Os.close(vsockFd)
                    } catch (e: Exception) {
                        Log.v(TAG, "Error closing vsock", e)
                    }
                }
            }

            val t1 = thread {
                pipe("Client->VM", clientIn, vmOut)
                closeConnection()
            }
            val t2 = thread {
                pipe("VM->Client", vmIn, clientOut)
                closeConnection()
            }

            t1.join()
            t2.join()
        } catch (e: Exception) {
            Log.d(TAG, "Session ended: ${e.message}")
        } finally {
            try {
                clientSocket.close()
            } catch (e: Exception) {
                Log.v(TAG, "Error closing client socket", e)
            }
            if (vsockFd != null && vsockFd.valid()) {
                try {
                    Os.close(vsockFd)
                } catch (e: Exception) {
                    Log.v(TAG, "Error closing vsock", e)
                }
            }
        }
    }

    // TODO(b/464250786): when a guest agent notifies the host when it is ready, we don't need to
    // keep trying until it is ready.
    private fun connectWithRetry(): FileDescriptor? {
        val vmAddress = VmSocketAddress(vmPort, vmCid)

        for (i in 1..MAX_RETRIES) {
            if (!isRunning.get()) return null

            var fd: FileDescriptor? = null
            try {
                fd = Os.socket(OsConstants.AF_VSOCK, OsConstants.SOCK_STREAM, 0)
                Os.connect(fd, vmAddress)
                return fd
            } catch (e: ErrnoException) {
                if (fd != null && fd.valid()) {
                    try {
                        Os.close(fd)
                    } catch (_: Exception) {}
                }

                if (i == MAX_RETRIES) {
                    Log.e(TAG, "VM connection failed after $MAX_RETRIES attempts")
                    return null
                }
                try {
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (_: InterruptedException) {}
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in connectWithRetry", e)
                return null
            }
        }
        return null
    }

    private fun pipe(direction: String, input: InputStream, output: OutputStream) {
        val buf = ByteArray(BUFFER_SIZE)
        var len: Int
        try {
            while (input.read(buf).also { len = it } != -1) {
                output.write(buf, 0, len)
                output.flush()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Pipe [$direction] closed: ${e.message}")
        }
    }
}
