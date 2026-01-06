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
package com.android.virtualization.terminal.new2.core

import android.content.Context
import android.system.virtualizationcommon.IGuestAgent
import android.util.Log
import com.android.virtualization.debian.aidl.IDebianService
import com.android.virtualization.terminal.ClipboardController
import com.android.virtualization.terminal.DebianService
import com.android.virtualization.terminal.DebianServiceBase
import com.android.virtualization.terminal.DebianServiceGrpc
import com.android.virtualization.terminal.PortsStateManager
import com.android.virtualization.terminal.StorageBalloonWorker
import io.grpc.InsecureServerCredentials
import io.grpc.Server
import io.grpc.okhttp.OkHttpServerBuilder
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OpenPort(val port: Int, val name: String, val isForwarded: Boolean) {
    fun isSaved() = name.isEmpty()
}

class GuestAgentController(
    private val context: Context,
    private val aidlGuestAgent: Boolean,
    private val scope: CoroutineScope,
) {
    private var server: Server? = null
    private var debianService: DebianServiceBase? = null
    private var clipboardController: ClipboardController? = null
    private val portsStateManager = PortsStateManager.getInstance(context)

    private val _ports = MutableStateFlow<List<OpenPort>>(emptyList())
    val ports: StateFlow<List<OpenPort>> = _ports.asStateFlow()

    private val portsListener =
        object : PortsStateManager.Listener {
            override fun onPortsStateUpdated(oldActivePorts: Set<Int>, newActivePorts: Set<Int>) {
                updatePortsState()
            }
        }

    @Synchronized
    fun startServer(): Int {
        if (aidlGuestAgent) {
            Log.w(TAG, "Ignoring gRPC setup. soong generated CIDATA implies AIDL communication.")
            return 0
        }
        if (debianService != null) {
            Log.w(TAG, "GuestAgentController is started again. It might had been crashed.")
            stop()
        }
        val port = startDebianServerGrpc()
        portsStateManager.registerListener(portsListener)
        updatePortsState()
        return port
    }

    @Synchronized
    fun start(cid: Int, guestAgent: IGuestAgent, service: IDebianService) {
        Log.d(TAG, "Starting guest agent controller with AIDL")

        if (!aidlGuestAgent) {
            Log.w(TAG, "Ignoring AIDL setup. soong generated CIDATA is required")
            return
        }
        if (debianService != null) {
            Log.w(TAG, "GuestAgentController is started again. It might had been crashed.")
            stop() // Safely stop existing before recreating
        }
        debianService = DebianService(context, scope, cid, guestAgent, service)
        clipboardController = ClipboardController(context, service)
        portsStateManager.registerListener(portsListener)
        updatePortsState()
        StorageBalloonWorker.start(context, debianService!!)
    }

    @Synchronized
    fun stop() {
        portsStateManager.unregisterListener(portsListener)
        clipboardController?.onDestroy()
        clipboardController = null
        stopDebianServer()
    }

    @Synchronized
    fun pullClipboardFromGuest() {
        clipboardController?.pullFromGuest()
    }

    @Synchronized
    fun pushClipboardToGuest() {
        clipboardController?.pushToGuestIfNeeded()
    }

    @Synchronized
    fun shutdownVm() {
        debianService?.shutdownDebian()
    }

    fun enablePortForwarding(port: Int, enable: Boolean) {
        portsStateManager.updateEnabledPort(port, enable)
    }

    private fun updatePortsState() {
        val activePorts = portsStateManager.getActivePorts()
        val enabledPorts = portsStateManager.getEnabledPorts()
        val openPorts =
            activePorts
                .mapNotNull { port ->
                    portsStateManager.getActivePortInfo(port)?.let {
                        OpenPort(it.port, it.comm, enabledPorts.contains(port))
                    }
                }
                .toMutableList()
        val savedPorts = enabledPorts.subtract(activePorts)
        for (port in savedPorts) {
            openPorts.add(OpenPort(port, "", true))
        }
        _ports.value = openPorts
    }

    private fun startDebianServerGrpc(): Int {
        try {
            // TODO(b/372666638): gRPC for java doesn't support vsock for now.
            val port = 0
            val service = DebianServiceGrpc(context)
            debianService = service
            server =
                OkHttpServerBuilder.forPort(port, InsecureServerCredentials.create())
                    .addService(service)
                    .build()
                    .start()
        } catch (e: IOException) {
            Log.d(TAG, "grpc server error", e)
            throw RuntimeException("cannot start grpc server", e)
        }

        StorageBalloonWorker.start(context, debianService!!)

        return server!!.port
    }

    private fun stopDebianServer() {
        debianService?.stop()
        server?.shutdown()
        server = null
        debianService = null
    }

    companion object {
        private const val TAG = "GuestAgentController"
    }
}
