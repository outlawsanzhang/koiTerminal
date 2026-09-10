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

import android.Manifest.permission
import android.Manifest.permission_group
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.icu.number.NumberFormatter
import android.icu.number.NumberRangeFormatter
import android.icu.number.Precision
import android.icu.text.MeasureFormat
import android.icu.util.MeasureUnit
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AddToHomeScreen
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.AppBlocking
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NearbyError
import androidx.compose.material.icons.filled.NearbyOff
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.virtualization.koiterminal.GraphicsManager
import com.android.virtualization.koiterminal.R
import com.android.virtualization.koiterminal.new2.core.OpenPort
import com.android.virtualization.koiterminal.new2.core.VmController
import com.android.virtualization.koiterminal.new2.ui.main.DisplayResolution
import com.android.virtualization.koiterminal.new2.ui.main.MainViewModel
import com.android.virtualization.koiterminal.new2.ui.main.NetworkConnection
import com.android.virtualization.koiterminal.new2.ui.main.SettingsViewModel
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

val NETWORK_LIMITATION_LINK_URL = "https://github.com/outlawsanzhang/koiTerminal/blob/koiterminal/FAQ.md#network-settings-limitations"

enum class SettingsDestination(val title: Int, val icon: ImageVector) {
    PortControl(R.string.settings_port_title, Icons.Default.Security),
    Network(R.string.settings_network_title, Icons.Default.SettingsEthernet),
    Advanced(R.string.settings_advanced_title, Icons.Default.Tune),
    Recovery(R.string.settings_recovery_title, Icons.Default.Restore),
}

enum class KeepAwakeDuration(val minutes: Int, val stringRes: Int) {
    OFF(0, R.string.settings_keep_awake_off),
    ONE_MINUTE(1, R.string.settings_keep_awake_1m),
    FIVE_MINUTES(5, R.string.settings_keep_awake_5m),
    TEN_MINUTES(10, R.string.settings_keep_awake_10m),
    THIRTY_MINUTES(30, R.string.settings_keep_awake_30m),
    ONE_HOUR(60, R.string.settings_keep_awake_1h),
    TWO_HOURS(120, R.string.settings_keep_awake_2h),
    SIX_HOURS(360, R.string.settings_keep_awake_6h),
    ONE_DAY(1440, R.string.settings_keep_awake_1d);

    companion object {
        fun fromMinutes(minutes: Int): KeepAwakeDuration {
            return entries.find { it.minutes == minutes } ?: OFF
        }
    }
}

enum class NetworkConnectionOptions(val icon: ImageVector, val titleRes: Int, val descRes: Int) {
    NONE(Icons.Default.OfflineBolt, R.string.settings_network_none, R.string.settings_network_none_desc),
    FULL(Icons.Default.Public, R.string.settings_network_full, R.string.settings_network_full_desc),
    MANAGED_SOCKS5(Icons.Default.VpnKey, R.string.settings_network_managed_socks5, R.string.settings_network_managed_socks5_desc),
    DELEGATE_SOCKS5(Icons.Default.Directions, R.string.settings_network_delegate_socks5, R.string.settings_network_delegate_socks5_desc);

    fun toSetting(): NetworkConnection {
        return NetworkConnection.valueOf(name)
    }

