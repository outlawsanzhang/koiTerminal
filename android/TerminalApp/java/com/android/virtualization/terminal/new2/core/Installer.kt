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
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.android.virtualization.terminal.ImageArchive
import com.android.virtualization.terminal.InstalledImage
import com.android.virtualization.terminal.new2.util.LoggingMutableStateFlow
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A custom [MutableStateFlow] for [InstallState] that prevents moving from a started state back to
 * a non-started state. This ensures that background status checks don't interrupt an ongoing
 * installation.
 */
private class InstallStateFlow(private val original: MutableStateFlow<InstallState>) :
    MutableStateFlow<InstallState> by original {
    override var value: InstallState
        get() = original.value
        set(newValue) {
            if (shouldIgnore(original.value, newValue)) return
            original.value = newValue
        }

    override suspend fun emit(value: InstallState) {
        if (shouldIgnore(original.value, value)) return
        original.emit(value)
    }

    override fun tryEmit(value: InstallState): Boolean {
        if (shouldIgnore(original.value, value)) return false
        return original.tryEmit(value)
    }

    /**
     * Determines if a state transition should be ignored.
     *
     * We want to prevent background status checks (which set state to Checking or NotInstalled)
     * from overriding an active installation process.
     */
    private fun shouldIgnore(current: InstallState, next: InstallState): Boolean {
        // If installation is underway, ignore background resets UNLESS the
        // installation job is no longer active (e.g., it was cancelled by the user).
        if (
            current.isStarted() &&
                (next is InstallState.Checking || next is InstallState.NotInstalled)
        ) {
            if (Installer.isInstalling) {
                Log.d("Installer", "Ignoring background update while installation is active.")
                return true
            }
        }
        return false
    }
}

object Installer {
    private const val ESTIMATED_DOWNLOAD_SIZE = 700L * 1024 * 1024

    private lateinit var context: Context
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var installedImage: InstalledImage

    private val _installState =
        InstallStateFlow(
            LoggingMutableStateFlow(MutableStateFlow(InstallState.Checking), "Installer")
        )
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    private val _showIfError = MutableStateFlow(false)
    val showIfError = _showIfError.asStateFlow()

    private val _wifiOnly = MutableStateFlow(true)
    val wifiOnly = _wifiOnly.asStateFlow()

    private var networkMonitor: NetworkMonitor? = null
    private var installJob: Job? = null

    /** Whether an installation or upgrade process is currently active. */
    val isInstalling: Boolean
        get() = installJob?.isActive == true

    fun initialize(context: Context) {
        this.context = context.applicationContext
        this.installedImage = InstalledImage.getDefault(this.context)

        networkMonitor = NetworkMonitor(context)

        repositoryScope.launch { checkInstallStatus() }
    }

    fun install() {
        if (installState.value.isStarted() || installState.value is InstallState.Installed) {
            return
        }
        val intent = Intent(context, VmService::class.java)
        context.startForegroundService(intent)
        installJob = repositoryScope.launch { installInternal() }
    }

    fun upgrade() {
        if (installState.value.isStarted()) {
            return
        }
        val intent = Intent(context, VmService::class.java)
        context.startForegroundService(intent)
        installJob =
            repositoryScope.launch {
                try {
                    val progressFlow = MutableStateFlow(0L)
                    _installState.value =
                        InstallState.Installing(
                            ESTIMATED_DOWNLOAD_SIZE,
                            progressFlow.asStateFlow(),
                            networkMonitor?.isWifiConnected?.value ?: false,
                        )
                    installedImage.uninstallAndBackup()
                    installInternal()
                } catch (e: IOException) {
                    _installState.value =
                        InstallState.Error(e, InstallState.ErrorCause.UninstallFailed)
                }
            }
    }

