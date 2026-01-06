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
package com.android.virtualization.terminal

import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import java.util.UUID

class TabMetadata(val id: String)

class TerminalTabAdapter(fragmentActivity: FragmentActivity) :
    FragmentStateAdapter(fragmentActivity) {
    val tabs = ArrayList<TabMetadata>()

    override fun createFragment(position: Int): Fragment {
        val terminalTabFragment = TerminalTabFragment()

        terminalTabFragment.arguments = bundleOf("id" to tabs[position].id)
        return terminalTabFragment
    }

    override fun getItemCount(): Int {
        return tabs.size
    }

    override fun getItemId(position: Int): Long {
        return tabs[position].id.hashCode().toLong()
    }

    override fun containsItem(itemId: Long): Boolean {
        return tabs.any { it.id.hashCode().toLong() == itemId }
    }

    fun addTab(): String {
        val id = UUID.randomUUID().toString()
        tabs.add(TabMetadata(id))
        return id
    }

    fun deleteTab(position: Int) {
        if (position in 0 until tabs.size) {
            tabs.removeAt(position)
            notifyItemRemoved(position)
        }
    }
}
