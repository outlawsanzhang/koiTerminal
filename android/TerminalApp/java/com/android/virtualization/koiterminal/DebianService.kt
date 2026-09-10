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

import android.content.Context
import android.os.RemoteException
import android.system.virtualizationcommon.IGuestAgent
import android.system.virtualmachine.VirtualMachine
import android.util.Log
import androidx.annotation.Keep
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.virtualization.debian.aidl.IDebianService
import com.android.virtualization.debian.aidl.IkoiService
import com.android.virtualization.debian.aidl.IkoiHostCallback
import com.android.virtualization.debian.aidl.IVmActivePortListener
import com.android.virtualization.koiterminal.ForwarderHost.ForwardingCallbackImpl
import com.android.virtualization.koiterminal.ForwarderHostSetup
import com.android.virtualization.koiterminal.MainActivity.Companion.TAG
import com.android.virtualization.koiterminal.Socks5Setup
import com.android.virtualization.koiterminal.new2.ui.main.NetworkConnection
import com.android.virtualization.koiterminal.new2.ui.main.SettingsViewModel
import com.android.virtualization.terminal.proto.ActivePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class DebianService(
    private val context: Context,
    private val scope: CoroutineScope,
    private val vm: VirtualMachine,
    private val guestAgent: IGuestAgent,
    private val koi_service: IkoiService?,
    private val service: IDebianService,
) : DebianServiceBase {
    private lateinit var portsStateManager: PortsStateManager
    private val portsStateListener: PortsStateManager.Listener =
        object : PortsStateManager.Listener {
            override fun onPortsStateUpdated(oldActivePorts: Set<Int>, newActivePorts: Set<Int>) {
                updateListeningPorts()
            }
        }
    private val koiService = KoiService(context, scope, vm, koi_service)
    private val vmActivePortsListener = VmActivePortsListener()

    init {
        portsStateManager = PortsStateManager.getInstance(context)
        portsStateManager.registerListener(portsStateListener)
        updateListeningPorts()
        val sharedPref = context.getSharedPreferences(SettingsViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        val currentNetwork = SettingsViewModel.networkConnectionPref(sharedPref)
        val socks5Delegate = SettingsViewModel.socks5DelegatePref(sharedPref)
        val socks5LoopbackOk = SettingsViewModel.socks5LoopbackOkPref(sharedPref)
        val isDelegate = currentNetwork == NetworkConnection.DELEGATE_SOCKS5
        val isManaged = currentNetwork == NetworkConnection.MANAGED_SOCKS5
        val enableSocks5 = isDelegate || isManaged
        val socks5 = Socks5Setup(
            delegated = if (isDelegate) socks5Delegate else 0,
            loopback = socks5LoopbackOk,
            udp = false,
            multicast = false,
            timeout_ms = 10000,
        )

        scope.launch(Dispatchers.IO) {
            try {
                val setup = ForwarderHostSetup(koiService.getRcServicesAndSetupGuest(enableSocks5), socks5)
                ForwarderHost.run(vm.cid, setup, ForwarderHostCallback(service, vm))
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
        portsStateManager.unregisterListener(portsStateListener) // upstream bug?
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
    private class ForwarderHostCallback(private val service: IDebianService, vm: VirtualMachine) : ForwardingCallbackImpl(vm) {
        override fun onForwardingRequestReceived(guestTcpPort: Int, vsockPort: Int) {
            try {
                Log.d(TAG, "service.requestForwarding($guestTcpPort, $vsockPort)")
                service.requestForwarding(guestTcpPort, vsockPort)
                Log.d(TAG, "service.requestForwarding($guestTcpPort, $vsockPort) done")
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

internal class KoiService(
    private val context: Context,
    private val scope: CoroutineScope,
    private val vm: VirtualMachine,
    private val service: IkoiService?,
) {
    private val callback = KoiHostCallback()

    init {
        service?.let { service ->
            service.registerHostCallback(callback)
        }
    }

    fun getRcServicesAndSetupGuest(enableSocks5: Boolean): IntArray {
        return if (service == null) {
            intArrayOf()
        } else {
            val services = mutableListOf<Int>()
            if (enableSocks5) {
                service.openReverseConnectedPort(ForwarderHost.defaults.SOCKS5_PORT)
                services.add(ForwarderHost.defaults.REVCONN_SOCKS5_PROXY)
            }
            services.toIntArray()
        }
    }

    @Keep
    private class KoiHostCallback() : IkoiHostCallback.Stub() {
        override fun requestReverseConnection(vsockPort: Int) {
            ForwarderHost.requestReverseConnection(vsockPort)
        }
    }
}
