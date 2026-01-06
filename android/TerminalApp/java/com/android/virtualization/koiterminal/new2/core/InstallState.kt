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

import kotlinx.coroutines.flow.StateFlow

sealed interface InstallState {
    data object Checking : InstallState

    data class NotInstalled(val totalSizeBytes: Long) : InstallState

    data class NeedsUpgrade(val totalSizeBytes: Long) : InstallState

    data class Installing(
        val totalBytes: Long,
        val progress: StateFlow<Long>,
        val onWifi: Boolean,
    ) : InstallState

    data class InstallSuspended(val totalBytes: Long, val progress: StateFlow<Long>) : InstallState

    data object Installed : InstallState

    enum class ErrorCause {
        CheckFailed,
        InstallFailedUnknown,
        UninstallFailed,
        DeleteBackupFailed,
        InstallFailedNoSpace,
    }

    data class Error(val cause: Throwable, val errorCause: ErrorCause) : InstallState

    fun isStarted(): Boolean {
        return this is Installing || this is InstallSuspended
    }

    fun getTotalImageSize(): Long {
        return when (this) {
            is NotInstalled -> totalSizeBytes
            is NeedsUpgrade -> totalSizeBytes
            is Installing -> totalBytes
            is InstallSuspended -> totalBytes
            else -> 0L
        }
    }
}
