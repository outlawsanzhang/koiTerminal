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
import android.content.SharedPreferences
import com.android.internal.annotations.GuardedBy
import com.android.virtualization.terminal.proto.ActivePort
import java.util.HashSet

/**
 * PortsStateManager is responsible for communicating with shared preferences and managing state of
 * ports.
 */
class PortsStateManager private constructor(private val sharedPref: SharedPreferences) {
    private val lock = Any()

    @GuardedBy("lock") private val activePorts: MutableMap<Int, ActivePort> = hashMapOf()

    @GuardedBy("lock")
    private val enabledPorts: MutableSet<Int> =
        sharedPref
            .getAll()
            .entries
            .filterIsInstance<MutableMap.MutableEntry<String, Int>>()
            .filter { it.value and FLAG_ENABLED == FLAG_ENABLED }
            .map { it.key.toIntOrNull() }
            .filterNotNull()
            .toMutableSet()

    @GuardedBy("lock") private val listeners: MutableSet<Listener> = hashSetOf()

    fun getActivePorts(): Set<Int> {
        synchronized(lock) {
            return HashSet<Int>(activePorts.keys)
        }
    }

    fun getActivePortInfo(port: Int): ActivePort? {
        synchronized(lock) {
            return activePorts[port]
        }
    }

    fun getEnabledPorts(): Set<Int> {
        synchronized(lock) {
            return HashSet<Int>(enabledPorts)
        }
    }

    fun updateActivePorts(ports: List<ActivePort>) {
        val oldPorts = getActivePorts()
        synchronized(lock) {
            activePorts.clear()
            activePorts.putAll(ports.associateBy { it.port })
        }
        notifyPortsStateUpdated(oldPorts, getActivePorts())
    }

    fun updateEnabledPort(port: Int, enabled: Boolean) {
        synchronized(lock) {
            val editor = sharedPref.edit()
            editor.putInt(port.toString(), if (enabled) FLAG_ENABLED else 0)
            editor.apply()
            if (enabled) {
                enabledPorts.add(port)
            } else {
                enabledPorts.remove(port)
            }
        }
        notifyPortsStateUpdated(getActivePorts(), getActivePorts())
    }

    fun clearEnabledPorts() {
        synchronized(lock) {
            val editor = sharedPref.edit()
            editor.clear()
            editor.apply()
            enabledPorts.clear()
        }
        notifyPortsStateUpdated(getActivePorts(), getActivePorts())
    }

    fun registerListener(listener: Listener) {
        synchronized(lock) { listeners.add(listener) }
    }

    fun unregisterListener(listener: Listener) {
        synchronized(lock) { listeners.remove(listener) }
    }

    // TODO: it notifies when both enabledPort and activePort are changed, but doesn't provide
    // enabledPort's value change. Make this callback provide that information as well.
    private fun notifyPortsStateUpdated(oldActivePorts: Set<Int>, newActivePorts: Set<Int>) {
        synchronized(lock) { HashSet<Listener>(this@PortsStateManager.listeners) }
            .forEach {
                it.onPortsStateUpdated(HashSet<Int>(oldActivePorts), HashSet<Int>(newActivePorts))
            }
    }

    interface Listener {
        fun onPortsStateUpdated(oldActivePorts: Set<Int>, newActivePorts: Set<Int>) {}
    }

    companion object {
        private const val PREFS_NAME = ".PORTS"
        private const val FLAG_ENABLED = 1

        private var instance: PortsStateManager? = null

        @Synchronized
        fun getInstance(context: Context): PortsStateManager {
            if (instance == null) {
                val sharedPref =
                    context.getSharedPreferences(
                        context.getPackageName() + PREFS_NAME,
                        Context.MODE_PRIVATE,
                    )
                instance = PortsStateManager(sharedPref)
            }
            return instance!!
        }
    }
}
