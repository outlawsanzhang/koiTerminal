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
package com.android.virtualization.koiterminal.new2.ui

import android.app.Activity
import android.provider.Settings
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.virtualization.koiterminal.BetterBugLauncher
import com.android.virtualization.koiterminal.R
import com.android.virtualization.koiterminal.new2.core.InstallState
import com.android.virtualization.koiterminal.new2.core.Installer
import com.android.virtualization.koiterminal.new2.ui.main.DisplayState
import com.android.virtualization.koiterminal.new2.ui.main.MainUiState
import com.android.virtualization.koiterminal.new2.ui.main.MainViewModel
import kotlinx.coroutines.delay

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val installState by Installer.installState.collectAsStateWithLifecycle()
    val showSettings by viewModel.showSettings.collectAsStateWithLifecycle()
    val showIfInstallerError by Installer.showIfError.collectAsStateWithLifecycle()
    val showIfError by viewModel.showIfError.collectAsStateWithLifecycle()
    val isFullscreen by viewModel.isFullscreen.collectAsStateWithLifecycle()
    val hasMandatoryPermissions by viewModel.hasMandatoryPermissions.collectAsStateWithLifecycle()

    var lastValidState by remember { mutableStateOf<MainUiState>(MainUiState.Ready) }
    if (uiState !is MainUiState.Error) {
        lastValidState = uiState
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val activity = context as Activity

    PermissionChecker(viewModel, snackbarHostState)

    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is MainUiState.Ready -> {
                snackbarHostState.currentSnackbarData?.dismiss()
            }
            is MainUiState.Stopped -> activity.finish()
            is MainUiState.Error -> {
                handleError(activity, snackbarHostState, state.handler) {
                    viewModel.setShowIfError(true)
                }
            }
            else -> {}
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(hostState = snackbarHostState) }) { innerPadding ->
        val padding = if (isFullscreen) PaddingValues(0.dp) else innerPadding
        Log.d("MainScreen", "Composing. uiState=$uiState lastValidState=$lastValidState, installState=$installState, showIfError=$showIfError, showIfInstallerError=$showIfInstallerError, padding=$padding, innerPadding=$innerPadding, isFullscreen=$isFullscreen")

        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                val installStateNow = installState
                val uiStateNow = uiState
                if (!hasMandatoryPermissions) {
                    PermissionScreen(viewModel = viewModel)
                } else if (installState !is InstallState.Installed) {
                    InstallScreen(snackbarHostState = snackbarHostState)
                } else if (installStateNow is InstallState.Error && showIfInstallerError) {
                    ErrorScreen(
                        error = installStateNow.cause,
                        onDismiss = { Installer.setShowIfError(false) },
                        onReset = { Installer.initialize(context) },
                    )
                } else if (uiStateNow is MainUiState.Error && showIfError) {
                    ErrorScreen(
                        error = (uiStateNow.handler as MainUiState.ErrorHandler.ShowBug).error,
                        onDismiss = { viewModel.setShowIfError(false) },
                        onReset = {
                            Installer.initialize(context)
                            viewModel.restartVm()
                        },
                    )
                } else
                    when (val state = lastValidState) {
                        is MainUiState.Ready -> {
                            // VM will soon be booting
                            LaunchedEffect(lastValidState) {
                                // ... unless it is stuck
                                delay(2000)
                                if (uiState is MainUiState.Ready) {
                                    Log.d("MainScreen", "UI stuck in MainUiState.Ready for 2 seconds. Reinitializing Installer.")
                                    Installer.initialize(context)
                                }
                            }
                        }
                        is MainUiState.Stopped -> {
                            // Activity will finish
                        }
                        is MainUiState.Booting -> BootingScreen()
                        is MainUiState.Running -> RunningScreen(state, viewModel)
                        is MainUiState.Stopping -> BootingScreen() // TODO: show the shutdown screen
                        else -> {}
                    }
            }

            AnimatedVisibility(
                visible = showSettings,
                enter = slideInHorizontally(initialOffsetX = { it }),
                exit = slideOutHorizontally(targetOffsetX = { it }),
            ) {
                SettingsScreen(onBack = { viewModel.setShowSettings(false) })
            }
        }
    }
}

@Composable
fun SplashScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun BootingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun RunningScreen(state: MainUiState.Running, viewModel: MainViewModel) {
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val selectedTabId by viewModel.selectedTabId.collectAsStateWithLifecycle()
    val displayState by viewModel.displayState.collectAsStateWithLifecycle()
    val isFullscreen by viewModel.isFullscreen.collectAsStateWithLifecycle()

    Column {
        if (!isFullscreen) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    TerminalTabBar(
                        tabs = tabs,
                        selectedTabId =
                            if (displayState == DisplayState.Normal) null else selectedTabId,
                        onTabSelected = { viewModel.selectTab(it) },
                        onTabClosed = { viewModel.closeTab(it) },
                        onAddTab = { viewModel.addTab() },
                        onAddSerialTab = { viewModel.addSerialTab() },
                    )
                }
                DisplayController(viewModel = viewModel)
                IconButton(
                    onClick = {
                        viewModel.setShowSettings(true)
                        viewModel.setIsImeVisible(false)
                    }
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        }
        if (displayState == DisplayState.Normal) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Wrap with key(isFullscreen) to force recreation of DisplayScreen (and its
                // internal SurfaceView and DisplayProvider) when toggling fullscreen.
                // This ensures that the VM display is correctly updated to the new
                // layout dimensions.
                key(isFullscreen) { DisplayScreen(viewModel = viewModel) }
            }
        } else {
            TerminalScreen(state.outReadingPfd, state.inWritingPfd, state.terminalAddress, selectedTabId, viewModel)
        }
    }
}

private suspend fun handleError(
    activity: Activity,
    snackbarHostState: SnackbarHostState,
    handler: MainUiState.ErrorHandler,
    onShowBug: () -> Unit,
) {
    val (messageId, actionLabel, action) =
        when (handler) {
            is MainUiState.ErrorHandler.ShowBug ->
                Triple(
                    R.string.error_title,
                    activity.getString(R.string.error_btn_show_error),
                    {
                        val error = handler.error
                        val exception = error as? Exception ?: Exception(error)
                        // BetterBugLauncher.launchBetterBugActivity(activity, exception) // don't report it to Google
                        onShowBug()
                    },
                )
        }

    snackbarHostState.currentSnackbarData?.dismiss()
    val result =
        snackbarHostState.showSnackbar(
            message = activity.getString(messageId),
            actionLabel = actionLabel,
            duration = SnackbarDuration.Indefinite,
        )
    if (result == SnackbarResult.ActionPerformed) {
        action()
    }
}

@Composable
fun ErrorScreen(error: Throwable, onDismiss: () -> Unit, onReset: () -> Unit) {
    val error = error as? Exception ?: Exception(error)
    Column(
        modifier =
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.error_title),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Left,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(32.dp))
        Box(modifier = Modifier.fillMaxWidth().weight(4f)) {
            Text(
                text = Log.getStackTraceString(error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.matchParentSize().verticalScroll(rememberScrollState()),
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        val colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
        Row(modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Spacer(modifier = Modifier.width(32.dp))
            Button(
                onClick = {
                    onDismiss()
                    onReset()
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = colors,
            ) {
                val textResId = R.string.settings_graphics_dlg_btn_restart
                Text(text = stringResource(textResId), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(modifier = Modifier.width(32.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = colors,
            ) {
                val textResId = R.string.settings_graphics_dlg_btn_later
                Text(text = stringResource(textResId), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(modifier = Modifier.width(32.dp))
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}