    private suspend fun installInternal() {
        val monitor =
            networkMonitor ?: throw IllegalStateException("NetworkMonitor not initialized")
        val progressFlow = MutableStateFlow(0L)
        _installState.value =
            InstallState.Installing(0L, progressFlow.asStateFlow(), monitor.isWifiConnected.value)
        try {
            downloadAndInstall(monitor, progressFlow)
            _installState.value = InstallState.Installed
        } catch (e: IOException) {
            val cause =
                if (e.message?.contains("No space left on device") == true) {
                    InstallState.ErrorCause.InstallFailedNoSpace
                } else {
                    InstallState.ErrorCause.InstallFailedUnknown
                }
            _installState.value = InstallState.Error(e, cause)
        } catch (e: CancellationException) {
            handleCancellation()
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
        synchronized(this) { (this as Object).notifyAll() }
    }

    fun retryCheck() {
        val state = _installState.value
        if (state is InstallState.Error) {
            repositoryScope.launch { checkInstallStatus() }
        }
    }

    suspend fun uninstall(backupRootfs: Boolean) {
        withContext(Dispatchers.IO) {
            try {
                _installState.value = InstallState.Checking
                if (backupRootfs) {
                    installedImage.uninstallAndBackup()
                } else {
                    installedImage.uninstallFully()
                }
                checkInstallStatus()
            } catch (e: IOException) {
                Log.e("Installer", "Failed to uninstall or backup VM", e)
                _installState.value = InstallState.Error(e, InstallState.ErrorCause.UninstallFailed)
            }
        }
    }

    fun hasBackup(): Boolean {
        return installedImage.hasBackup()
    }

    suspend fun deleteBackup(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                installedImage.deleteBackup()
                true
            } catch (e: IOException) {
                Log.e("Installer", "Failed to delete backup", e)
                _installState.value =
                    InstallState.Error(e, InstallState.ErrorCause.DeleteBackupFailed)
                false
            }
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        _wifiOnly.value = enabled
        synchronized(this) { (this as Object).notifyAll() }
    }

    fun setShowIfError(enabled: Boolean) {
        _showIfError.value = enabled
    }

    private suspend fun downloadAndInstall(
        monitor: NetworkMonitor,
        progressFlow: MutableStateFlow<Long>,
    ) {
        waitForNetwork()

        while (true) {
            try {
                val archive = ImageArchive.getDefault()
                val totalSize = archive.getSize()
                _installState.value =
                    InstallState.Installing(
                        totalSize,
                        progressFlow.asStateFlow(),
                        monitor.isWifiConnected.value,
                    )
                waitForNetwork()
                archive
                    .installTo(context, installedImage.installDir) { inputStream ->
                        SuspendableInputStream(archive, inputStream)
                    }
                    .collect { bytesRead -> progressFlow.value = bytesRead }
                return
            } catch (e: IOException) {
                if (isPermanentError(e)) {
                    throw e
                }
                Log.w("Installer", "Installation failed, retrying...", e)
            }
        }
    }

    private fun isPermanentError(e: IOException): Boolean {
        if (e is java.io.FileNotFoundException) return true
        if (e is java.nio.file.AccessDeniedException) return true
        val msg = e.message
        if (msg != null && msg.contains("No space left on device")) return true
        return false
    }

    private fun handleCancellation() {
        val currentState = _installState.value
        val totalSize =
            when (currentState) {
                is InstallState.Installing -> currentState.totalBytes
                is InstallState.InstallSuspended -> currentState.totalBytes
                else -> ESTIMATED_DOWNLOAD_SIZE
            }
        _installState.value = InstallState.NotInstalled(totalSize)
    }

    private suspend fun checkInstallStatus() {
        _installState.value = InstallState.Checking
        Log.i("Installer", "installedImage.isInstalled() = ${installedImage.isInstalled()}")
        if (installedImage.isInstalled()) {
            Log.i("Installer", "installedImage.isCompatible() = ${installedImage.isCompatible(context)}")
            if (installedImage.isCompatible(context)) {
                _installState.value = InstallState.Installed
            } else {
                try {
                    val downloadSize = ImageArchive.getDefault().getSize()
                    _installState.value = InstallState.NeedsUpgrade(downloadSize)
                } catch (e: IOException) {
                    _installState.value = InstallState.NeedsUpgrade(ESTIMATED_DOWNLOAD_SIZE)
                }
            }
            return
        }

        var retryCount = 0
        while (true) {
            try {
                val downloadSize = ImageArchive.getDefault().getSize()
                _installState.value = InstallState.NotInstalled(downloadSize)
                return
            } catch (e: IOException) {
                if (retryCount >= 2) {
                    Log.w("Installer", "Failed to get image size, using estimate", e)
                    _installState.value = InstallState.NotInstalled(ESTIMATED_DOWNLOAD_SIZE)
                    return
                }
                retryCount++
                delay(1000)
            }
        }
    }

    private fun canInstall(): Boolean {
        if (ImageArchive.isLocalImage()) {
            return true
        }
        val monitor = networkMonitor ?: return false
        if (!monitor.isNetworkConnected.value) {
            Log.d("Installer", "cannot install because network disconnected")
            return false
        }
        if (wifiOnly.value && monitor.isNetworkMetered.value) {
            Log.d("Installer", "cannot install because wifi not available")
            return false
        }
        return true
    }

