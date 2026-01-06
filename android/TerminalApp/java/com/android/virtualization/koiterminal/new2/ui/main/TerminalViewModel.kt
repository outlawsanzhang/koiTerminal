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

import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display.DEFAULT_DISPLAY
import android.view.WindowManager.LayoutParams.TYPE_APPLICATION
import androidx.lifecycle.AndroidViewModel
import com.android.virtualization.terminal.R
import com.android.virtualization.terminal.new2.core.TerminalAddress
import com.android.virtualization.terminal.new2.core.TtydView
import com.android.virtualization.terminal.new2.core.VmController
import com.android.virtualization.terminal.new2.util.LoggingMutableStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface TerminalUiState {
    data object Initializing : TerminalUiState

    data object Connecting : TerminalUiState

    data object Ready : TerminalUiState

    data object Disconnected : TerminalUiState
}

class TerminalViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState =
        LoggingMutableStateFlow<TerminalUiState>(
            MutableStateFlow(TerminalUiState.Initializing),
            "TerminalViewModel",
        )
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    private val _title = MutableStateFlow<String>(application.getString(R.string.app_name))
    val title: StateFlow<String> = _title.asStateFlow()

    // TODO: explain reason for this
    private val context: Context by lazy {
        val dm = application.getSystemService<DisplayManager>(DisplayManager::class.java)
        val disp = dm.getDisplay(DEFAULT_DISPLAY)
        application.createDisplayContext(disp).createWindowContext(TYPE_APPLICATION, null)
    }
    private var ttydView: TtydView? = null

    fun getOrCreateTtydView(sessionId: String, terminalAddress: TerminalAddress): TtydView {
        if (ttydView == null) {
            Log.d("TerminalViewModel", "Creating new TtydView")
            _uiState.value = TerminalUiState.Connecting
            ttydView =
                TtydView(context).apply {
                    onTerminalReady = { _uiState.value = TerminalUiState.Ready }
                    onTerminalDisconnected = { _uiState.value = TerminalUiState.Disconnected }
                    onSessionDiscard = { VmController.requestSessionDiscard(sessionId) }
                    onTitleChanged = { title -> _title.value = title }
                    load(terminalAddress)
                }
        }
        return ttydView!!
    }

    fun terminalClose() {
        ttydView?.terminalClose()
        ttydView = null
    }

    override fun onCleared() {
        super.onCleared()
        Log.d("TerminalViewModel", "Clearing TtydView")
        ttydView?.terminalClose()
        ttydView = null
    }
}
