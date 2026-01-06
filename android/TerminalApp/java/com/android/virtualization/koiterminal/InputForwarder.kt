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
import android.hardware.input.InputManager
import android.os.Handler
import android.system.virtualmachine.VirtualMachine
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.android.virtualization.terminal.MainActivity.Companion.TAG

/** Forwards input events (touch, mouse, ...) from Android to VM */
internal class InputForwarder(
    private val context: Context,
    vm: VirtualMachine,
    touchReceiver: View,
    mouseReceiver: View,
    keyReceiver: DisplaySurfaceView,
) {
    private val virtualMachine: VirtualMachine = vm
    private var inputDeviceListener: InputManager.InputDeviceListener? = null
    private var isTabletMode = false

    init {
        val config = vm.config.customImageConfig

        checkNotNull(config)

        if (config.useTouch() == true) {
            setupTouchReceiver(touchReceiver)
        }
        if (config.useMouse() || config.useTrackpad()) {
            setupMouseReceiver(mouseReceiver)
        }
        if (config.useKeyboard()) {
            setupKeyReceiver(keyReceiver)
        }
        if (config.useSwitches()) {
            // Any view's handler is fine.
            setupTabletModeHandler(touchReceiver.getHandler())
        }
    }

    fun cleanUp() {
        if (inputDeviceListener != null) {
            val im = context.getSystemService<InputManager>(InputManager::class.java)
            im.unregisterInputDeviceListener(inputDeviceListener)
            inputDeviceListener = null
        }
    }

    private fun setupTouchReceiver(receiver: View) {
        receiver.setOnTouchListener { v, event ->
            virtualMachine.config.customImageConfig?.displayConfig?.let { displayConfig ->
                val displayWidth = displayConfig.width.toFloat()
                val scale = displayWidth / v.width
                virtualMachine.setTouchScale(scale)
                virtualMachine.sendMultiTouchEvent(event)
            }
            true
        }
    }

    private fun setupMouseReceiver(receiver: View) {
        receiver.requestUnbufferedDispatch(InputDevice.SOURCE_ANY)
        receiver.setOnCapturedPointerListener { v: View?, event: MotionEvent? ->
            val eventSource = event!!.source
            if ((eventSource and InputDevice.SOURCE_CLASS_POSITION) != 0) {
                return@setOnCapturedPointerListener virtualMachine.sendTrackpadEvent(event)
            }
            virtualMachine.sendMouseEvent(event)
        }
    }

    private fun setupKeyReceiver(receiver: DisplaySurfaceView) {
        receiver.virtualMachine = virtualMachine
        receiver.setOnKeyListener { v: View?, code: Int, event: KeyEvent? ->
            // TODO: this is guest-os specific. It shouldn't be handled here.
            if (isVolumeKey(code)) {
                return@setOnKeyListener false
            }
            if (event != null) {
                virtualMachine.sendKeyEvent(
                    event.scanCode.toShort(),
                    event.action != MotionEvent.ACTION_UP,
                )
            } else {
                false
            }
        }
    }

    private fun setupTabletModeHandler(handler: Handler?) {
        val im = context.getSystemService<InputManager?>(InputManager::class.java)
        inputDeviceListener =
            object : InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(deviceId: Int) {
                    setTabletModeConditionally()
                }

                override fun onInputDeviceRemoved(deviceId: Int) {
                    setTabletModeConditionally()
                }

                override fun onInputDeviceChanged(deviceId: Int) {
                    setTabletModeConditionally()
                }
            }
        im!!.registerInputDeviceListener(inputDeviceListener, handler)
    }

    fun setTabletModeConditionally() {
        val tabletModeNeeded = !hasPhysicalKeyboard()
        if (tabletModeNeeded != isTabletMode) {
            val mode = if (tabletModeNeeded) "tablet mode" else "desktop mode"
            Log.d(TAG, "switching to $mode")
            isTabletMode = tabletModeNeeded
            virtualMachine.sendTabletModeEvent(tabletModeNeeded)
        }
    }

    companion object {
        private fun isVolumeKey(keyCode: Int): Boolean {
            return keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        }

        private fun hasPhysicalKeyboard(): Boolean {
            for (id in InputDevice.getDeviceIds()) {
                val d = InputDevice.getDevice(id)
                if (!d!!.isVirtual && d.isEnabled && d.isFullKeyboard) {
                    return true
                }
            }
            return false
        }
    }
}
