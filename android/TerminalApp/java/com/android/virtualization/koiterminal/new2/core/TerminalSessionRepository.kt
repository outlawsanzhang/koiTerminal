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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for terminal sessions. Manages the list of active sessions and the
 * currently selected session.
 */
object TerminalSessionRepository {
    private val _sessions = MutableStateFlow<List<TerminalSession>>(listOf(TerminalSession()))
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()

    private val _selectedSessionId = MutableStateFlow(_sessions.value.first().id)
    val selectedSessionId: StateFlow<String> = _selectedSessionId.asStateFlow()

    /** Adds a new terminal session and selects it. */
    fun addSession() {
        val newSession = TerminalSession()
        val currentList = _sessions.value.toMutableList()
        currentList.add(newSession)
        _sessions.value = currentList
        _selectedSessionId.value = newSession.id
    }

    /** Removes a session by ID. If it was selected, selects another one. */
    fun removeSession(id: String) {
        val currentList = _sessions.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == id }
        if (index == -1) return

        currentList.removeAt(index)
        _sessions.value = currentList

        if (currentList.isEmpty()) {
            return
        }

        if (_selectedSessionId.value == id) {
            val newIndex = if (index > 0) index - 1 else 0
            _selectedSessionId.value = currentList[newIndex].id
        }
    }

    /** Selects a session by ID. */
    fun selectSession(id: String) {
        if (_sessions.value.any { it.id == id }) {
            _selectedSessionId.value = id
        }
    }

    /** Resets the sessions to a single new session. */
    fun reset() {
        val newSession = TerminalSession()
        _sessions.value = listOf(newSession)
        _selectedSessionId.value = newSession.id
    }
}
