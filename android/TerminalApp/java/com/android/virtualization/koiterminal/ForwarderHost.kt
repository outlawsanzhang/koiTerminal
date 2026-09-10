/*
 * Copyright 2026 The Android Open Source Project
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

import android.os.ParcelFileDescriptor
import android.system.virtualmachine.VirtualMachine
import android.util.Log
import com.android.virtualization.debian.aidl.IkoiService

data class Socks5Setup(
    val delegated: Int = 0, // =0 for managed, >0 for 3rd-party port
    val loopback: Boolean = false,
    val private: Boolean = false,
    val udp: Boolean = false,
    val multicast: Boolean = false,
    val timeout_ms: Int = 10000,
)

data class ForwarderHostSetup(
    val rcServices: IntArray = intArrayOf(),
    val socks5: Socks5Setup = Socks5Setup(),
) {
    val REVCONN_SOCKS5_PROXY = 0 // There could potentially be more to come, such as an NFS
    val SOCKS5_PORT = IkoiService.SOCKS5_PORT.toInt()
}

object ForwarderHost {
    // To be synced with android/forwarder_host/src/forwarder_host.rs
    public val defaults = ForwarderHostSetup()

    init {
        System.loadLibrary("forwarder_host_jni")
    }

    @JvmStatic external fun run(cid: Int, setup: ForwarderHostSetup, callback: ForwardingCallback?)

    @JvmStatic external fun requestReverseConnection(vsockPort: Int)

    @JvmStatic external fun shutdown()

    @JvmStatic external fun updateListeningPorts(ports: IntArray?)

    interface ForwardingCallback {
        fun onForwardingRequestReceived(guestTcpPort: Int, vsockPort: Int)
        fun hostConnectVsock(vsockPort: Int): Int
        fun hostDisconnectVsock(vsockFd: Int)
    }

    abstract class ForwardingCallbackImpl(private val vm: VirtualMachine): ForwardingCallback {
        private val connections: MutableMap<Int, ParcelFileDescriptor> = hashMapOf()

        override fun hostConnectVsock(vsockPort: Int): Int {
            Log.d("ForwarderHost", "ForwarderHost hostConnectVsock called on port $vsockPort (retries = ${RETRIES})")
            for (retry in 1..RETRIES) {
                try {
                    val pfd = vm.connectVsock(vsockPort.toLong())
                    val oldPfd = connections.remove(pfd.fd)
                    oldPfd?.close()
                    connections[pfd.fd] = pfd
                    return pfd.fd
                } catch (e: Exception) {
                    if (retry == RETRIES) {
                        Log.e("ForwarderHost", "ForwarderHost hostConnectVsock failed (retry = ${retry})", e)
                        throw e
                    } else {
                        Log.e("ForwarderHost", "ForwarderHost hostConnectVsock failed (retry = ${retry}), ${e.message}")
                    }
                    Thread.sleep(RETRY_MS)
                }
            }
            // Unreachable
            return -1
        }

        override fun hostDisconnectVsock(vsockFd: Int) {
            if (vsockFd == -1) {
                // Shortcut for disconnect all
                for ((key, pfd) in connections) {
                    try {
                        pfd.close()
                    } catch (e: Exception) {
                        Log.e("ForwarderHost", "ForwarderHost hostDisconnectVsock failed on pfd=${pfd.fd}", e)
                    }
                }
                connections.clear()
                Log.d("ForwarderHost", "ForwarderHost hostDisconnectVsock fd=$vsockFd (disconnect all)")
            } else {
                try {
                    val pfd = connections.remove(vsockFd)
                    pfd?.close()
                    Log.d("ForwarderHost", "ForwarderHost hostDisconnectVsock fd=$vsockFd (remaining = ${connections.size})")
                } catch (e: Exception) {
                    Log.e("ForwarderHost", "ForwarderHost hostDisconnectVsock failed", e)
                }
            }
        }

        companion object {
            private const val RETRIES = 5
            private const val RETRY_MS = 300L
        }
    }
}
