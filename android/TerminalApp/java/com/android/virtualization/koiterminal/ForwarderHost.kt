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
package com.android.virtualization.terminal

object ForwarderHost {
    init {
        System.loadLibrary("forwarder_host_jni")
    }

    @JvmStatic external fun run(cid: Int, callback: ForwardingCallback?)

    @JvmStatic external fun shutdown()

    @JvmStatic external fun updateListeningPorts(ports: IntArray?)

    interface ForwardingCallback {
        fun onForwardingRequestReceived(guestTcpPort: Int, vsockPort: Int)
    }
}
