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
package com.android.virtualization.terminal.new2.ui.main

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.virtualization.terminal.new2.core.Installer
import com.android.virtualization.terminal.new2.core.VmController
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
    }
}
