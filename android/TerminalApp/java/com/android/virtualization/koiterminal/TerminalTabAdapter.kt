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
package com.android.virtualization.koiterminal

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import java.util.UUID

class TabMetadata(val id: String, val mode: Int)

class TerminalTabAdapter(fragmentActivity: FragmentActivity) :
    FragmentStateAdapter(fragmentActivity) {
    val tabs = ArrayList<TabMetadata>()
    private var terminalSerialTabFragment: TerminalSerialTabFragment? = null

    override fun createFragment(position: Int): Fragment {
        val terminalTabFragment = if (tabs[position].mode == MODE_TTYD) {
            TerminalTabFragment()
        } else {
            terminalSerialTabFragment!!
        }

        val args = Bundle()
        args.putString("id", tabs[position].id)
        terminalTabFragment.arguments = args
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

    fun hasSerial(): Boolean {
        return tabs.any { it.mode == MODE_SERIAL }
    }

    fun addTab(): String {
        val id = UUID.randomUUID().toString()
        tabs.add(TabMetadata(id, MODE_TTYD))
        return id
    }

    fun addSerialTab(serial: TerminalSerialTabFragment): String {
        val id = UUID.randomUUID().toString()
        assert(!hasSerial())
        terminalSerialTabFragment = serial
        tabs.add(TabMetadata(id, MODE_SERIAL))
        return id
    }

    fun deleteTab(id: String): Boolean {
        for (i in 0 until tabs.size) {
            if (tabs[i].id == id) {
                if (tabs[i].mode == MODE_SERIAL) {
                    terminalSerialTabFragment?.disconnectTerminal()
                    terminalSerialTabFragment = null
                }
                tabs.removeAt(i)
                notifyItemRemoved(i)
                return true
            }
        }
        return false
    }

    companion object {
        public const val MODE_TTYD = 0
        public const val MODE_SERIAL = 1
    }
}
