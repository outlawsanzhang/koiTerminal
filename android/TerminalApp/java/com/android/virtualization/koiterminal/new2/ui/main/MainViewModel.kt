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
package com.android.virtualization.terminal.new2.ui.main

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.view.InputDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.virtualization.terminal.new2.core.InstallState
import com.android.virtualization.terminal.new2.core.Installer
import com.android.virtualization.terminal.new2.core.TerminalAddress
import com.android.virtualization.terminal.new2.core.TerminalSession
import com.android.virtualization.terminal.new2.core.TerminalSessionRepository
import com.android.virtualization.terminal.new2.core.VmController
import com.android.virtualization.terminal.new2.core.VmState
import com.android.virtualization.terminal.new2.ui.MainActivity
import com.android.virtualization.terminal.new2.ui.PERMISSIONS
import com.android.virtualization.terminal.new2.ui.SettingsDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface MainUiState {
    data object Ready : MainUiState

    data object Stopped : MainUiState

    data object Booting : MainUiState

    data class Running(val terminalAddress: TerminalAddress) : MainUiState

    data object Stopping : MainUiState

    sealed interface ErrorHandler {
        data class ShowBug(val error: Throwable) : ErrorHandler
    }

    data class Error(val handler: ErrorHandler) : MainUiState
}

sealed interface DisplayState {
    data object Hidden : DisplayState

    data object Normal : DisplayState

    data object Minimized : DisplayState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    var hasVmEverStarted = false

    val tabs: StateFlow<List<TerminalSession>> = TerminalSessionRepository.sessions
    val selectedTabId: StateFlow<String> = TerminalSessionRepository.selectedSessionId

    private val _displayState = MutableStateFlow<DisplayState>(DisplayState.Hidden)
    val displayState: StateFlow<DisplayState> = _displayState.asStateFlow()

    private val _isFullscreen = MutableStateFlow(false)
    val isFullscreen: StateFlow<Boolean> = _isFullscreen.asStateFlow()

    private val _isImeVisible = MutableStateFlow(false)
    val isImeVisible: StateFlow<Boolean> = _isImeVisible.asStateFlow()

    private val _isPanZoomMode = MutableStateFlow(false)
    val isPanZoomMode: StateFlow<Boolean> = _isPanZoomMode.asStateFlow()

    private val _hasMouse = MutableStateFlow(false)
    val hasMouse: StateFlow<Boolean> = _hasMouse.asStateFlow()

    private val _hasPhysicalKeyboard = MutableStateFlow(false)
    val hasPhysicalKeyboard: StateFlow<Boolean> = _hasPhysicalKeyboard.asStateFlow()

    private val _isMouseLocked = MutableStateFlow(false)
    val isMouseLocked: StateFlow<Boolean> =
        combine(_isMouseLocked, _hasMouse) { locked, hasMouse -> locked && hasMouse }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _useDisplayAsTouchpad = MutableStateFlow(false)
    val useDisplayAsTouchpad: StateFlow<Boolean> = _useDisplayAsTouchpad.asStateFlow()

    private val _settingsRequest = MutableStateFlow<SettingsDestination?>(null)
    val settingsRequest: StateFlow<SettingsDestination?> = _settingsRequest.asStateFlow()

    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

    private val _showIfError = MutableStateFlow(false)
    val showIfError: StateFlow<Boolean> = _showIfError.asStateFlow()

    private val _hasMandatoryPermissions = MutableStateFlow(false)
    val hasMandatoryPermissions: StateFlow<Boolean> = _hasMandatoryPermissions.asStateFlow()

    private val _permissionRequired = MutableStateFlow(false)
    val permissionRequired: StateFlow<Boolean> = _permissionRequired.asStateFlow()

