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

import android.content.Context
import android.os.RemoteException
import android.system.virtualizationcommon.IGuestAgent
import android.util.Log
import androidx.annotation.Keep
import com.android.virtualization.debian.aidl.IDebianService
import com.android.virtualization.debian.aidl.IVmActivePortListener
import com.android.virtualization.terminal.ForwarderHost.ForwardingCallback
import com.android.virtualization.terminal.MainActivity.Companion.TAG
import com.android.virtualization.terminal.proto.ActivePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class DebianService(
    private val context: Context,
    private val scope: CoroutineScope,
    private val cid: Int,
    private val guestAgent: IGuestAgent,
    private val service: IDebianService,
) : DebianServiceBase {
    private lateinit var portsStateManager: PortsStateManager
    private val portsStateListener: PortsStateManager.Listener =
        object : PortsStateManager.Listener {
            override fun onPortsStateUpdated(oldActivePorts: Set<Int>, newActivePorts: Set<Int>) {
                updateListeningPorts()
            }
        }
    private val vmActivePortsListener = VmActivePortsListener()

    init {
        portsStateManager = PortsStateManager.getInstance(context)
        portsStateManager.registerListener(portsStateListener)
        updateListeningPorts()

        scope.launch(Dispatchers.IO) {
            try {
                ForwarderHost.run(cid, ForwarderHostCallback(service))
            } catch (e: Exception) {
                Log.d(TAG, "Exception from JNI", e)
            }
        }
        service.setVmActivePortListener(vmActivePortsListener)
    }

    override fun shutdownDebian(): Boolean {
        try {
            guestAgent.shutdownAsync()
            return true
        } catch (e: RemoteException) {
            Log.e(TAG, "Exception from shutdownDebian()", e)
            return false
        }
    }

    override fun setAvailableStorageBytes(availableBytes: Long): Boolean {
        try {
            service.requestStorageBalloon(availableBytes)
            return true
        } catch (e: RemoteException) {
            Log.e(TAG, "Exception from setAvailableStorageBytes()", e)
            return false
        }
    }

    override fun stop() {
        ForwarderHost.shutdown()
    }

    inner private class VmActivePortsListener : IVmActivePortListener.Stub() {
        override fun reportActivePorts(ports: List<IVmActivePortListener.ActivePort>) {
            val portsList: List<com.android.virtualization.terminal.proto.ActivePort> =
                ports.map {
                    com.android.virtualization.terminal.proto.ActivePort.newBuilder()
                        .setPort(it.port)
                        .setComm(it.comm)
                        .build()
                }
            portsStateManager.updateActivePorts(portsList)
        }
    }

    @Keep
    private class ForwarderHostCallback(private val service: IDebianService) : ForwardingCallback {
        override fun onForwardingRequestReceived(guestTcpPort: Int, vsockPort: Int) {
            try {
                service.requestForwarding(guestTcpPort, vsockPort)
            } catch (e: RemoteException) {
                Log.e(
                    TAG,
                    "Exception from requestForwarding(), guestTcpPort=${guestTcpPort}, vsockPort=${vsockPort}",
                    e,
                )
            }
        }
    }

    private fun updateListeningPorts() {
        val activePorts: Set<Int> = portsStateManager.getActivePorts()
        val enabledPorts: Set<Int> = portsStateManager.getEnabledPorts()
        val ports = activePorts.filter { enabledPorts.contains(it) }.toIntArray()
        ForwarderHost.updateListeningPorts(ports)
    }
}
