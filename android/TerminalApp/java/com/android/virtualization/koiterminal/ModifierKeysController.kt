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

import android.content.res.Configuration
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets

class ModifierKeysController(val activity: MainActivity, val parent: ViewGroup) {
    private val window = activity.window
    private val keysSingleLine: View
    private val keysDoubleLine: View
    private var activeTerminalView: TerminalView? = null
    private var keysInSingleLine: Boolean = false

    init {
        // Prepare the two modifier keys layout, but only attach the double line one since the
        // keysInSingleLine is set to true by default
        val layout = LayoutInflater.from(activity)
        keysSingleLine = layout.inflate(R.layout.modifier_keys_singleline, parent, false)
        keysDoubleLine = layout.inflate(R.layout.modifier_keys_doubleline, parent, false)

        addClickListeners(keysSingleLine)
        addClickListeners(keysDoubleLine)

        keysSingleLine.visibility = View.GONE
        keysDoubleLine.visibility = View.GONE
        parent.addView(keysDoubleLine)

        // Setup for the update to be called when needed
        window.decorView.rootView.setOnApplyWindowInsetsListener { _: View?, insets: WindowInsets ->
            update()
            insets
        }
    }

    fun addTerminalView(terminalView: TerminalView) {
        terminalView.setOnFocusChangeListener { _: View, onFocus: Boolean ->
            if (onFocus) {
                activeTerminalView = terminalView
            } else {
                activeTerminalView = null
                terminalView.disableCtrlKey()
            }
            update()
        }
    }

    private fun addClickListeners(keys: View) {
        // Only ctrl key is special, it communicates with xtermjs to modify key event with ctrl key
        keys
            .findViewById<View>(R.id.btn_ctrl)
            .setOnClickListener({
                activeTerminalView!!.mapCtrlKey()
                activeTerminalView!!.enableCtrlKey()
            })

        val listener =
            View.OnClickListener { v: View ->
                BTN_KEY_CODE_MAP[v.id]?.also { keyCode ->
                    activeTerminalView!!.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                    activeTerminalView!!.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                }
            }

        for (btn in BTN_KEY_CODE_MAP.keys) {
            keys.findViewById<View>(btn).setOnClickListener(listener)
        }
    }

    fun update() {
        // Pass if no TerminalView focused.
        if (activeTerminalView == null) {
            val keys = if (keysInSingleLine) keysSingleLine else keysDoubleLine
            keys.visibility = View.GONE
        } else {
            // select single line or double line
            val needSingleLine = needsKeysInSingleLine()
            if (keysInSingleLine != needSingleLine) {
                if (needSingleLine) {
                    parent.removeView(keysDoubleLine)
                    parent.addView(keysSingleLine)
                } else {
                    parent.removeView(keysSingleLine)
                    parent.addView(keysDoubleLine)
                }
                keysInSingleLine = needSingleLine
            }
            // set visibility
            val needShow = needToShowKeys()
            val keys = if (keysInSingleLine) keysSingleLine else keysDoubleLine
            keys.visibility = if (needShow) View.VISIBLE else View.GONE
        }
    }

    // Modifier keys are required only when IME is shown and the HW qwerty keyboard is not present
    private fun needToShowKeys(): Boolean =
        activity.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime()) &&
            activeTerminalView!!.hasFocus() &&
            !(activity.resources.configuration.keyboard == Configuration.KEYBOARD_QWERTY)

    // If terminal's height including height of modifier keys is less than 40% of the screen
    // height, we need to show modifier keys in a single line to save the vertical space
    private fun needsKeysInSingleLine(): Boolean {
        val keys = if (keysInSingleLine) keysSingleLine else keysDoubleLine
        return activeTerminalView!!.height + keys.height < 0.4f * activity.window.decorView.height
    }

    companion object {
        private val BTN_KEY_CODE_MAP =
            mapOf(
                R.id.btn_tab to KeyEvent.KEYCODE_TAB, // Alt key sends ESC keycode
                R.id.btn_alt to KeyEvent.KEYCODE_ESCAPE,
                R.id.btn_esc to KeyEvent.KEYCODE_ESCAPE,
                R.id.btn_left to KeyEvent.KEYCODE_DPAD_LEFT,
                R.id.btn_right to KeyEvent.KEYCODE_DPAD_RIGHT,
                R.id.btn_up to KeyEvent.KEYCODE_DPAD_UP,
                R.id.btn_down to KeyEvent.KEYCODE_DPAD_DOWN,
                R.id.btn_home to KeyEvent.KEYCODE_MOVE_HOME,
                R.id.btn_end to KeyEvent.KEYCODE_MOVE_END,
                R.id.btn_pgup to KeyEvent.KEYCODE_PAGE_UP,
                R.id.btn_pgdn to KeyEvent.KEYCODE_PAGE_DOWN,
            )
    }
}