    fun refreshPermissionState() {
        val context = getApplication<Application>()
        // TODO(b/492409159): Remove this check if local network is no longer mandatory
        _hasMandatoryPermissions.value =
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun handleIntent(intent: Intent) {
        if (intent.action == MainActivity.ACTION_OPEN_SETTINGS_PORT) {
            _settingsRequest.value = SettingsDestination.PortControl
            _showSettings.value = true
        } else if (intent.action == MainActivity.ACTION_OPEN_SETTINGS_KEEP_AWAKE) {
            _settingsRequest.value = SettingsDestination.Advanced
            _showSettings.value = true
        }
    }

    fun setShowSettings(show: Boolean) {
        _showSettings.value = show
        if (!show) {
            clearSettingsRequest()
        }
    }

    fun clearSettingsRequest() {
        _settingsRequest.value = null
    }

    fun setShowIfError(enabled: Boolean) {
        _showIfError.value = enabled
    }

    fun toggleDisplay() {
        _displayState.value =
            if (_displayState.value == DisplayState.Hidden) {
                DisplayState.Normal
            } else {
                DisplayState.Hidden
            }
    }

    fun setIsFullscreen(enabled: Boolean) {
        _isFullscreen.value = enabled
    }

    fun setIsImeVisible(visible: Boolean) {
        _isImeVisible.value = visible
    }

    fun setPanZoomMode(enabled: Boolean) {
        _isPanZoomMode.value = enabled
    }

    fun setMouseLocked(locked: Boolean) {
        _isMouseLocked.value = locked
    }

    fun setUseDisplayAsTouchpad(enabled: Boolean) {
        _useDisplayAsTouchpad.value = enabled
    }

    fun addTab() {
        TerminalSessionRepository.addSession()
    }

    fun closeTab(id: String) {
        TerminalSessionRepository.removeSession(id)
    }

    fun selectTab(id: String) {
        TerminalSessionRepository.selectSession(id)
        _displayState.value = DisplayState.Hidden
    }

    val uiState: StateFlow<MainUiState> =
        VmController.vmState
            .map { vmState ->
                when (vmState) {
                    is VmState.Ready -> {
                        if (hasVmEverStarted) {
                            MainUiState.Stopped
                        } else {
                            MainUiState.Ready
                        }
                    }
                    is VmState.Starting -> {
                        hasVmEverStarted = true
                        MainUiState.Booting
                    }
                    is VmState.Rebooting -> MainUiState.Booting
                    is VmState.Running -> MainUiState.Running(vmState.terminalAddress)
                    is VmState.Stopping -> MainUiState.Stopping
                    is VmState.Stopped -> {
                        if (hasVmEverStarted) {
                            MainUiState.Stopped
                        } else {
                            MainUiState.Ready
                        }
                    }
                    is VmState.Error -> {
                        setIsImeVisible(false)
                        MainUiState.Error(MainUiState.ErrorHandler.ShowBug(vmState.cause))
                    }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = MainUiState.Ready,
            )

    fun startVm() {
        if (!hasMandatoryPermissions.value) {
            return
        }
        viewModelScope.launch { VmController.start() }
    }

    fun onPermissionGranted() {
        refreshPermissionState()
        _permissionRequired.value = false
    }

    fun onPermissionDenied() {
        refreshPermissionState()
        _permissionRequired.value = false
    }

    fun stopVm() {
        VmController.stop()
    }

    fun restartVm() {
        hasVmEverStarted = false
        TerminalSessionRepository.reset()
        if (VmController.vmState.value is VmState.Rebooting) {
            VmController.reset()
        } else {
            stopVm()
        }
    }

    override fun onCleared() {
        super.onCleared()
        VmController.stop()
    }

    init {
        VmController.reset()

        val context = getApplication<Application>()
        _permissionRequired.value =
            PERMISSIONS.any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        refreshPermissionState()

        val inputManager = context.getSystemService(InputManager::class.java)
        val mouseDeviceIds = mutableSetOf<Int>()
        val keyboardDeviceIds = mutableSetOf<Int>()
        val updateDeviceStatus = {
            val currentIds = inputManager.inputDeviceIds.toSet()
            // Remove devices that are no longer connected
            mouseDeviceIds.retainAll(currentIds)
            keyboardDeviceIds.retainAll(currentIds)

            // Add devices that are currently identified as a mouse or keyboard
            for (id in currentIds) {
                val device = inputManager.getInputDevice(id) ?: continue
                if (device.isVirtual) continue

                if (device.sources and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE) {
                    mouseDeviceIds.add(id)
                }
                if (device.isFullKeyboard) {
                    keyboardDeviceIds.add(id)
                }
            }
            _hasMouse.value = mouseDeviceIds.isNotEmpty()
            _hasPhysicalKeyboard.value = keyboardDeviceIds.isNotEmpty()
        }
        updateDeviceStatus()
        inputManager.registerInputDeviceListener(
            object : InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(deviceId: Int) {
                    updateDeviceStatus()
                }

                override fun onInputDeviceRemoved(deviceId: Int) {
                    updateDeviceStatus()
                }

                override fun onInputDeviceChanged(deviceId: Int) {
                    updateDeviceStatus()
                }
            },
            null,
        )

        // Observe installation and UI states to manage VM lifecycle.
        viewModelScope.launch {
            hasMandatoryPermissions.collectLatest { hasMandatory ->
                if (hasMandatory) {
                    launch {
                        Installer.installState.collectLatest { installState ->
                            if (installState is InstallState.Installed) {
                                // Reset sessions to start fresh upon installation completion.
                                TerminalSessionRepository.reset()

                                // Once installed, start observing UI state and trigger VM startup
                                // whenever it returns to a Ready state.
                                uiState.collect { state ->
                                    if (state is MainUiState.Ready) {
                                        startVm()
                                    }
                                }
                            } else {
                                // If installation is removed or checking, reset flags and UI.
                                // FIXME: when VM is deleted in the background, this gets stuck
                                hasVmEverStarted = false
                                _showSettings.value = false
                            }
                        }
                    }

                    launch { VmController.sessionDiscarded.collect { id -> closeTab(id) } }
                    launch {
                        VmController.vmState.collect { state ->
                            if (state is VmState.Rebooting) {
                                restartVm()
                            }
                        }
                    }
                    launch {
                        TerminalSessionRepository.sessions.collect { sessions ->
                            if (sessions.isEmpty()) {
                                stopVm()
                            }
                        }
                    }
                }
            }
        }
    }
}
