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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsPortForwardingActiveAdapter(
    private val portsStateManager: PortsStateManager,
    private val context: Context,
) : SettingsPortForwardingBaseAdapter<SettingsPortForwardingActiveAdapter.ViewHolder>() {

    override fun getItems(): ArrayList<SettingsPortForwardingItem> {
        val enabledPorts = portsStateManager.getEnabledPorts()
        return portsStateManager
            .getActivePorts()
            .map { SettingsPortForwardingItem(it, enabledPorts.contains(it)) }
            .toCollection(ArrayList())
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val enabledSwitch: MaterialSwitch =
            view.findViewById(R.id.settings_port_forwarding_active_item_enabled_switch)
        val port: TextView = view.findViewById(R.id.settings_port_forwarding_active_item_port)
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
        val view =
            LayoutInflater.from(viewGroup.context)
                .inflate(R.layout.settings_port_forwarding_active_item, viewGroup, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val port = items[position].port
        viewHolder.port.text =
            context.getString(
                R.string.settings_port_forwarding_active_ports_content,
                port,
                portsStateManager.getActivePortInfo(port)?.comm,
            )
        viewHolder.enabledSwitch.contentDescription = viewHolder.port.text
        viewHolder.enabledSwitch.setOnCheckedChangeListener(null)
        viewHolder.enabledSwitch.isChecked = items[position].enabled
        viewHolder.enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            portsStateManager.updateEnabledPort(port, isChecked)
        }
    }
}