    companion object {
        fun fromSetting(connection: NetworkConnection): NetworkConnectionOptions {
            return NetworkConnectionOptions.valueOf(connection.name)
        }
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: MainViewModel = viewModel()) {
    val navigator = rememberListDetailPaneScaffoldNavigator<SettingsDestination>()
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val isMobileMode = configuration.screenWidthDp < 600
    val settingsRequest by viewModel.settingsRequest.collectAsStateWithLifecycle()
    val settingsViewModel: SettingsViewModel = viewModel()

    val destinations = remember { SettingsDestination.values().toList() }

    LaunchedEffect(settingsRequest, isMobileMode) {
        if (settingsRequest != null) {
            val destination = settingsRequest!!
            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, destination)
            if (destination == SettingsDestination.Advanced) {
                settingsViewModel.setShowKeepAwakeDialog(true)
            }
            viewModel.clearSettingsRequest()
        } else if (!isMobileMode && navigator.currentDestination == null) {
            destinations.firstOrNull()?.let {
                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, it)
            }
        }
    }

    BackHandler {
        if (navigator.canNavigateBack()) {
            scope.launch { navigator.navigateBack() }
        } else {
            onBack()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        NavigableListDetailPaneScaffold(
            navigator = navigator,
            listPane = {
                AnimatedPane {
                    SettingsListPane(
                        destinations = destinations,
                        onItemClick = { item ->
                            scope.launch {
                                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, item)
                            }
                        },
                        selectedItem =
                            navigator.currentDestination?.contentKey as? SettingsDestination,
                        onBack = onBack,
                    )
                }
            },
            detailPane = {
                AnimatedPane {
                    val destination =
                        (navigator.currentDestination?.contentKey as? SettingsDestination)
                            ?: if (!isMobileMode) destinations.firstOrNull() else null
                    if (destination != null) {
                        SettingsDetailPane(
                            destination = destination,
                            isMobileMode = isMobileMode,
                            onBack = { scope.launch { navigator.navigateBack() } },
                            onCloseSettings = onBack,
                        )
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsListPane(
    destinations: List<SettingsDestination>,
    onItemClick: (SettingsDestination) -> Unit,
    selectedItem: SettingsDestination?,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_btn_back_desc),
                        )
                    }
                },
            )
        }
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.padding(innerPadding)) {
            items(destinations) { item ->
                val isSelected = selectedItem == item
                ListItem(
                    headlineContent = { Text(stringResource(item.title)) },
                    leadingContent = { Icon(imageVector = item.icon, contentDescription = null) },
                    modifier = Modifier.clickable { onItemClick(item) },
                    colors =
                        ListItemDefaults.colors(
                            containerColor =
                                if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surface
                        ),
                )
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDetailPane(
    destination: SettingsDestination,
    isMobileMode: Boolean,
    onBack: () -> Unit,
    onCloseSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(destination.title)) },
                navigationIcon = {
                    if (isMobileMode) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.settings_btn_back_desc),
                            )
                        }
                    }
                },
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (destination) {
                SettingsDestination.PortControl -> PortControlPage()
                SettingsDestination.Network -> NetworkPage(onCloseSettings)
                SettingsDestination.Advanced -> AdvancedPage(onCloseSettings)
                SettingsDestination.Recovery -> RecoveryPage()
            }
        }
    }
}

fun checkLocalPermission(activity: Activity): Boolean {
    return activity.checkSelfPermission(android.Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
}

fun intentLaunchAppSettings(activity: Activity): Intent {
    return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", activity.packageName, null)
    }
}

