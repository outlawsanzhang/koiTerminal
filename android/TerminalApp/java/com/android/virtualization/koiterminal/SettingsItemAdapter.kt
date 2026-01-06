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

import android.app.AlertDialog
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class SettingsItemAdapter(private val dataSet: List<SettingsItem>) :
    RecyclerView.Adapter<SettingsItemAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.settings_list_item_card)
        val icon: ImageView = view.findViewById(R.id.settings_list_item_icon)
        val title: TextView = view.findViewById(R.id.settings_list_item_title)
        val subTitle: TextView = view.findViewById(R.id.settings_list_item_sub_title)
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
        val view =
            LayoutInflater.from(viewGroup.context)
                .inflate(R.layout.settings_list_item, viewGroup, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        viewHolder.icon.setImageResource(dataSet[position].icon)
        viewHolder.title.text = dataSet[position].title
        viewHolder.subTitle.text = dataSet[position].subTitle

        viewHolder.card.setOnClickListener { view ->
            val type = dataSet[position].settingsItemEnum
            if (type == SettingsItemEnum.GraphicAcceleration) {
                val graphicsManager = GraphicsManager.getInstance(view.context)
                val currentType = graphicsManager.accelerationType
                val availableOptions = graphicsManager.availableAccelerationTypes
                var originalSelection = availableOptions.indexOf(currentType)

                if (originalSelection == -1) {
                    originalSelection = 0
                }

                var newSelection = originalSelection

                AlertDialog.Builder(view.context)
                    .setTitle(R.string.settings_graphics_acceleration_title)
                    .setPositiveButton(android.R.string.ok) { dialog, which ->
                        if (newSelection != -1) {
                            val selectedType = availableOptions[newSelection]
                            graphicsManager.accelerationType = selectedType
                        }

                        if (originalSelection != newSelection) {
                            Toast.makeText(
                                    view.context,
                                    R.string.settings_graphics_acceleration_toast_reboot_required,
                                    Toast.LENGTH_SHORT,
                                )
                                .show()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel) { dialog, which ->
                        dialog.dismiss()
                    }
                    .setSingleChoiceItems(
                        availableOptions
                            .map { view.context.getString(it.descriptionId) }
                            .toTypedArray(),
                        originalSelection,
                    ) { dialog, which ->
                        newSelection = which
                    }
                    .create()
                    .show()
                return@setOnClickListener
            }
            val intent =
                Intent(
                    viewHolder.itemView.context,
                    when (type) {
                        SettingsItemEnum.DiskResize -> SettingsDiskResizeActivity::class.java
                        SettingsItemEnum.PortForwarding ->
                            SettingsPortForwardingActivity::class.java
                        SettingsItemEnum.Recovery -> SettingsRecoveryActivity::class.java
                        SettingsItemEnum.GraphicAcceleration ->
                            throw IllegalStateException("should be handled above")
                    },
                )
            view.context.startActivity(intent)
        }
    }

    override fun getItemCount() = dataSet.size
}
