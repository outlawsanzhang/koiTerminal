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
import android.util.Log
import androidx.annotation.Keep
import com.android.internal.annotations.GuardedBy
import com.android.system.virtualmachine.flags.Flags
import com.android.virtualization.terminal.MainActivity.Companion.TAG
import com.android.virtualization.terminal.proto.DebianServiceGrpc.DebianServiceImplBase
import com.android.virtualization.terminal.proto.ForwardingRequestItem
import com.android.virtualization.terminal.proto.QueueOpeningRequest
import com.android.virtualization.terminal.proto.ReportVmActivePortsRequest
import com.android.virtualization.terminal.proto.ReportVmActivePortsResponse
import com.android.virtualization.terminal.proto.ShutdownQueueOpeningRequest
import com.android.virtualization.terminal.proto.ShutdownRequestItem
import com.android.virtualization.terminal.proto.StorageBalloonQueueOpeningRequest
import com.android.virtualization.terminal.proto.StorageBalloonRequestItem
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver

internal class DebianServiceImpl(context: Context) : DebianServiceImplBase() {
    private val portsStateManager = PortsStateManager.getInstance(context)
    private var portsStateListener: PortsStateManager.Listener? = null
    private var shutdownRunnable: Runnable? = null
    private val mLock = Object()
    @GuardedBy("mLock") private var storageBalloonCallback: StorageBalloonCallback? = null

    override fun reportVmActivePorts(
        request: ReportVmActivePortsRequest,
        responseObserver: StreamObserver<ReportVmActivePortsResponse?>,
    ) {
        portsStateManager.updateActivePorts(request.portsList)
        Log.d(TAG, "reportVmActivePorts: " + portsStateManager.getActivePorts())
        val reply = ReportVmActivePortsResponse.newBuilder().setSuccess(true).build()
        responseObserver.onNext(reply)
        responseObserver.onCompleted()
    }

    override fun openForwardingRequestQueue(
        request: QueueOpeningRequest,
        responseObserver: StreamObserver<ForwardingRequestItem?>,
    ) {
        Log.d(TAG, "OpenForwardingRequestQueue")
        portsStateListener =
            object : PortsStateManager.Listener {
                override fun onPortsStateUpdated(
                    oldActivePorts: Set<Int>,
                    newActivePorts: Set<Int>,
                ) {
                    updateListeningPorts()
                }
            }
        portsStateManager.registerListener(portsStateListener!!)
        updateListeningPorts()
        runForwarderHost(request.cid, ForwarderHostCallback(responseObserver))
        responseObserver.onCompleted()
    }

    fun shutdownDebian(): Boolean {
        if (shutdownRunnable == null) {
            Log.d(TAG, "mShutdownRunnable is not ready.")
            return false
        }
        shutdownRunnable!!.run()
        return true
    }

    override fun openShutdownRequestQueue(
        request: ShutdownQueueOpeningRequest?,
        responseObserver: StreamObserver<ShutdownRequestItem?>,
    ) {
        val serverCallStreamObserver =
            responseObserver as ServerCallStreamObserver<ShutdownRequestItem?>
        serverCallStreamObserver.setOnCancelHandler { shutdownRunnable = null }
        Log.d(TAG, "openShutdownRequestQueue")
        shutdownRunnable = Runnable {
            if (serverCallStreamObserver.isCancelled()) {
                return@Runnable
            }
            responseObserver.onNext(ShutdownRequestItem.newBuilder().build())
            responseObserver.onCompleted()
            shutdownRunnable = null
        }
    }

    private class StorageBalloonCallback(
        private val responseObserver: StreamObserver<StorageBalloonRequestItem?>
    ) {
        fun setAvailableStorageBytes(availableBytes: Long) {
            Log.d(TAG, "send setStorageBalloon: $availableBytes")
            val item =
                StorageBalloonRequestItem.newBuilder().setAvailableBytes(availableBytes).build()
            responseObserver.onNext(item)
        }

        fun closeConnection() {
            Log.d(TAG, "close StorageBalloonQueue")
            responseObserver.onCompleted()
        }
    }

    fun setAvailableStorageBytes(availableBytes: Long): Boolean {
        synchronized(mLock) {
            if (storageBalloonCallback == null) {
                Log.d(TAG, "storageBalloonCallback is not ready.")
                return false
            }
            storageBalloonCallback!!.setAvailableStorageBytes(availableBytes)
        }
        return true
    }

    override fun openStorageBalloonRequestQueue(
        request: StorageBalloonQueueOpeningRequest?,
        responseObserver: StreamObserver<StorageBalloonRequestItem?>,
    ) {
        if (!Flags.terminalStorageBalloon()) {
            return
        }
        Log.d(TAG, "openStorageRequestQueue")
        synchronized(mLock) {
            if (storageBalloonCallback != null) {
                Log.d(TAG, "RequestQueue already exists. Closing connection.")
                storageBalloonCallback!!.closeConnection()
            }
            storageBalloonCallback = StorageBalloonCallback(responseObserver)
        }
    }

    fun closeStorageBalloonRequestQueue() {
        Log.d(TAG, "Stopping storage balloon queue")
        synchronized(mLock) {
            if (storageBalloonCallback != null) {
                storageBalloonCallback!!.closeConnection()
                storageBalloonCallback = null
            }
        }
    }

    @Keep
    private class ForwarderHostCallback(
        private val responseObserver: StreamObserver<ForwardingRequestItem?>
    ) {

        fun onForwardingRequestReceived(guestTcpPort: Int, vsockPort: Int) {
            val item =
                ForwardingRequestItem.newBuilder()
                    .setGuestTcpPort(guestTcpPort)
                    .setVsockPort(vsockPort)
                    .build()
            responseObserver.onNext(item)
        }
    }

    fun killForwarderHost() {
        Log.d(TAG, "Stopping port forwarding")
        if (portsStateListener != null) {
            portsStateManager.unregisterListener(portsStateListener!!)
            portsStateListener = null
        }
        terminateForwarderHost()
    }

    private fun updateListeningPorts() {
        val activePorts: Set<Int> = portsStateManager.getActivePorts()
        val enabledPorts: Set<Int> = portsStateManager.getEnabledPorts()
        updateListeningPorts(activePorts.filter { enabledPorts.contains(it) }.toIntArray())
    }

    companion object {
        init {
            System.loadLibrary("forwarder_host_jni")
        }

        @JvmStatic private external fun runForwarderHost(cid: Int, callback: ForwarderHostCallback?)

        @JvmStatic private external fun terminateForwarderHost()

        @JvmStatic private external fun updateListeningPorts(ports: IntArray?)
    }
}