@Composable
fun NetworkPage(
    onCloseSettings: () -> Unit,
    mainViewModel: MainViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val activity = LocalContext.current as Activity
    val currentNetwork by settingsViewModel.networkConnection.collectAsStateWithLifecycle()
    val currentNetworkOption = NetworkConnectionOptions.fromSetting(currentNetwork)
    val socks5Delegate by settingsViewModel.socks5Delegate.collectAsStateWithLifecycle()
    var localPermission by remember { mutableStateOf(checkLocalPermission(activity)) }
    localPermission = checkLocalPermission(activity)
    val socks5LoopbackOk by settingsViewModel.socks5LoopbackOk.collectAsStateWithLifecycle()
    var showNetworkSelectionDialog by remember { mutableStateOf(false) }
    var showDelegatePortDialog by remember { mutableStateOf(false) }
    var showRebootDialog by remember { mutableStateOf(0) }
    var delegatePort by remember { mutableStateOf<String>(socks5Delegate.toString()) }
    var portErrorResId by remember { mutableStateOf<Int?>(null) }
    val settingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            localPermission = checkLocalPermission(activity)
        }
    val localPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            localPermission = checkLocalPermission(activity)
            if (!localPermission) {
                settingsLauncher.launch(intentLaunchAppSettings(activity))
            }
        }


    if (showNetworkSelectionDialog) {
        NetworkSelectionDialog(
            currentSelection = currentNetworkOption,
            onDismissRequest = { showNetworkSelectionDialog = false },
            onConfirm = { option ->
                settingsViewModel.setNetworkConnection(option.toSetting())
                showNetworkSelectionDialog = false
                showRebootDialog = 1
            },
        )
    }
    if (showDelegatePortDialog) {
        AlertDialog(
            onDismissRequest = { showDelegatePortDialog = false },
            title = { Text(stringResource(R.string.settings_delegating_port_dlg_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = delegatePort,
                        onValueChange = { it ->
                            delegatePort = it
                            portErrorResId = null
                        },
                        label = { Text(stringResource(R.string.settings_port_dlg_hint_port_number)) },
                        isError = portErrorResId != null,
                        supportingText = portErrorResId?.let { { Text(stringResource(it)) } },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val port = delegatePort.toIntOrNull()
                        if (port == null) {
                            portErrorResId = R.string.settings_port_dlg_error_invalid_input
                        } else if (port < 1024 || port > 65535) {
                            portErrorResId = R.string.settings_port_dlg_error_invalid_range
                        } else {
                            showDelegatePortDialog = false
                            if (socks5Delegate != port) {
                                settingsViewModel.setSocks5Delegate(port)
                                showRebootDialog = 1
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.settings_port_dlg_btn_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelegatePortDialog = false }) {
                    Text(stringResource(R.string.settings_port_dlg_btn_cancel))
                }
            },
        )
    }

    if (showRebootDialog != 0) {
        AlertDialog(
            onDismissRequest = { showRebootDialog = 0 },
            title = { Text(stringResource(R.string.settings_graphics_dlg_title_restart)) },
            text = { Text(stringResource(R.string.settings_graphics_dlg_message_restart)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (showRebootDialog) {
                            1 -> {
                                showRebootDialog = 0
                                mainViewModel.restartVm()
                                onCloseSettings()
                            }
                            else -> { // force shut down app
                                showRebootDialog = 0
                                mainViewModel.stopVm()
                                onCloseSettings()
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.settings_graphics_dlg_btn_restart))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebootDialog = 0 }) {
                    Text(stringResource(R.string.settings_graphics_dlg_btn_later))
                }
            },
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            ListItem(
                headlineContent = {
                    Text(stringResource(currentNetworkOption.titleRes))
                },
                supportingContent = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(currentNetworkOption.descRes))
                    }
                },
                leadingContent = { Icon(imageVector = currentNetworkOption.icon, contentDescription = null) },
                trailingContent = {
                    Icon(imageVector = Icons.Default.ExpandMore, contentDescription = null)
                },
                modifier = Modifier.clickable { showNetworkSelectionDialog = true },
            ) 
        }
        item {
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.settings_network_open_limitations))
                },
                leadingContent = { Icon(imageVector = Icons.Default.WarningAmber, contentDescription = null) },
                trailingContent = {
                    Icon(imageVector = Icons.Default.OpenInNew, contentDescription = null)
                },
                modifier = Modifier.clickable {
                    // Using Sharesheet
                    val sendIntent: Intent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, NETWORK_LIMITATION_LINK_URL)
                        type = "text/plain"
                    }
                    val shareIntent = Intent.createChooser(sendIntent, null)
                    activity.startActivity(shareIntent)
                },
            ) 
        }
        when (currentNetwork) {
            NetworkConnection.MANAGED_SOCKS5 -> {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_socks5_connections)) },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_socks5_local_ok)) },
                        supportingContent = { Text(stringResource(R.string.settings_socks5_local_ok_desc)) },
                        leadingContent = {
                            Icon(imageVector = Icons.Default.NearbyError, contentDescription = null)
                        },
                        trailingContent = {
                            Switch(checked = localPermission, onCheckedChange = null)
                        },
                        modifier = Modifier.toggleable(
                            value = localPermission,
                            role = Role.Switch,
                        ) { ok ->
                            if (ok) {
                                // Ask for permission
                                localPermissionLauncher.launch(android.Manifest.permission.ACCESS_LOCAL_NETWORK)
                            } else {
                                // Somehow, this does not fully revoke the permission. Must direct the user to do so
                                // Are there more permissions in android.Manifest.permission_group.NEARBY_DEVICES? We don't request any.
                                // activity.revokeSelfPermissionOnKill(android.Manifest.permission.ACCESS_LOCAL_NETWORK)
                                // showRebootDialog = 2 // force shut down app
                                settingsLauncher.launch(intentLaunchAppSettings(activity))
                            }
                        },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_socks5_loopback_ok)) },
                        supportingContent = { Text(stringResource(R.string.settings_socks5_loopback_ok_desc)) },
                        leadingContent = {
                            Icon(imageVector = Icons.AutoMirrored.Filled.AddToHomeScreen, contentDescription = null)
                        },
                        trailingContent = {
                            Switch(checked = socks5LoopbackOk, onCheckedChange = null)
                        },
                        modifier = Modifier.toggleable(
                            value = socks5LoopbackOk,
                            role = Role.Switch,
                        ) { ok ->
                            settingsViewModel.setSocks5LoopbackOk(ok)
                            showRebootDialog = 1
                        },
                    )
                }
            }
            NetworkConnection.DELEGATE_SOCKS5 -> {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_socks5_delegated_port)) },
                        supportingContent = { Text(socks5Delegate.toString()) },
                        leadingContent = {
                            Icon(imageVector = Icons.Default.Pin, contentDescription = null)
                        },
                        modifier =
                            Modifier.clickable {
                                showDelegatePortDialog = true
                            },
                    )
                }
            }
            else -> {}
        }
        item {
            HorizontalDivider()
        }
    }
}

