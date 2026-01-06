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

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Bundle
import android.system.virtualmachine.VirtualMachine
import android.system.virtualmachine.VirtualMachineManager
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.RelativeLayout
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.android.virtualization.terminal.new2.ui.main.DisplayResolution
import com.google.android.material.button.MaterialButton

class DisplayActivity : BaseActivity() {
    private lateinit var mainView: DisplaySurfaceView
    private lateinit var cursorView: SurfaceView
    private lateinit var pipButton: Button
    private lateinit var fullscreenButton: MaterialButton
    private lateinit var keyboardButton: MaterialButton
    private lateinit var modifierKeysButton: MaterialButton
    private lateinit var modifierKeysContainer: ViewGroup
    private lateinit var mouseLockButton: MaterialButton
    private lateinit var displayFlow: androidx.constraintlayout.helper.widget.Flow
    private lateinit var displayProvider: DisplayProvider
    private lateinit var pictureInPictureParams: PictureInPictureParams
    private var hasPointerCapture = false
    private var debianVm: VirtualMachine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_display)
        initializeViews()
        val vmm = applicationContext.getSystemService(VirtualMachineManager::class.java)
        debianVm = vmm?.get("debian")
        // TODO: Add error handling if vmm is missing
        debianVm?.let { vm ->
            setupDisplayAndInput(vm)
            setupUI()
        }
    }

    private fun setupDisplayAndInput(vm: VirtualMachine) {
        // Connect the views to the VM
        val width = vm.config.customImageConfig?.displayConfig!!.width
        val height = vm.config.customImageConfig?.displayConfig!!.height
        val ratio = android.util.Rational(width, height)
        displayProvider =
            DisplayProvider(mainView, cursorView, width, height, DisplayResolution.HALF)
        InputForwarder(this, vm, mainView, mainView, mainView) {
            hasPointerCapture = false
            mainView.releasePointerCapture()
        }
        // Calculate the screen ratio of the VM
        (mainView.layoutParams as ConstraintLayout.LayoutParams).dimensionRatio =
            ratio.toFloat().toString()
        mainView.post {
            val sourceRectHint = Rect()
            mainView.getGlobalVisibleRect(sourceRectHint)
            pictureInPictureParams =
                PictureInPictureParams.Builder()
                    .setAspectRatio(ratio)
                    .setSourceRectHint(sourceRectHint)
                    .setAutoEnterEnabled(true)
                    .build()
            setPictureInPictureParams(pictureInPictureParams)
        }
    }

    private fun initializeViews() {
        mainView = findViewById(R.id.surface_view)
        cursorView = findViewById(R.id.cursor_surface_view)
        pipButton = findViewById(R.id.pip_button)
        fullscreenButton = findViewById(R.id.fullscreen_button)
        keyboardButton = findViewById(R.id.keyboard_button)
        modifierKeysButton = findViewById(R.id.modifier_keys_button)
        modifierKeysContainer = findViewById(R.id.display_activity_modifier_keys_container)
        mouseLockButton = findViewById(R.id.mouse_lock_button)
        displayFlow = findViewById(R.id.display_flow)
    }

    private fun setupUI() {
        setupButtons()
        setupModifierKeys()
        setupBackCallback()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
    }

    private fun setupBackCallback() {
        val callback =
            object : OnBackPressedCallback(true /* enabled by default */) {
                override fun handleOnBackPressed() {
                    // If it's in fullscreen mode, exit fullscreen instead of close the activity.
                    if (isFullscreen()) {
                        exitFullscreen()
                    } else {
                        this@DisplayActivity.enterPictureInPictureMode(pictureInPictureParams)
                    }
                }
            }

        onBackPressedDispatcher.addCallback(callback)
    }

    private fun setupButtons() {
        pipButton.setOnClickListener { this.enterPictureInPictureMode(pictureInPictureParams) }

        fullscreenButton.setOnClickListener { makeFullscreen() }

        keyboardButton.setOnClickListener { showSoftKeyboard() }

        mouseLockButton.setOnClickListener {
            hasPointerCapture = !hasPointerCapture
            if (hasPointerCapture) {
                mainView.requestPointerCapture()
            } else {
                mainView.releasePointerCapture()
            }
        }
    }

    private fun setupModifierKeys() {
        val modifierKeysContainerView =
            findViewById<RelativeLayout>(R.id.display_activity_modifier_keys_container) as ViewGroup
        val modifierKeysView =
            LayoutInflater.from(this)
                .inflate(R.layout.modifier_keys_display, modifierKeysContainerView)
        modifierKeysView.isVisible = false

        findViewById<MaterialButton>(R.id.modifier_keys_button).setOnClickListener {
            modifierKeysView.isVisible = !modifierKeysView.isVisible
        }

        // Use a onTouchListener to catch the press and release event for combination keys like
        // Ctrl-T
        val listener =
            View.OnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_UP -> {
                        BTN_KEY_CODE_MAP[view.id]?.let { androidKeyCode ->
                            val scanCode =
                                mainView.convertAndroidKeyCodeToEvdevScanCode(androidKeyCode)
                            if (scanCode != (-1).toShort()) {
                                debianVm?.sendKeyEvent(
                                    scanCode,
                                    event.action == MotionEvent.ACTION_DOWN,
                                )
                            }
                        }
                    }
                }
                // Return false to let the next lister to handle the touch event for accessibility.
                false
            }

        BTN_KEY_CODE_MAP.keys.forEach { buttonId ->
            modifierKeysView.findViewById<View>(buttonId).setOnTouchListener(listener)
        }
    }

    private fun showSoftKeyboard() {
        mainView.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(mainView, 0)
    }

    private fun makeFullscreen() {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.run {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // TODO: Consider devices like laptops where landscape is the default mode.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun exitFullscreen() {
        window.setDecorFitsSystemWindows(true)
        window.insetsController?.run {
            show(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_DEFAULT
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun isFullscreen(): Boolean {
        val insets = ViewCompat.getRootWindowInsets(window.decorView)
        return insets?.isVisible(WindowInsetsCompat.Type.statusBars()) == false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasPointerCapture && hasFocus) {
            mainView.requestPointerCapture()
        }
    }

    companion object {
        /**
         * Map of button IDs to Linux key codes. The key codes are defined in
         * linux/input-event-codes.h
         * https://elixir.bootlin.com/linux/latest/source/include/uapi/linux/input-event-codes.h
         */
        val BTN_KEY_CODE_MAP =
            mapOf<Int, Int>(
                R.id.btn_f1 to KeyEvent.KEYCODE_F1,
                R.id.btn_f2 to KeyEvent.KEYCODE_F2,
                R.id.btn_f3 to KeyEvent.KEYCODE_F3,
                R.id.btn_f4 to KeyEvent.KEYCODE_F4,
                R.id.btn_f5 to KeyEvent.KEYCODE_F5,
                R.id.btn_f6 to KeyEvent.KEYCODE_F6,
                R.id.btn_f7 to KeyEvent.KEYCODE_F7,
                R.id.btn_f8 to KeyEvent.KEYCODE_F8,
                R.id.btn_f9 to KeyEvent.KEYCODE_F9,
                R.id.btn_f10 to KeyEvent.KEYCODE_F10,
                R.id.btn_f11 to KeyEvent.KEYCODE_F11,
                R.id.btn_f12 to KeyEvent.KEYCODE_F12,
                R.id.btn_ctrl to KeyEvent.KEYCODE_CTRL_LEFT,
                R.id.btn_tab to KeyEvent.KEYCODE_TAB,
                R.id.btn_alt to KeyEvent.KEYCODE_ALT_LEFT,
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
