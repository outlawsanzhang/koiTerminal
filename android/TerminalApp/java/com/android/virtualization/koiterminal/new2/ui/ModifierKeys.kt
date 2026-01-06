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
import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.virtualization.terminal.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class ExtraKey(val label: String, val icon: ImageVector? = null, val keyCode: Int? = null) {
    ESC("Esc", keyCode = KeyEvent.KEYCODE_ESCAPE),
    TAB("Tab", keyCode = KeyEvent.KEYCODE_TAB),
    HOME("Home", keyCode = KeyEvent.KEYCODE_MOVE_HOME),
    UP("Up", icon = Icons.Default.KeyboardArrowUp, keyCode = KeyEvent.KEYCODE_DPAD_UP),
    END("End", keyCode = KeyEvent.KEYCODE_MOVE_END),
    PGUP("PgUp", keyCode = KeyEvent.KEYCODE_PAGE_UP),
    CTRL("Ctrl", keyCode = KeyEvent.KEYCODE_CTRL_LEFT),
    ALT("Alt", keyCode = KeyEvent.KEYCODE_ALT_LEFT),
    LEFT("Left", icon = Icons.AutoMirrored.Filled.ArrowBack, keyCode = KeyEvent.KEYCODE_DPAD_LEFT),
    DOWN("Down", icon = Icons.Default.KeyboardArrowDown, keyCode = KeyEvent.KEYCODE_DPAD_DOWN),
    RIGHT(
        "Right",
        icon = Icons.AutoMirrored.Filled.ArrowForward,
        keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
    ),
    PGDN("PgDn", keyCode = KeyEvent.KEYCODE_PAGE_DOWN),
    F1("F1", keyCode = KeyEvent.KEYCODE_F1),
    F2("F2", keyCode = KeyEvent.KEYCODE_F2),
    F3("F3", keyCode = KeyEvent.KEYCODE_F3),
    F4("F4", keyCode = KeyEvent.KEYCODE_F4),
    F5("F5", keyCode = KeyEvent.KEYCODE_F5),
    F6("F6", keyCode = KeyEvent.KEYCODE_F6),
    F7("F7", keyCode = KeyEvent.KEYCODE_F7),
    F8("F8", keyCode = KeyEvent.KEYCODE_F8),
    F9("F9", keyCode = KeyEvent.KEYCODE_F9),
    F10("F10", keyCode = KeyEvent.KEYCODE_F10),
    F11("F11", keyCode = KeyEvent.KEYCODE_F11),
    F12("F12", keyCode = KeyEvent.KEYCODE_F12),
}

@Composable
fun ModifierKeys(onKeyAction: (ExtraKey, action: Int) -> Unit) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val page1Keys =
        if (isLandscape) {
            listOf(
                ExtraKey.ESC,
                ExtraKey.TAB,
                ExtraKey.CTRL,
                ExtraKey.ALT,
                ExtraKey.HOME,
                ExtraKey.END,
                ExtraKey.LEFT,
                ExtraKey.DOWN,
                ExtraKey.UP,
                ExtraKey.RIGHT,
                ExtraKey.PGDN,
                ExtraKey.PGUP,
            )
        } else {
            listOf(
                ExtraKey.ESC,
                ExtraKey.TAB,
                ExtraKey.HOME,
                ExtraKey.UP,
                ExtraKey.END,
                ExtraKey.PGUP,
                ExtraKey.CTRL,
                ExtraKey.ALT,
                ExtraKey.LEFT,
                ExtraKey.DOWN,
                ExtraKey.RIGHT,
                ExtraKey.PGDN,
            )
        }
    val page2Keys =
        listOf(
            ExtraKey.F1,
            ExtraKey.F2,
            ExtraKey.F3,
            ExtraKey.F4,
            ExtraKey.F5,
            ExtraKey.F6,
            ExtraKey.F7,
            ExtraKey.F8,
            ExtraKey.F9,
            ExtraKey.F10,
            ExtraKey.F11,
            ExtraKey.F12,
        )

    val pages = listOf(page1Keys, page2Keys)
    val pagerState = rememberPagerState(pageCount = { pages.size })
    var arrowsVisible by remember { mutableStateOf(true) }

    LaunchedEffect(arrowsVisible) {
        if (arrowsVisible) {
            delay(1000)
            arrowsVisible = false
        }
    }

    Box(
        modifier =
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        arrowsVisible = true
                        waitForUpOrCancellation()
                    }
                }
    ) {
        HorizontalPager(state = pagerState, contentPadding = PaddingValues(0.dp)) { page ->
            ModifierKeysPage(
                keys = pages[page],
                isLandscape = isLandscape,
                onKeyAction = onKeyAction,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }

        // Left Arrow
        PagerArrow(
            visible = arrowsVisible && pagerState.currentPage > 0,
            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = stringResource(R.string.modifierkeys_hint_prev_page),
            alignment = Alignment.CenterStart,
            modifier = Modifier.padding(start = 4.dp),
        )

        // Right Arrow
        PagerArrow(
            visible = arrowsVisible && pagerState.currentPage < pages.size - 1,
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = stringResource(R.string.modifierkeys_hint_next_page),
            alignment = Alignment.CenterEnd,
            modifier = Modifier.padding(end = 4.dp),
        )
    }
}

