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
package com.android.virtualization.koiterminal.new2.ui.main

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.virtualization.koiterminal.BetterBugLauncher
import com.android.virtualization.koiterminal.ImageArchive
import com.android.virtualization.koiterminal.R
import com.android.virtualization.koiterminal.new2.core.InstallState
import com.android.virtualization.koiterminal.new2.core.Installer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the installation screen.
 *
 * It manages the installation state and provides actions to control the installation process. It
 * also handles installation-related errors and provides events for the UI to show snackbars.
 */
class InstallViewModel : ViewModel() {
    /** Current state of the installation process. */
    val installState: StateFlow<InstallState> = Installer.installState

    /** Whether the current installation process is an upgrade. */
    val isUpgrade: StateFlow<Boolean> =
        installState
            .map { it is InstallState.NeedsUpgrade }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Whether to only download/install when connected to Wi-Fi. */
    val wifiOnly: StateFlow<Boolean> = Installer.wifiOnly

    /** Whether the installation should proceed automatically (e.g., from a local image). */
    val autoInstall: Boolean
        get() = ImageArchive.isLocalImage() && 
        installState.value.let { !(it is InstallState.NeedsUpgrade || it is InstallState.Checking) }

    init {
        autoInstallIfNecessary()
        collectErrorEvents()
    }

    /**
     * Automatically triggers installation if a local image is available and the VM is not yet
     * installed.
     */
    private fun autoInstallIfNecessary() {
        if (autoInstall) {
            viewModelScope.launch {
                installState.collect { state ->
                    when (state) {
                        is InstallState.NotInstalled -> installVm()
                        is InstallState.NeedsUpgrade -> upgradeVm()
                        else -> {}
                    }
                }
            }
        }
    }

    /** Starts the installation process. */
    fun installVm() {
        Installer.install()
    }

    /** Starts the upgrade process. */
    fun upgradeVm() {
        Installer.upgrade()
    }

    /** Updates the preference for Wi-Fi only installation. */
    fun setWifiOnly(enabled: Boolean) {
        Installer.setWifiOnly(enabled)
    }

    /** Cancels the ongoing installation. */
    fun cancelInstallVm() {
        Installer.cancelInstall()
    }

    /** Retries checking the installation status or resumes a failed installation. */
    fun retryCheck() {
        Installer.retryCheck()
    }

    // --- Error Handling ---

    /**
     * Internal flow for UI events like showing snackbars. SharedFlow is used to ensure events are
     * delivered to the UI even if it's currently paused.
     */
    private val _uiEvents = MutableSharedFlow<InstallUiEvent>()
    val uiEvents: SharedFlow<InstallUiEvent> = _uiEvents.asSharedFlow()

    /**
     * Executes the action associated with a snackbar event. This method bridges the gap between the
     * ViewModel logic and Android-specific APIs.
     */
    fun performAction(activity: Activity, event: InstallUiEvent.ShowSnackbar) {
        when (event.actionType) {
            ActionType.OpenWifiSettings -> {
                activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            ActionType.OpenStorageSettings -> {
                activity.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            }
            ActionType.Retry -> {
                retryCheck()
            }
            ActionType.ShowBug -> {
                Installer.setShowIfError(true)
            }
        }
    }

    /** Monitors the installation state and emits UI events when an error occurs. */
    private fun collectErrorEvents() {
        viewModelScope.launch {
            installState.collect { state ->
                if (state is InstallState.Error) {
                    _uiEvents.emit(mapErrorToEvent(state))
                }
            }
        }
    }

    /**
     * Maps an [InstallState.Error] to a [InstallUiEvent.ShowSnackbar] with appropriate strings and
     * action types.
     */
    private fun mapErrorToEvent(state: InstallState.Error): InstallUiEvent.ShowSnackbar {
        return when (state.errorCause) {
            InstallState.ErrorCause.CheckFailed ->
                InstallUiEvent.ShowSnackbar(
                    R.string.installer_snkbar_error_no_wifi,
                    R.string.action_settings,
                    ActionType.OpenWifiSettings,
                )
            InstallState.ErrorCause.InstallFailedNoSpace ->
                InstallUiEvent.ShowSnackbar(
                    R.string.installer_snkbar_error_no_space,
                    R.string.installer_snkbar_action_storage,
                    ActionType.OpenStorageSettings,
                )
            InstallState.ErrorCause.InstallFailedUnknown ->
                InstallUiEvent.ShowSnackbar(
                    R.string.installer_snkbar_error_unknown,
                    R.string.notif_btn_retry,
                    ActionType.Retry,
                )
            InstallState.ErrorCause.UninstallFailed,
            InstallState.ErrorCause.DeleteBackupFailed ->
                InstallUiEvent.ShowSnackbar(
                    R.string.error_title,
                    R.string.error_btn_show_error,
                    ActionType.ShowBug,
                    state.cause,
                )
        }
    }

    /** Represents events that the UI should respond to. */
    sealed interface InstallUiEvent {
        /** Instructs the UI to show a snackbar with specific messages and actions. */
        data class ShowSnackbar(
            val messageId: Int,
            val actionLabelId: Int,
            val actionType: ActionType,
            val cause: Throwable? = null,
        ) : InstallUiEvent
    }

    /** Types of actions that can be triggered from a snackbar. */
    enum class ActionType {
        OpenWifiSettings,
        OpenStorageSettings,
        Retry,
        ShowBug,
    }
}
