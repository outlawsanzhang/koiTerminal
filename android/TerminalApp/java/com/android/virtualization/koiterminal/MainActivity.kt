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
package com.android.virtualization.koiterminal

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.graphics.fonts.FontStyle
import android.media.MediaScannerConnection
import android.widget.TextView
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ConditionVariable
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemProperties
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.RelativeLayout
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.viewModels
import androidx.viewpager2.widget.ViewPager2
import com.android.internal.annotations.VisibleForTesting
import com.android.microdroid.test.common.DeviceProperties
import com.android.system.virtualmachine.flags.Flags
import com.android.virtualization.koiterminal.ErrorActivity.Companion.start
import com.android.virtualization.koiterminal.VmLauncherService.VmLauncherServiceCallback
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import java.net.MalformedURLException
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.termux.terminal.TerminalSession

public class MainActivity :
    BaseActivity(),
    VmLauncherServiceCallback,
    AccessibilityManager.AccessibilityStateChangeListener {
    var displayMenu: Button? = null
    var tabAddButton: Button? = null
    var tabAddSerialButton: Button? = null
    val bootCompleted = ConditionVariable()
    lateinit var modifierKeysController: ModifierKeysController
    private lateinit var tabScrollView: HorizontalScrollView
    private lateinit var executorService: ExecutorService
    private lateinit var image: InstalledImage
    private lateinit var accessibilityManager: AccessibilityManager
    private lateinit var manageExternalStorageActivityResultLauncher: ActivityResultLauncher<Intent>
    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var terminalTabAdapter: TerminalTabAdapter
    private val terminalInfo = CompletableFuture<TerminalInfo>()
    private var serialOut: ParcelFileDescriptor? = null
    private var serialIn: ParcelFileDescriptor? = null
    private val terminalViewModel: TerminalViewModel by viewModels()
    private var isVmRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockOrientationIfNecessary()

        image = InstalledImage.getDefault(this)

        val launchInstaller = installIfNecessary()

        initializeUi()

        accessibilityManager =
            getSystemService<AccessibilityManager>(AccessibilityManager::class.java)
        accessibilityManager.addAccessibilityStateChangeListener(this)

        manageExternalStorageActivityResultLauncher =
            registerForActivityResult<Intent, ActivityResult>(
                StartActivityForResult(),
                ActivityResultCallback { startVm() },
            )
        executorService =
            Executors.newSingleThreadExecutor(TerminalThreadFactory(applicationContext))

        // if installer is launched, it will be handled in onActivityResult
        if (!launchInstaller) {
            if (image.isOlderThanCurrentVersion()) {
                val intent = Intent(this, UpgradeActivity::class.java)
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                // Explicitly finish to make sure that user can't go back from ErrorActivity.
                finish()
            } else if (!Environment.isExternalStorageManager()) {
                requestStoragePermissions(this, manageExternalStorageActivityResultLauncher)
            } else {
                startVm()
            }
        }
    }

    private fun initializeUi() {
        setContentView(R.layout.activity_headless)
        tabLayout = findViewById<TabLayout>(R.id.tab_layout)
        displayMenu = findViewById<Button>(R.id.display_button)
        tabAddButton = findViewById<Button>(R.id.tab_add_button)
        tabAddSerialButton = findViewById<Button>(R.id.tab_add_serial_button)
        tabScrollView = findViewById<HorizontalScrollView>(R.id.tab_scrollview)
        val modifierKeysContainerView =
            findViewById<RelativeLayout>(R.id.modifier_keys_container) as ViewGroup

        findViewById<Button>(R.id.settings_button).setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            this.startActivity(intent)
        }

        displayMenu?.also {
            it.visibility = if (Flags.terminalGuiSupport()) View.VISIBLE else View.GONE
            it.setEnabled(false)
            if (Flags.terminalGuiSupport()) {
                it.setOnClickListener {
                    val intent = Intent(this, DisplayActivity::class.java)
                    intent.flags = intent.flags or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    this.startActivity(intent)
                }
            }
        }

        modifierKeysController = ModifierKeysController(this, modifierKeysContainerView)

        terminalTabAdapter = TerminalTabAdapter(this)
        viewPager = findViewById(R.id.pager)
        viewPager.adapter = terminalTabAdapter
        viewPager.isUserInputEnabled = false
        viewPager.offscreenPageLimit = 2

        TabLayoutMediator(tabLayout, viewPager, false, false) { _: TabLayout.Tab?, _: Int -> }
            .attach()

        tabLayout.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    tab?.position?.let {
                        terminalViewModel.selectedTabViewId = terminalTabAdapter.tabs[it].id
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) {}

                override fun onTabReselected(tab: TabLayout.Tab?) {}
            }
        )

        addTerminalTab()

        tabAddButton?.setOnClickListener { addTerminalTab() }
        tabAddSerialButton?.setOnClickListener { addTerminalTab(mode = TerminalTabAdapter.MODE_SERIAL) }
    }

    private fun createTerminalSerialTabFragment(outReadingPfd: ParcelFileDescriptor, inWritingPfd: ParcelFileDescriptor): TerminalSerialTabFragment {
        return TerminalSerialTabFragment().also {
            val session = TerminalSession(outReadingPfd, inWritingPfd, 10240, it.asSessionClient()) // FIXME: hard-coded number of buffer lines
            it.attachSession(session)
        }
    }

    private fun addTerminalTab(mode: Int = TerminalTabAdapter.MODE_TTYD) {
        // Only one serial tab possible
        val tabId = when (mode) {
            TerminalTabAdapter.MODE_SERIAL -> {
                if (terminalTabAdapter.hasSerial() || serialOut == null || serialIn == null) return
                terminalTabAdapter.addSerialTab(createTerminalSerialTabFragment(serialOut!!, serialIn!!))
            }
            TerminalTabAdapter.MODE_TTYD -> {
                terminalTabAdapter.addTab()
            }
            else -> return
        }
        val tab = tabLayout.newTab()
        tab.setCustomView(R.layout.tabitem_terminal)
        viewPager.offscreenPageLimit += 1
        terminalViewModel.selectedTabViewId = tabId
        terminalViewModel.terminalTabs[tabId] = tab
        tab.customView!!
            .findViewById<Button>(R.id.tab_close_button)
            .setOnClickListener(View.OnClickListener { _: View? -> closeTab(tab) })
        // Add and select the tab
        tabLayout.addTab(tab, true)
    }

    fun closeTab(tab: TabLayout.Tab) {
        if (terminalTabAdapter.tabs.size == 1) {
            finish()
        }
        viewPager.offscreenPageLimit -= 1
        terminalTabAdapter.deleteTab(tab.position)
        tabLayout.removeTab(tab)
    }

    private fun lockOrientationIfNecessary() {
        val hasHwQwertyKeyboard = resources.configuration.keyboard == Configuration.KEYBOARD_QWERTY
        if (hasHwQwertyKeyboard) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        } else if (resources.getBoolean(R.bool.terminal_portrait_only)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        lockOrientationIfNecessary()
        modifierKeysController.update()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Build.isDebuggable() && event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            if (event.action == KeyEvent.ACTION_UP) {
                ErrorActivity.start(this, Exception("Debug: KeyEvent.KEYCODE_UNKNOWN"))
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun requestStoragePermissions(
        context: Context,
        activityResultLauncher: ActivityResultLauncher<Intent>,
    ) {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        val uri = Uri.fromParts("package", context.getPackageName(), null)
        intent.setData(uri)
        activityResultLauncher.launch(intent)
    }

    override fun onPause() {
        super.onPause()
        MediaScannerConnection.scanFile(
            this,
            arrayOf("/storage/emulated/${userId}/Download"),
            null /* mimeTypes */,
            null, /* callback */
        )
    }

    fun fontSize(): Float {
        val config = resources.configuration
        return config.fontScale * FONT_SIZE_DEFAULT
    }

    private fun getTerminalServiceUrl(ipAddress: String?, port: Int): URL? {
        val config = resources.configuration
        // TODO: Always enable screenReaderMode (b/395845063)
        val query =
            ("?fontSize=" +
                fontSize().toInt() +
                "&fontWeight=" +
                (FontStyle.FONT_WEIGHT_NORMAL + config.fontWeightAdjustment) +
                "&fontWeightBold=" +
                (FontStyle.FONT_WEIGHT_BOLD + config.fontWeightAdjustment) +
                "&screenReaderMode=" +
                accessibilityManager.isEnabled)

        try {
            return URL("https", ipAddress, port, "/$query")
        } catch (e: MalformedURLException) {
            // this cannot happen
            return null
        }
    }

    fun connectToTerminalService(terminalFragment: TerminalTabFragment) {
        Log.i(TAG, "connectToTerminalService()")
        terminalInfo.thenAcceptAsync(
            { info ->
                Log.i("$TAG-terminalInfo.thenAcceptAsync()", "info={ipAddress: ${info.ipAddress}, port=${info.port}}")
                val url = getTerminalServiceUrl(info.ipAddress, info.port)
                Log.i("$TAG-terminalInfo.thenAcceptAsync()", "url=$url")
                runOnUiThread({
                    Log.i("$TAG-terminalInfo.thenAcceptAsync()-runOnUiThread", "Loading url")
                    terminalFragment.loadUrl(url!!.toString())
                    Log.i("$TAG-terminalInfo.thenAcceptAsync()-runOnUiThread", "Loaded url")
                })
            },
            executorService,
        )
    }

    override fun onDestroy() {
        executorService.shutdown()
        getSystemService<AccessibilityManager>(AccessibilityManager::class.java)
            .removeAccessibilityStateChangeListener(this)
        if (isVmRunning) {
            val intent = VmLauncherService.getIntentForShutdown(this, this)
            startService(intent)
        }
        finishAndRemoveTask()
        super.onDestroy()
    }

    override fun onVmStart() {
        Log.i(TAG, "onVmStart()")
        isVmRunning = true
    }

    override fun onTerminalAvailable(info: TerminalInfo) {
        Log.i(TAG, "onTerminalAvailable()")
        terminalInfo.complete(info)
    }

    override fun onSerialAvailable(outReadingPfd: ParcelFileDescriptor, inWritingPfd: ParcelFileDescriptor) {
        // TODO: pass info whether serialOut needs to be closed
        serialOut = outReadingPfd
        serialIn = inWritingPfd
        Log.i(TAG, "onSerialAvailable(), serialOut = fd${serialOut?.getFd()}, serialIn = fd${serialIn?.getFd()}")
    }

    override fun onVmShuttingDown() {
        Log.i(TAG, "onVmShuttingDown()")
        isVmRunning = false
    }

    override fun onVmStop() {
        Log.i(TAG, "onVmStop()")
        isVmRunning = false
        finish()
    }

    override fun onVmError() {
        Log.i(TAG, "onVmError()")
        isVmRunning = false
        // TODO: error cause is too simple.
        ErrorActivity.start(this, Exception("onVmError"))
    }

    override fun onTtydTimeout() {
        Log.i(TAG, "onTtydTimeout()")
        isVmRunning = false
        viewPager
                ?.findViewById<TextView>(R.id.boot_progress_text)
                ?.text = resources.getString(R.string.vm_creation_ttyd_timeout_message)
    }

    override fun onAccessibilityStateChanged(enabled: Boolean) {
        terminalViewModel.terminalTabFragments.forEach { terminalFragment ->
            (terminalFragment as? TerminalTabFragment)?.let {
                connectToTerminalService(it)
            }
        }
    }

    private val installerLauncher =
        registerForActivityResult(StartActivityForResult()) { result ->
            val resultCode = result.resultCode
            if (resultCode != RESULT_OK) {
                Log.e(TAG, "Failed to start VM. Installer returned error.")
                finish()
            }
            if (!Environment.isExternalStorageManager()) {
                requestStoragePermissions(this, manageExternalStorageActivityResultLauncher)
            } else {
                startVm()
            }
        }

    private fun installIfNecessary(): Boolean {
        // If payload from external storage exists(only for debuggable build) or there is no
        // installed image, launch installer activity.
        if (!image.isInstalled()) {
            val intent = Intent(this, InstallerActivity::class.java)
            installerLauncher.launch(intent)
            return true
        }
        return false
    }

    private fun startVm() {
        val image = InstalledImage.getDefault(this)
        if (!image.isInstalled()) {
            return
        }

        val tapIntent = Intent(this, MainActivity::class.java)
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tapPendingIntent =
            PendingIntent.getActivity(this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE)

        val settingsIntent = Intent(this, SettingsActivity::class.java)
        settingsIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val settingsPendingIntent =
            PendingIntent.getActivity(this, 0, settingsIntent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = VmLauncherService.getIntentForShutdown(this, this)
        val stopPendingIntent =
            PendingIntent.getService(
                this,
                0,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val icon = Icon.createWithResource(resources, R.drawable.ic_launcher_foreground)
        val notification: Notification =
            Notification.Builder(this, Application.CHANNEL_LONG_RUNNING_ID)
                .setSilent(true)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(resources.getString(R.string.service_notification_title))
                .setContentText(resources.getString(R.string.service_notification_content))
                .setContentIntent(tapPendingIntent)
                .setOngoing(true)
                .addAction(
                    Notification.Action.Builder(
                            icon,
                            resources.getString(R.string.service_notification_settings),
                            settingsPendingIntent,
                        )
                        .build()
                )
                .addAction(
                    Notification.Action.Builder(
                            icon,
                            resources.getString(R.string.service_notification_quit_action),
                            stopPendingIntent,
                        )
                        .build()
                )
                .build()

        val diskSize = intent.getLongExtra(EXTRA_DISK_SIZE, image.getApparentSize())

        val intent =
            VmLauncherService.getIntentForStart(
                this,
                this,
                notification,
                getDisplayInfo(),
                diskSize,
            )
        try {
            startForegroundService(intent)
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.e("$TAG-MainActivity", "Failed to start VM", e)
            finish()
        }
    }

    @VisibleForTesting
    public fun waitForBootCompleted(timeoutMillis: Long): Boolean {
        return bootCompleted.block(timeoutMillis)
    }

    companion object {
        const val TAG: String = "VmTerminalApp"
        const val PREFIX: String = "com.android.virtualization.koiterminal."
        const val EXTRA_DISK_SIZE: String = PREFIX + "EXTRA_DISK_SIZE"
        private val TERMINAL_CONNECTION_TIMEOUT_MS: Int
        private const val REQUEST_CODE_INSTALLER = 0x33
        private const val FONT_SIZE_DEFAULT = 13

        init {
            val prop =
                DeviceProperties.create(
                    DeviceProperties.PropertyGetter { key: String -> SystemProperties.get(key) }
                )
            TERMINAL_CONNECTION_TIMEOUT_MS =
                if (prop.isCuttlefish() || prop.isGoldfish()) {
                    180000 // 3 minutes
                } else {
                    20000 // 20 sec
                }
        }
    }

    fun getDisplayInfo(): DisplayInfo {
        val wm = getSystemService<WindowManager>(WindowManager::class.java)
        val metrics = wm.currentWindowMetrics
        val dispBounds = metrics.bounds

        // For now, display size is fixed.(1280x720)
        // TODO(b/427895310): support various resolutions
        val width = 1280
        val height = 720
        var dpi = (DisplayMetrics.DENSITY_DEFAULT * metrics.density).toInt()
        var refreshRate = display.refreshRate.toInt()

        return DisplayInfo(width, height, dpi, refreshRate)
    }
}