@Composable
private fun BoxScope.PagerArrow(
    visible: Boolean,
    icon: ImageVector,
    contentDescription: String,
    alignment: Alignment,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.align(alignment),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModifierKeysPage(
    keys: List<ExtraKey>,
    isLandscape: Boolean,
    onKeyAction: (ExtraKey, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = if (isLandscape) 1 else 2
    val columns = keys.size / rows

    Column(modifier = modifier) {
        keys.chunked(columns).forEach { rowKeys ->
            Row(modifier = Modifier.fillMaxWidth()) {
                rowKeys.forEach { key ->
                    ModifierKeyButton(
                        key = key,
                        modifier = Modifier.weight(1f).height(40.dp),
                        onKeyAction = onKeyAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModifierKeyButton(
    key: ExtraKey,
    modifier: Modifier = Modifier,
    onKeyAction: (ExtraKey, Int) -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    Box(
        modifier =
            modifier.repeatingClickable(
                interactionSource = remember { MutableInteractionSource() },
                enabled = true,
                onInitialPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    onKeyAction(key, KeyEvent.ACTION_DOWN)
                },
                onRepeatPress = { onKeyAction(key, KeyEvent.ACTION_DOWN) },
                onRelease = { onKeyAction(key, KeyEvent.ACTION_UP) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (key.icon != null) {
            Icon(key.icon, contentDescription = key.label)
        } else {
            Text(text = key.label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * A custom clickable modifier that supports repeating key events while handling swipe gestures.
 *
 * This modifier distinguishes between a tap (single key press), a swipe (page navigation), and a
 * long press (repeating key press). It ensures that swipe gestures on the key area are properly
 * handled by the parent pager instead of being treated as key presses.
 */
fun Modifier.repeatingClickable(
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    onInitialPress: () -> Unit,
    onRepeatPress: () -> Unit,
    onRelease: () -> Unit,
): Modifier = composed {
    val currentInitialPressListener by rememberUpdatedState(onInitialPress)
    val currentRepeatPressListener by rememberUpdatedState(onRepeatPress)
    val currentReleaseListener by rememberUpdatedState(onRelease)
    val scope = rememberCoroutineScope()
    val viewConfiguration = LocalViewConfiguration.current

    val initialDelay = remember { viewConfiguration.longPressTimeoutMillis }
    val repeatDelay = remember { ViewConfiguration.getKeyRepeatDelay().toLong() }
    val touchSlop = viewConfiguration.touchSlop

    this.pointerInput(interactionSource, enabled) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val downTime = System.currentTimeMillis()
                var dragAmount = Offset.Zero
                var isSwipe = false
                var isTap = false

                val pressInteraction = PressInteraction.Press(down.position)
                scope.launch { interactionSource.emit(pressInteraction) }

                // Slight delay to detect swipe. We wait a bit to see if the user moves their finger
                // (indicating a swipe) before deciding it's a tap or long press.
                val timeout = 150L

                try {
                    withTimeout(timeout) {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break

                            if (change.changedToUp()) {
                                isTap = true
                                break
                            }

                            dragAmount += change.positionChange()
                            if (dragAmount.getDistance() > touchSlop) {
                                isSwipe = true
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Timeout reached, assume long press / start of repeating if not swiped yet
                }

                if (isSwipe) {
                    scope.launch {
                        interactionSource.emit(PressInteraction.Cancel(pressInteraction))
                    }
                } else if (isTap) {
                    scope.launch {
                        interactionSource.emit(PressInteraction.Release(pressInteraction))
                    }
                    currentInitialPressListener()
                    currentReleaseListener()
                } else {
                    val job =
                        scope.launch {
                            currentInitialPressListener()
                            val remainingDelay =
                                (initialDelay - (System.currentTimeMillis() - downTime))
                                    .coerceAtLeast(0L)
                            delay(remainingDelay)
                            while (enabled) {
                                currentRepeatPressListener()
                                delay(repeatDelay)
                            }
                        }
                    waitForUpOrCancellation()
                    job.cancel()
                    scope.launch {
                        interactionSource.emit(PressInteraction.Release(pressInteraction))
                    }
                    currentReleaseListener()
                }
            }
        }
        .indication(interactionSource, androidx.compose.foundation.LocalIndication.current)
}