@Composable
fun NetworkSelectionDialog(
    currentSelection: NetworkConnectionOptions,
    onDismissRequest: () -> Unit,
    onConfirm: (NetworkConnectionOptions) -> Unit,
) {
    var selected by remember { mutableStateOf(currentSelection) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.settings_network_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                NetworkConnectionOptions.entries.forEachIndexed { index, option ->
                    val isSelected = option == selected
                    Row(
                        Modifier.fillMaxWidth()
                            .height(64.dp)
                            .selectable(
                                selected = isSelected,
                                onClick = { selected = option },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        // Icon(option.icon, contentDescription = null) // too ugly and cramped
                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text(
                                text = stringResource(option.titleRes),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = stringResource(option.descRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (selected != currentSelection) {
                    onConfirm(selected)
                } else {
                    onDismissRequest()
                }
            }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
fun AdvancedPage(
    onCloseSettings: () -> Unit,
    mainViewModel: MainViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val currentType = VmController.graphicsAccelerationType
    var showSelectionDialog by remember { mutableStateOf(false) }
    var showRebootDialog by remember { mutableStateOf(false) }
    var selectedType by remember { mutableStateOf(currentType) }

    val currentMemoryMb by settingsViewModel.currentMemoryMb.collectAsStateWithLifecycle()
    var showMemoryDialog by remember { mutableStateOf(false) }

    val displayResolution by settingsViewModel.displayResolution.collectAsStateWithLifecycle()
    var showResolutionDialog by remember { mutableStateOf(false) }

    val keepAwakeMinutes by settingsViewModel.keepAwakeMinutes.collectAsStateWithLifecycle()
    val showKeepAwakeDialog by settingsViewModel.showKeepAwakeDialog.collectAsStateWithLifecycle()

    val typeToName =
        mapOf(
            GraphicsManager.AccelerationType.Lavapipe to
                stringResource(R.string.settings_graphics_renderer_software),
            GraphicsManager.AccelerationType.Gfxstream to
                stringResource(R.string.settings_graphics_renderer_gpu),
        )

    if (showSelectionDialog) {
        AlertDialog(
            onDismissRequest = { showSelectionDialog = false },
            title = { Text(stringResource(R.string.settings_graphics_title)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    GraphicsManager.AccelerationType.values().forEach { type ->
                        Row(
                            Modifier.fillMaxWidth()
                                .height(56.dp)
                                .selectable(
                                    selected = (type == selectedType),
                                    onClick = { selectedType = type },
                                    role = Role.RadioButton,
                                )
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = (type == selectedType), onClick = null)
                            Text(
                                text = typeToName[type] ?: "",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSelectionDialog = false
                        if (currentType != selectedType) {
                            VmController.setGraphicsAccelerationType(selectedType)
                            showRebootDialog = true
                        }
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSelectionDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showMemoryDialog) {
        MemorySizeDialog(
            currentMemoryMb = currentMemoryMb,
            minMemoryMb = SettingsViewModel.MIN_MEMORY_MIB,
            maxMemoryMb = settingsViewModel.maxMemoryMb,
            onDismissRequest = { showMemoryDialog = false },
            onConfirm = {
                settingsViewModel.setMemoryMb(it)
                showMemoryDialog = false
                showRebootDialog = true
            },
        )
    }

    if (showKeepAwakeDialog) {
        KeepAwakeDialog(
            currentMinutes = keepAwakeMinutes,
            onDismissRequest = { settingsViewModel.setShowKeepAwakeDialog(false) },
            onConfirm = {
                settingsViewModel.setKeepAwakeMinutes(it)
                settingsViewModel.setShowKeepAwakeDialog(false)
            },
        )
    }

    if (showResolutionDialog) {
        DisplayResolutionDialog(
            currentResolution = displayResolution,
            onDismissRequest = { showResolutionDialog = false },
            onConfirm = {
                settingsViewModel.setDisplayResolution(it)
                showResolutionDialog = false
            },
        )
    }

    if (showRebootDialog) {
        AlertDialog(
            onDismissRequest = { showRebootDialog = false },
            title = { Text(stringResource(R.string.settings_graphics_dlg_title_restart)) },
            text = { Text(stringResource(R.string.settings_graphics_dlg_message_restart)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRebootDialog = false
                        mainViewModel.restartVm()
                        onCloseSettings()
                    }
                ) {
                    Text(stringResource(R.string.settings_graphics_dlg_btn_restart))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebootDialog = false }) {
                    Text(stringResource(R.string.settings_graphics_dlg_btn_later))
                }
            },
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.settings_display_resolution_title))
                },
                supportingContent = { Text(formatDisplayResolution(displayResolution)) },
                leadingContent = {
                    Icon(imageVector = Icons.Default.DisplaySettings, contentDescription = null)
                },
                modifier = Modifier.clickable { showResolutionDialog = true },
            )
            HorizontalDivider()
        }
        if (VmController.isGraphicsAccelerationSupported) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_graphics_title)) },
                    supportingContent = { Text(typeToName[currentType] ?: "") },
                    leadingContent = {
                        Icon(imageVector = Icons.Default.Speed, contentDescription = null)
                    },
                    modifier =
                        Modifier.clickable {
                            selectedType = currentType
                            showSelectionDialog = true
                        },
                )
                HorizontalDivider()
            }
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_advanced_memory_title)) },
                supportingContent = { Text(formatMemorySize(currentMemoryMb)) },
                leadingContent = {
                    Icon(imageVector = Icons.Default.Memory, contentDescription = null)
                },
                modifier = Modifier.clickable { showMemoryDialog = true },
            )
            HorizontalDivider()
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_keep_awake_title)) },
                supportingContent = { Text(formatKeepAwakeTime(keepAwakeMinutes)) },
                leadingContent = {
                    Icon(imageVector = Icons.Default.Power, contentDescription = null)
                },
                modifier = Modifier.clickable { settingsViewModel.setShowKeepAwakeDialog(true) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun MemorySizeDialog(
    currentMemoryMb: Int,
    minMemoryMb: Int,
    maxMemoryMb: Int,
    onDismissRequest: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val minLog = log2(minMemoryMb.toFloat())
    val maxLog = log2(maxMemoryMb.toFloat())

    var textValue by remember { mutableStateOf(currentMemoryMb.toString()) }
    var sliderValue by remember { mutableFloatStateOf(log2(currentMemoryMb.toFloat())) }

    val currentParsedMemory = textValue.toIntOrNull()
    val isError = currentParsedMemory == null || currentParsedMemory !in minMemoryMb..maxMemoryMb

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.settings_advanced_memory_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { newValue ->
                        if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                            textValue = newValue
                            val parsed = newValue.toIntOrNull()
                            if (parsed != null && parsed in minMemoryMb..maxMemoryMb) {
                                sliderValue = log2(parsed.toFloat())
                            }
                        }
                    },
                    label = { Text(formatMemoryUnit(MeasureUnit.MEGABYTE)) },
                    isError = isError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    supportingText = {
                        if (isError) {
                            val range = formatMemoryRange(minMemoryMb, maxMemoryMb)
                            Text(
                                stringResource(R.string.settings_advanced_memory_valid_range, range)
                            )
                        } else {
                            Text(formatMemorySize(currentParsedMemory ?: currentMemoryMb))
                        }
                    },
                )
                Slider(
                    value = sliderValue,
                    onValueChange = {
                        sliderValue = it
                        val memory = 2f.pow(it).roundToInt().coerceIn(minMemoryMb, maxMemoryMb)
                        textValue = memory.toString()
                    },
                    valueRange = minLog..maxLog,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatMemorySize(minMemoryMb),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = formatMemorySize(maxMemoryMb),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { currentParsedMemory?.let { onConfirm(it) } },
                enabled = !isError,
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private fun formatMemoryUnit(unit: MeasureUnit): String {
    val formatter = MeasureFormat.getInstance(Locale.getDefault(), MeasureFormat.FormatWidth.NARROW)
    return formatter.getUnitDisplayName(unit)
}

private fun formatMemorySize(mib: Int): String {
    if (mib >= 1000) {
        val formatter =
            NumberFormatter.withLocale(Locale.getDefault())
                .unit(MeasureUnit.GIGABYTE)
                .unitWidth(NumberFormatter.UnitWidth.SHORT)
                .precision(Precision.fixedFraction(1))
                .roundingMode(RoundingMode.DOWN)
        return formatter.format(mib.toDouble() / 1000.0).toString()
    } else {
        val formatter =
            NumberFormatter.withLocale(Locale.getDefault())
                .unit(MeasureUnit.MEGABYTE)
                .unitWidth(NumberFormatter.UnitWidth.SHORT)
        return formatter.format(mib).toString()
    }
}

private fun formatMemoryRange(minMib: Int, maxMib: Int): String {
    return NumberRangeFormatter.withLocale(Locale.getDefault())
        .numberFormatterBoth(NumberFormatter.with().unit(MeasureUnit.MEGABYTE))
        .formatRange(minMib, maxMib)
        .toString()
}

@Composable
fun KeepAwakeDialog(currentMinutes: Int, onDismissRequest: () -> Unit, onConfirm: (Int) -> Unit) {
    var selectedOption by remember { mutableStateOf(KeepAwakeDuration.fromMinutes(currentMinutes)) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.settings_keep_awake_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_keep_awake_battery_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Column(Modifier.selectableGroup()) {
                    KeepAwakeDuration.entries.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth()
                                .height(48.dp)
                                .selectable(
                                    selected = (option == selectedOption),
                                    onClick = { selectedOption = option },
                                    role = Role.RadioButton,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = (option == selectedOption), onClick = null)
                            Text(
                                text = stringResource(option.stringRes),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedOption.minutes) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun formatKeepAwakeTime(minutes: Int): String {
    val duration = KeepAwakeDuration.fromMinutes(minutes)
    return if (duration == KeepAwakeDuration.OFF && minutes > 0) {
        "$minutes min"
    } else {
        stringResource(duration.stringRes)
    }
}

@Composable
fun DisplayResolutionDialog(
    currentResolution: DisplayResolution,
    onDismissRequest: () -> Unit,
    onConfirm: (DisplayResolution) -> Unit,
) {
    var selectedOption by remember { mutableStateOf(currentResolution) }

    val resolutionToName =
        mapOf(
            DisplayResolution.FULL to stringResource(R.string.settings_display_resolution_full),
            DisplayResolution.HALF to stringResource(R.string.settings_display_resolution_half),
            DisplayResolution.QUARTER to
                stringResource(R.string.settings_display_resolution_quarter),
        )

    val resolutionToHint =
        mapOf(
            DisplayResolution.FULL to
                stringResource(R.string.settings_display_resolution_full_hint),
            DisplayResolution.HALF to
                stringResource(R.string.settings_display_resolution_half_hint),
            DisplayResolution.QUARTER to
                stringResource(R.string.settings_display_resolution_quarter_hint),
        )

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.settings_display_resolution_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                DisplayResolution.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth()
                            .height(64.dp)
                            .selectable(
                                selected = (option == selectedOption),
                                onClick = { selectedOption = option },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = (option == selectedOption), onClick = null)
                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text(
                                text = resolutionToName[option] ?: "",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = resolutionToHint[option] ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color =
                                    if (option == DisplayResolution.HALF)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedOption) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun formatDisplayResolution(resolution: DisplayResolution): String {
    return when (resolution) {
        DisplayResolution.FULL -> stringResource(R.string.settings_display_resolution_full)
        DisplayResolution.HALF -> stringResource(R.string.settings_display_resolution_half)
        DisplayResolution.QUARTER -> stringResource(R.string.settings_display_resolution_quarter)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortControlPage() {
    val ports by VmController.ports.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    val activePorts = ports.filter { !it.isSaved() }
    val savedPorts = ports.filter { it.isSaved() }

    if (showAddDialog) {
        AddPortDialog(
            onDismissRequest = { showAddDialog = false },
            onConfirm = { port ->
                VmController.enablePortForwarding(port, true)
                showAddDialog = false
            },
            ports = ports,
        )
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.settings_port_btn_add),
                )
            }
        }
    ) { innerPadding ->
        if (ports.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.settings_port_message_empty))
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                if (activePorts.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.settings_port_title_active_ports),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    items(activePorts) { port ->
                        ListItem(
                            headlineContent = { Text("${port.port} (${port.name})") },
                            trailingContent = {
                                Switch(
                                    checked = port.isForwarded,
                                    onCheckedChange = { isChecked ->
                                        VmController.enablePortForwarding(port.port, isChecked)
                                    },
                                )
                            },
                        )
                    }
                }

                if (savedPorts.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.settings_port_title_saved_ports),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    items(savedPorts) { port ->
                        ListItem(
                            headlineContent = { Text(port.port.toString()) },
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        VmController.enablePortForwarding(port.port, false)
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_close),
                                        contentDescription =
                                            stringResource(
                                                R.string.settings_port_btn_delete,
                                                port.port,
                                            ),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AddPortDialog(onDismissRequest: () -> Unit, onConfirm: (Int) -> Unit, ports: List<OpenPort>) {
    var portToAdd by remember { mutableStateOf("") }
    var portErrorResId by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.settings_port_dlg_title_add)) },
        text = {
            Column {
                OutlinedTextField(
                    value = portToAdd,
                    onValueChange = {
                        portToAdd = it
                        portErrorResId = null
                    },
                    label = { Text(stringResource(R.string.settings_port_dlg_hint_port_number)) },
                    isError = portErrorResId != null,
                    supportingText = portErrorResId?.let { { Text(stringResource(it)) } },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val port = portToAdd.toIntOrNull()
                    if (port == null) {
                        portErrorResId = R.string.settings_port_dlg_error_invalid_input
                    } else if (port < 1024 || port > 65535) {
                        portErrorResId = R.string.settings_port_dlg_error_invalid_range
                    } else if (ports.any { it.port == port }) {
                        portErrorResId = R.string.settings_port_dlg_error_existing
                    } else {
                        onConfirm(port)
                    }
                }
            ) {
                Text(stringResource(R.string.settings_port_dlg_btn_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.settings_port_dlg_btn_cancel))
            }
        },
    )
}
