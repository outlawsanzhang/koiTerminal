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
package com.android.virtualization.koiterminal.new2.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/**
 * Single source of truth for terminal sessions. Manages the list of active sessions and the
 * currently selected session.
 */
object TerminalSessionRepository {
    private val _sessions = MutableStateFlow<List<TerminalSession>>(listOf(TerminalSession()))
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()

    private val _selectedSessionId = MutableStateFlow(_sessions.value.first().id)
    val selectedSessionId: StateFlow<String> = _selectedSessionId.asStateFlow()

    private val _selectedSessionIndex = MutableStateFlow(0)
    val selectedSessionIndex: StateFlow<Int> = _selectedSessionIndex.asStateFlow()
    val selectedSession = _sessions.combine(_selectedSessionIndex) { sessions, index ->
        sessions.getOrNull(index)
    }

    /** Adds a new terminal session and selects it. Returns false if skipped. */
    fun addSession(type: TerminalSessionType = TerminalSessionType.TTYD): Boolean {
        if (type == TerminalSessionType.SERIAL && _sessions.value.any { it.type == TerminalSessionType.SERIAL }) {
            // only one SERIAL session at a time
            return false
        }
        val newSession = TerminalSession(type = type)
        val currentList = _sessions.value.toMutableList()
        currentList.add(newSession)
        _sessions.value = currentList
        _selectedSessionId.value = newSession.id
        _selectedSessionIndex.value = currentList.size - 1
        return true
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

        if (_selectedSessionIndex.value == index) {
            val newIndex = if (index > 0) index - 1 else 0
            _selectedSessionId.value = currentList[newIndex].id
            _selectedSessionIndex.value = newIndex
        } else if (_selectedSessionIndex.value > index) {
            _selectedSessionIndex.value -= 1
        }
    }

    /** Selects a session by ID. */
    fun selectSession(id: String) {
        val index =_sessions.value.indexOfFirst { it.id == id }
        if (index != -1) {
            _selectedSessionId.value = id
            _selectedSessionIndex.value = index
        }
    }

    /** Resets the sessions to a single new session. */
    fun reset() {
        val newSession = TerminalSession()
        _sessions.value = listOf(newSession)
        _selectedSessionId.value = newSession.id
        _selectedSessionIndex.value = 0
    }
}
