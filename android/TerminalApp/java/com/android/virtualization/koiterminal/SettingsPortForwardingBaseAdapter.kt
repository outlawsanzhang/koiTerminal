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

import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SortedList
import androidx.recyclerview.widget.SortedListAdapterCallback

abstract class SettingsPortForwardingBaseAdapter<T : RecyclerView.ViewHolder>() :
    RecyclerView.Adapter<T>() {
    var items: SortedList<SettingsPortForwardingItem>

    init {
        items =
            SortedList(
                SettingsPortForwardingItem::class.java,
                object : SortedListAdapterCallback<SettingsPortForwardingItem>(this) {
                    override fun compare(
                        o1: SettingsPortForwardingItem,
                        o2: SettingsPortForwardingItem,
                    ): Int {
                        return o1.port - o2.port
                    }

                    override fun areContentsTheSame(
                        o1: SettingsPortForwardingItem,
                        o2: SettingsPortForwardingItem,
                    ): Boolean {
                        return o1.port == o2.port && o1.enabled == o2.enabled
                    }

                    override fun areItemsTheSame(
                        o1: SettingsPortForwardingItem,
                        o2: SettingsPortForwardingItem,
                    ): Boolean {
                        return o1.port == o2.port
                    }
                },
            )
    }

    override fun getItemCount() = items.size()

    abstract fun getItems(): ArrayList<SettingsPortForwardingItem>

    fun refreshItems() {
        items.replaceAll(getItems())
    }
}
