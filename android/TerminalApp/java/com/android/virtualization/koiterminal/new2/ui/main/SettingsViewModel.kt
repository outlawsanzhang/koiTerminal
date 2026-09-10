/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.virtualization.koiterminal.new2.ui.main

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.virtualization.koiterminal.new2.core.Installer
import com.android.virtualization.koiterminal.new2.core.VmController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class DisplayResolution(val scale: Float) {
    FULL(1.0f),
    HALF(0.5f),
    QUARTER(0.25f),
}

enum class NetworkConnection() {
    NONE,
    FULL,
    MANAGED_SOCKS5,
    DELEGATE_SOCKS5,
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val sharedPref = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _currentMemoryMb =
        MutableStateFlow(sharedPref.getInt(KEY_MEMORY_MIB, DEFAULT_MEMORY_MIB))
    val currentMemoryMb: StateFlow<Int> = _currentMemoryMb.asStateFlow()

    private val _displayResolution =
        MutableStateFlow(
            sharedPref.getString(KEY_DISPLAY_RESOLUTION, DisplayResolution.HALF.name)!!
        )
    val displayResolution: StateFlow<DisplayResolution> =
        _displayResolution
            .map { DisplayResolution.valueOf(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), DisplayResolution.HALF)

    private val _keepAwakeMinutes = MutableStateFlow(sharedPref.getInt(KEY_KEEP_AWAKE, 0))
    val keepAwakeMinutes: StateFlow<Int> = _keepAwakeMinutes.asStateFlow()

    private val _networkConnection = MutableStateFlow(networkConnectionPref(sharedPref).name)
    val networkConnection: StateFlow<NetworkConnection> =
        _networkConnection
            .map { NetworkConnection.valueOf(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), NetworkConnection.FULL)
    private val _socks5Delegate = MutableStateFlow(socks5DelegatePref(sharedPref))
    val socks5Delegate: StateFlow<Int> = _socks5Delegate.asStateFlow()
    private val _socks5LoopbackOk = MutableStateFlow(socks5LoopbackOkPref(sharedPref))
    val socks5LoopbackOk: StateFlow<Boolean> = _socks5LoopbackOk.asStateFlow()


    private val sharedPrefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                KEY_MEMORY_MIB -> {
                    _currentMemoryMb.value = sharedPref.getInt(KEY_MEMORY_MIB, DEFAULT_MEMORY_MIB)
                }
                KEY_DISPLAY_RESOLUTION -> {
                    _displayResolution.value =
                        sharedPref.getString(KEY_DISPLAY_RESOLUTION, DisplayResolution.HALF.name)!!
                }
                KEY_KEEP_AWAKE -> {
                    _keepAwakeMinutes.value = sharedPref.getInt(KEY_KEEP_AWAKE, 0)
                }
                KEY_NETWORK_CONNECTION -> {
                    _networkConnection.value = networkConnectionPref(sharedPref).name
                }
                KEY_SOCKS5_DELEGATE -> {
                    _socks5Delegate.value = socks5DelegatePref(sharedPref)
                }
                KEY_SOCKS5_LOOPBACK_OK -> {
                    _socks5LoopbackOk.value = socks5LoopbackOkPref(sharedPref)
                }
            }
        }

    init {
        sharedPref.registerOnSharedPreferenceChangeListener(sharedPrefListener)
    }

    val maxMemoryMb: Int = calculateMaxMemoryMb(application)

    private val _showRebootDialog = MutableStateFlow(false)
    val showRebootDialog: StateFlow<Boolean> = _showRebootDialog.asStateFlow()

    private val _showKeepAwakeDialog = MutableStateFlow(false)
    val showKeepAwakeDialog: StateFlow<Boolean> = _showKeepAwakeDialog.asStateFlow()

    fun setMemoryMb(mb: Int) {
        if (mb != _currentMemoryMb.value) {
            sharedPref.edit().putInt(KEY_MEMORY_MIB, mb).apply()
            _currentMemoryMb.value = mb
            _showRebootDialog.value = true
        }
    }

    fun setDisplayResolution(resolution: DisplayResolution) {
        if (resolution.name != _displayResolution.value) {
            sharedPref.edit().putString(KEY_DISPLAY_RESOLUTION, resolution.name).apply()
            _displayResolution.value = resolution.name
        }
    }

    fun setKeepAwakeMinutes(minutes: Int) {
        if (minutes != _keepAwakeMinutes.value) {
            sharedPref.edit().putInt(KEY_KEEP_AWAKE, minutes).apply()
            _keepAwakeMinutes.value = minutes
        }
    }

    fun setNetworkConnection(connection: NetworkConnection) {
        if (connection.name != _networkConnection.value) {
            sharedPref.edit().putString(KEY_NETWORK_CONNECTION, connection.name).apply()
            _networkConnection.value = connection.name
        }
    }

    fun setSocks5Delegate(port: Int) {
        if (port != _socks5Delegate.value && port > 0 && port <= 65535) {
            sharedPref.edit().putInt(KEY_SOCKS5_DELEGATE, port).apply()
            _socks5Delegate.value = port
        }
    }

    fun setSocks5LoopbackOk(ok: Boolean) {
        if (ok != _socks5LoopbackOk.value) {
            sharedPref.edit().putBoolean(KEY_SOCKS5_LOOPBACK_OK, ok).apply()
            _socks5LoopbackOk.value = ok
        }
    }

    fun setShowKeepAwakeDialog(show: Boolean) {
        _showKeepAwakeDialog.value = show
    }

    fun dismissRebootDialog() {
        _showRebootDialog.value = false
    }

    override fun onCleared() {
        super.onCleared()
        sharedPref.unregisterOnSharedPreferenceChangeListener(sharedPrefListener)
    }

    private fun calculateMaxMemoryMb(context: Context): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        // Set maximum to 70% of total system RAM
        return (memoryInfo.totalMem / (1024 * 1024) * 0.7).toInt()
    }

    // Recovery related operations moved from RecoveryPage
    suspend fun resetTerminal(backup: Boolean) {
        VmController.stop()
        Installer.uninstall(backup)
    }

    suspend fun deleteBackup(): Boolean {
        return Installer.deleteBackup()
    }

    fun hasBackup(): Boolean {
        return Installer.hasBackup()
    }

    companion object {
        internal const val PREFS_NAME = "terminal_settings"
        internal const val KEY_MEMORY_MIB = "memory_mib"
        internal const val KEY_DISPLAY_RESOLUTION = "display_resolution"
        internal const val KEY_KEEP_AWAKE = "keep_awake"
        const val DEFAULT_MEMORY_MIB = 1024
        const val MIN_MEMORY_MIB = 200
        internal const val KEY_NETWORK_CONNECTION = "network_connection"
        internal const val KEY_SOCKS5_DELEGATE = "socks5_delegate"
        internal const val KEY_SOCKS5_LOOPBACK_OK = "socks5_loopback_ok"
        const val DEFAULT_SOCKS5_DELEGATED = 9050 // Orbot
        fun networkConnectionPref(sharedPref: SharedPreferences): NetworkConnection = NetworkConnection.valueOf(sharedPref.getString(KEY_NETWORK_CONNECTION, NetworkConnection.FULL.name)!!)
        fun socks5DelegatePref(sharedPref: SharedPreferences) = sharedPref.getInt(KEY_SOCKS5_DELEGATE, DEFAULT_SOCKS5_DELEGATED)
        fun socks5LoopbackOkPref(sharedPref: SharedPreferences) = sharedPref.getBoolean(KEY_SOCKS5_LOOPBACK_OK, false)
    }
}
