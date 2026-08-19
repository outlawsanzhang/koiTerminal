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

object ForwarderHost {
    init {
        System.loadLibrary("forwarder_host_jni")
    }

    @JvmStatic external fun run(cid: Int, callback: ForwardingCallback?)

    @JvmStatic external fun shutdown()

    @JvmStatic external fun updateListeningPorts(ports: IntArray?)

    interface ForwardingCallback {
        fun onForwardingRequestReceived(guestTcpPort: Int, vsockPort: Int)
        fun hostConnectVsock(vsockPort: Int): Int
        fun hostDisonnectVsock(vsockFd: Int)
    }

    abstract class ForwardingCallbackImpl(private val vm: VirtualMachine): ForwardingCallback {
        private val connections: MutableMap<Int, ParcelFileDescriptor> = hashMapOf()

        override fun hostConnectVsock(vsockPort: Int): Int {
            for (retry in 1..RETRIES) {
                try {
                    val pfd = vm.connectVsock(vsockPort.toLong())
                    val oldPfd = connections.remove(pfd.fd)
                    oldPfd?.close()
                    connections[pfd.fd] = pfd
                    return pfd.fd
                } catch (e: Exception) {
                    if (retry == RETRIES) {
                        Log.e("ForwarderHost", "ASDF hostConnectVsock failed (retry = ${retry})", e)
                        throw e
                    } else {
                        Log.e("ForwarderHost", "ASDF hostConnectVsock failed (retry = ${retry}), ${e.message}")
                    }
                    Thread.sleep(RETRY_MS)
                }
            }
            // Unreachable
            return -1
        }

        override fun hostDisonnectVsock(vsockFd: Int) {
            val pfd = connections.remove(vsockFd)
            pfd?.close()
        }

        companion object {
            private const val RETRIES = 5
            private const val RETRY_MS = 300L
        }
    }
}