    /**
     * Blocks the current thread if the network is not available for installation. It updates the
     * install state to [InstallState.InstallSuspended] while waiting, and switches it back to
     * [InstallState.Installing] once the network is restored. This method relies on [Object.wait]
     * and is expected to be woken up by [NetworkMonitor] via [Object.notifyAll].
     */
    private fun waitForNetwork() {
        synchronized(Installer) {
            while (!Installer.canInstall()) {
                val currentState = Installer._installState.value
                if (currentState is InstallState.Installing) {
                    Installer._installState.value =
                        InstallState.InstallSuspended(
                            currentState.totalBytes,
                            currentState.progress,
                        )
                }
                try {
                    (Installer as Object).wait()
                } catch (e: InterruptedException) {
                    throw IOException("Installation interrupted", e)
                }
                if (Installer.installJob?.isActive != true) {
                    throw CancellationException("Installation cancelled")
                }
            }
            val currentState = Installer._installState.value
            val monitor = networkMonitor
            if (currentState is InstallState.InstallSuspended && monitor != null) {
                Installer._installState.value =
                    InstallState.Installing(
                        currentState.totalBytes,
                        currentState.progress,
                        monitor.isWifiConnected.value,
                    )
            }
        }
    }

    /**
     * An InputStream wrapper that supports pausing and resuming the download. It monitors the
     * network state via Installer.waitForNetwork() and automatically reconnects to the archive
     * stream if the connection is lost.
     */
    private class SuspendableInputStream(
        private val archive: ImageArchive,
        initialInputStream: InputStream,
    ) : InputStream() {
        private var inputStream = initialInputStream
        private var position = 0L

        @Throws(IOException::class)
        override fun read(buf: ByteArray?, offset: Int, numToRead: Int): Int {
            while (true) {
                try {
                    Installer.waitForNetwork()
                    val result = inputStream.read(buf, offset, numToRead)
                    if (result != -1) {
                        position += result
                    }
                    return result
                } catch (e: IOException) {
                    Log.d("Installer", "Read error, attempting to resume at $position", e)
                    // Wait for network recovery and reconnect
                    reconnect()
                }
            }
        }

        @Throws(IOException::class)
        override fun read(): Int {
            while (true) {
                try {
                    Installer.waitForNetwork()
                    val result = inputStream.read()
                    if (result != -1) {
                        position++
                    }
                    return result
                } catch (e: IOException) {
                    Log.d("Installer", "Read error, attempting to resume at $position", e)
                    // Wait for network recovery and reconnect
                    reconnect()
                }
            }
        }

        private fun reconnect() {
            // Wait until we can install again
            Installer.waitForNetwork()
            try {
                inputStream.close()
            } catch (ignored: IOException) {}
            try {
                inputStream = archive.getInputStream(position)
            } catch (e: IOException) {
                // If reconnect fails immediately, it will be retried in the next loop iteration
                Log.e("Installer", "Failed to reconnect")
            }
        }
    }

    /**
     * Monitors the network state and updates flows accordingly. It also notifies Installer whenever
     * the network state changes.
     */
    private class NetworkMonitor(context: Context) {
        private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

        private val _isNetworkConnected = MutableStateFlow(false)
        val isNetworkConnected = _isNetworkConnected.asStateFlow()

        private val _isNetworkMetered = MutableStateFlow(false)
        val isNetworkMetered = _isNetworkMetered.asStateFlow()

        private val _isWifiConnected = MutableStateFlow(false)
        val isWifiConnected = _isWifiConnected.asStateFlow()

        init {
            // Calculate initial state
            val network = connectivityManager?.activeNetwork
            val caps = network?.let { connectivityManager?.getNetworkCapabilities(it) }
            processCapabilities(caps)

            connectivityManager?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        // No-op. processCapabilities() will be called in onCapabilitiesChanged.
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        processCapabilities(networkCapabilities)
                    }

                    override fun onLost(network: Network) {
                        // The network object passed here still represents the network just before
                        // it was lost, and it might still be considered connected if we query its
                        // capabilities. Thus, we explicitly set all network states to false.
                        updateNetworkState(isConnected = false, isWifi = false, isMetered = false)
                    }
                }
            )
        }

        private fun processCapabilities(caps: NetworkCapabilities?) {
            val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val isConnected =
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val isMetered =
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
            updateNetworkState(isConnected, isWifi, isMetered)
        }

        private fun updateNetworkState(isConnected: Boolean, isWifi: Boolean, isMetered: Boolean) {
            _isWifiConnected.value = isWifi
            _isNetworkConnected.value = isConnected
            _isNetworkMetered.value = isMetered

            Log.d(
                "Installer",
                "update network state. wificonnected=$isWifi networkconnected=$isConnected networkmetered=$isMetered",
            )
            synchronized(Installer) { (Installer as Object).notifyAll() }
        }
    }
}
