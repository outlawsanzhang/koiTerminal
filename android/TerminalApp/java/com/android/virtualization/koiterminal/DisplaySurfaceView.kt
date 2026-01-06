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

import android.content.Context
import android.system.virtualmachine.VirtualMachine
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo

val HARDWARE_KEYCODE_SHIFT: Short = 42

class DisplaySurfaceView(context: Context, attrs: AttributeSet) : SurfaceView(context, attrs) {
    lateinit var virtualMachine: VirtualMachine

    init {
        setFocusable(true)
        setFocusableInTouchMode(true)
    }

    fun convertAndroidKeyCodeToEvdevScanCode(androidKeyCode: Int): Short {
        return when (androidKeyCode) {
            // Alphanumeric keys
            KeyEvent.KEYCODE_A -> 0x1E // EV_KEY_A
            KeyEvent.KEYCODE_B -> 0x30 // EV_KEY_B
            KeyEvent.KEYCODE_C -> 0x2E // EV_KEY_C
            KeyEvent.KEYCODE_D -> 0x20 // EV_KEY_D
            KeyEvent.KEYCODE_E -> 0x12 // EV_KEY_E
            KeyEvent.KEYCODE_F -> 0x21 // EV_KEY_F
            KeyEvent.KEYCODE_G -> 0x22 // EV_KEY_G
            KeyEvent.KEYCODE_H -> 0x23 // EV_KEY_H
            KeyEvent.KEYCODE_I -> 0x17 // EV_KEY_I
            KeyEvent.KEYCODE_J -> 0x24 // EV_KEY_J
            KeyEvent.KEYCODE_K -> 0x25 // EV_KEY_K
            KeyEvent.KEYCODE_L -> 0x26 // EV_KEY_L
            KeyEvent.KEYCODE_M -> 0x32 // EV_KEY_M
            KeyEvent.KEYCODE_N -> 0x31 // EV_KEY_N
            KeyEvent.KEYCODE_O -> 0x18 // EV_KEY_O
            KeyEvent.KEYCODE_P -> 0x19 // EV_KEY_P
            KeyEvent.KEYCODE_Q -> 0x10 // EV_KEY_Q
            KeyEvent.KEYCODE_R -> 0x13 // EV_KEY_R
            KeyEvent.KEYCODE_S -> 0x1F // EV_KEY_S
            KeyEvent.KEYCODE_T -> 0x14 // EV_KEY_T
            KeyEvent.KEYCODE_U -> 0x16 // EV_KEY_U
            KeyEvent.KEYCODE_V -> 0x2F // EV_KEY_V
            KeyEvent.KEYCODE_W -> 0x11 // EV_KEY_W
            KeyEvent.KEYCODE_X -> 0x2D // EV_KEY_X
            KeyEvent.KEYCODE_Y -> 0x15 // EV_KEY_Y
            KeyEvent.KEYCODE_Z -> 0x2C // EV_KEY_Z

            KeyEvent.KEYCODE_0 -> 0x0B // EV_KEY_0
            KeyEvent.KEYCODE_1 -> 0x02 // EV_KEY_1
            KeyEvent.KEYCODE_2 -> 0x03 // EV_KEY_2
            KeyEvent.KEYCODE_3 -> 0x04 // EV_KEY_3
            KeyEvent.KEYCODE_4 -> 0x05 // EV_KEY_4
            KeyEvent.KEYCODE_5 -> 0x06 // EV_KEY_5
            KeyEvent.KEYCODE_6 -> 0x07 // EV_KEY_6
            KeyEvent.KEYCODE_7 -> 0x08 // EV_KEY_7
            KeyEvent.KEYCODE_8 -> 0x09 // EV_KEY_8
            KeyEvent.KEYCODE_9 -> 0x0A // EV_KEY_9

            // Common non-alphanumeric keys
            KeyEvent.KEYCODE_SPACE -> 0x39 // EV_KEY_SPACE
            KeyEvent.KEYCODE_ENTER -> 0x1C // EV_KEY_ENTER
            KeyEvent.KEYCODE_DEL -> 0x0E // EV_KEY_BACKSPACE (Android DEL is backspace)
            KeyEvent.KEYCODE_TAB -> 0x0F // EV_KEY_TAB
            KeyEvent.KEYCODE_ESCAPE -> 0x01 // EV_KEY_ESC
            KeyEvent.KEYCODE_MINUS -> 0x0C // EV_KEY_MINUS
            KeyEvent.KEYCODE_EQUALS -> 0x0D // EV_KEY_EQUAL
            KeyEvent.KEYCODE_LEFT_BRACKET -> 0x1A // EV_KEY_LEFTBRACE
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x1B // EV_KEY_RIGHTBRACE
            KeyEvent.KEYCODE_BACKSLASH -> 0x2B // EV_KEY_BACKSLASH
            KeyEvent.KEYCODE_SEMICOLON -> 0x27 // EV_KEY_SEMICOLON
            KeyEvent.KEYCODE_APOSTROPHE -> 0x28 // EV_KEY_APOSTROPHE
            KeyEvent.KEYCODE_GRAVE -> 0x29 // EV_KEY_GRAVE (backtick)
            KeyEvent.KEYCODE_COMMA -> 0x33 // EV_KEY_COMMA
            KeyEvent.KEYCODE_PERIOD -> 0x34 // EV_KEY_DOT
            KeyEvent.KEYCODE_SLASH -> 0x35 // EV_KEY_SLASH

            // Arrow keys
            KeyEvent.KEYCODE_DPAD_UP -> 0x67 // EV_KEY_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> 0x6C // EV_KEY_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> 0x69 // EV_KEY_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0x6A // EV_KEY_RIGHT

            // There is no direct mapping for these keys, so we use the SHIFT + key
            KeyEvent.KEYCODE_STAR -> 0x09 // EV_KEY_8 + SHIFT
            KeyEvent.KEYCODE_POUND -> 0x04 // EV_KEY_3 + SHIFT
            KeyEvent.KEYCODE_AT -> 0x03 // EV_KEY_2 + SHIFT
            KeyEvent.KEYCODE_PLUS -> 0x0D // EV_KEY_EQUAL + SHIFT
            // Default case for unmapped keys
            else -> -1
        }
    }

    fun shouldSyntheticallyPressShift(event: KeyEvent): Boolean {
        // KeyCodes that require a synthetic SHIFT press to be sent to the VM.
        val keysRequiringShiftOverride =
            setOf(
                KeyEvent.KEYCODE_AT,
                KeyEvent.KEYCODE_POUND,
                KeyEvent.KEYCODE_STAR,
                KeyEvent.KEYCODE_PLUS,
            )
        return event.keyCode in keysRequiringShiftOverride
    }

    inner class InputConnection : BaseInputConnection(this, false) {
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.isShiftPressed || shouldSyntheticallyPressShift(event)) {
                virtualMachine.sendKeyEvent(HARDWARE_KEYCODE_SHIFT, true)
            }
            val result =
                virtualMachine.sendKeyEvent(
                    convertAndroidKeyCodeToEvdevScanCode(event.keyCode),
                    event.action != MotionEvent.ACTION_UP,
                )
            if (event.isShiftPressed || shouldSyntheticallyPressShift(event)) {
                virtualMachine.sendKeyEvent(HARDWARE_KEYCODE_SHIFT, false)
            }
            return result
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        outAttrs.imeOptions =
            outAttrs.imeOptions or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN
        return InputConnection()
    }
}
