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

package com.android.virtualization.terminal.new2.ui

import android.content.res.Configuration
import android.view.WindowInsets as ViewWindowInsets
import android.view.WindowInsetsAnimation
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * A container that manages IME visibility and animations for terminal screens. It ensures that the
 * stable padding information is only updated after the IME animation ends to prevent flickering. It
 * also displays the ModifierKeys synced with the IME animation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImeAwareContainer(
    modifier: Modifier = Modifier,
    isFocused: Boolean = true,
    isFullscreen: Boolean = false,
    onImeVisibilityChanged: (Boolean) -> Unit = {},
    onKeyAction: (ExtraKey, Int) -> Unit,
    content: @Composable (stablePadding: Dp) -> Unit,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Stable padding that only updates after animations end
    var currentBottomPadding by remember {
        mutableStateOf(view.rootWindowInsets.getBottomPadding(density, isFullscreen))
    }

    // This hidden View acts as a listener for WindowInsets animations.
    AndroidView(
        factory = { ctx ->
            android.view.View(ctx).apply {
                visibility = android.view.View.GONE
                setWindowInsetsAnimationCallback(
                    object : WindowInsetsAnimation.Callback(DISPATCH_MODE_STOP) {
                        override fun onProgress(
                            insets: ViewWindowInsets,
                            runningAnimations: MutableList<WindowInsetsAnimation>,
                        ): ViewWindowInsets = insets

                        override fun onEnd(animation: WindowInsetsAnimation) {
                            if (animation.typeMask and ViewWindowInsets.Type.ime() != 0) {
                                currentBottomPadding =
                                    rootWindowInsets.getBottomPadding(density, isFullscreen)
                            }
                        }
                    }
                )
            }
        }
    )

    val modifierKeysHeight = if (isLandscape) 40.dp else 80.dp
    val isWindowImeVisible = WindowInsets.isImeVisible && WindowInsets.ime.getBottom(density) > 0

    // Notify the caller about IME visibility changes only if we are focused.
    LaunchedEffect(isWindowImeVisible, isFocused) {
        if (isFocused) {
            // If the window says IME is hidden but we (likely) want it visible,
            // wait a bit to see if it's just a transient state during tab switching.
            if (!isWindowImeVisible) {
                delay(500)
            }
            onImeVisibilityChanged(isWindowImeVisible)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // Provide the stable total bottom height (IME + ModifierKeys) to the content
        val stableTotalPadding =
            currentBottomPadding + (if (isWindowImeVisible) modifierKeysHeight else 0.dp)

        content(stableTotalPadding)

        // Modifier Keys Layer: Follows IME animation in real-time.
        if (isWindowImeVisible) {
            val imeInsets =
                if (isFullscreen) {
                    WindowInsets.ime
                } else {
                    WindowInsets.ime.exclude(WindowInsets.navigationBars)
                }
            Box(modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(imeInsets)) {
                ModifierKeys(onKeyAction = onKeyAction)
            }
        }
    }
}

/** Calculates the bottom padding required for the IME, optionally including navigation bars. */
private fun ViewWindowInsets?.getBottomPadding(density: Density, isFullscreen: Boolean): Dp {
    val bottom =
        if (this != null) {
            val imeInsets = getInsets(ViewWindowInsets.Type.ime())
            if (isFullscreen) {
                // In fullscreen, include navigation bars height because MainScreen doesn't provide
                // it
                imeInsets.bottom
            } else {
                val navInsets = getInsets(ViewWindowInsets.Type.navigationBars())
                (imeInsets.bottom - navInsets.bottom).coerceAtLeast(0)
            }
        } else {
            0
        }
    return with(density) { bottom.toDp() }
}
