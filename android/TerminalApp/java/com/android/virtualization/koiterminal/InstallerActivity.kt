/*
 * Copyright 2024 The Android Open Source Project
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

import android.annotation.MainThread
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.ConditionVariable
import android.os.FileUtils
import android.os.IBinder
import android.os.RemoteException
import android.text.format.Formatter
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import com.android.internal.annotations.VisibleForTesting
import com.android.virtualization.koiterminal.ImageArchive.Companion.fromSdCard
import com.android.virtualization.koiterminal.ImageArchive.Companion.getDefault
import com.android.virtualization.koiterminal.InstallerActivity.InstallProgressListener
import com.android.virtualization.koiterminal.InstallerActivity.InstallerServiceConnection
import com.android.virtualization.koiterminal.MainActivity.Companion.TAG
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import java.io.IOException
import java.lang.Exception
import java.lang.ref.WeakReference

public class InstallerActivity : BaseActivity() {
    private lateinit var waitForWifiCheckbox: CheckBox
    private lateinit var installButton: TextView

    private var service: IInstallerService? = null
    private var installerServiceConnection: ServiceConnection? = null
    private lateinit var installProgressListener: InstallProgressListener
    private var installRequested = false
    private val installCompleted = ConditionVariable()
    private var autoInstall = false

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        autoInstall = getIntent()!!.getBooleanExtra(AUTO_INSTALL_EXTRA, false)

        installProgressListener = InstallProgressListener(this)

        setContentView(R.layout.activity_installer)
        updateSizeEstimation(ESTIMATED_IMG_SIZE_BYTES)
        measureImageSizeAndUpdateDescription()

        waitForWifiCheckbox = findViewById<CheckBox>(R.id.installer_wait_for_wifi_checkbox)
        installButton = findViewById<TextView>(R.id.installer_install_button)

        installButton.setOnClickListener(View.OnClickListener { requestInstall() })

        val intent = Intent(this, InstallerService::class.java)
        installerServiceConnection = InstallerServiceConnection(this)
        if (!bindService(intent, installerServiceConnection!!, BIND_AUTO_CREATE)) {
            handleInternalError(Exception("Failed to connect to installer service"))
        }
    }

    private fun updateSizeEstimation(est: Long) {
        val desc =
            getString(R.string.installer_desc_text_format, Formatter.formatShortFileSize(this, est))
        runOnUiThread {
            val view = findViewById<TextView>(R.id.installer_desc)
            view.text = desc
        }
    }

    private fun measureImageSizeAndUpdateDescription() {
        Thread {
                val est: Long =
                    try {
                        getDefault().getSize()
                    } catch (e: IOException) {
                        Log.w(TAG, "Failed to measure image size.", e)
                        return@Thread
                    }
                updateSizeEstimation(est)
            }
            .start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()

        if (autoInstall || (fromSdCard().exists())) {
            showSnackBar("Auto installing", Snackbar.LENGTH_LONG)
            requestInstall()
        }
    }

    public override fun onDestroy() {
        if (installerServiceConnection != null) {
            unbindService(installerServiceConnection!!)
            installerServiceConnection = null
        }

        super.onDestroy()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_START) {
            requestInstall()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    @VisibleForTesting
    public fun waitForInstallCompleted(timeoutMillis: Long): Boolean {
        return installCompleted.block(timeoutMillis)
    }

    private fun showSnackBar(message: String, length: Int) {
        val snackBar = Snackbar.make(findViewById<View>(android.R.id.content), message, length)
        snackBar.anchorView = waitForWifiCheckbox
        snackBar.show()
    }

    fun handleInternalError(e: Exception) {
        if (true) {
            showSnackBar(
                e.message + ". File a bugreport to go/ferrochrome-bug",
                Snackbar.LENGTH_INDEFINITE,
            )
        }
        Log.e(TAG, "Internal error", e)
        finishWithResult(RESULT_CANCELED)
    }

    private fun finishWithResult(resultCode: Int) {
        if (resultCode == RESULT_OK) {
            installCompleted.open()
        }
        setResult(resultCode)
        finish()
    }

    private fun setInstallEnabled(enabled: Boolean) {
        installButton.setEnabled(enabled)
        waitForWifiCheckbox.setEnabled(enabled)
        val progressBar = findViewById<LinearProgressIndicator>(R.id.installer_progress)
        progressBar.visibility = if (enabled) View.INVISIBLE else View.VISIBLE

        val resId =
            if (enabled) R.string.installer_install_button_enabled_text
            else R.string.installer_install_button_disabled_text
        installButton.text = getString(resId)
    }

    @MainThread
    private fun requestInstall() {
        setInstallEnabled(/* enabled= */ false)

        if (service != null) {
            try {
                service!!.requestInstall(waitForWifiCheckbox.isChecked)
            } catch (e: RemoteException) {
                handleInternalError(e)
            }
        } else {
            Log.d(TAG, "requestInstall() is called, but not yet connected")
            installRequested = true
        }
    }

    @MainThread
    fun handleInstallerServiceConnected() {
        try {
            service!!.setProgressListener(installProgressListener)
            if (service!!.isInstalled()) {
                // Finishing this activity will trigger MainActivity::onResume(),
                // and VM will be started from there.
                finishWithResult(RESULT_OK)
                return
            }

            if (installRequested) {
                requestInstall()
            } else if (service!!.isInstalling()) {
                setInstallEnabled(false)
            }
        } catch (e: RemoteException) {
            handleInternalError(e)
        }
    }

    @MainThread
    fun handleInstallerServiceDisconnected() {
        handleInternalError(Exception("InstallerService is destroyed while in use"))
    }

    @MainThread
    private fun handleInstallError(displayText: String) {
        showSnackBar(displayText, Snackbar.LENGTH_LONG)
        setInstallEnabled(true)
    }

    private class InstallProgressListener(activity: InstallerActivity) :
        IInstallProgressListener.Stub() {
        private val activity: WeakReference<InstallerActivity> =
            WeakReference<InstallerActivity>(activity)

        override fun onCompleted() {
            val activity = activity.get()
            if (activity == null) {
                // Ignore incoming connection or disconnection after activity is destroyed.
                return
            }

            // MainActivity will be resume and handle rest of progress.
            activity.finishWithResult(RESULT_OK)
        }

        override fun onError(displayText: String) {
            val context = activity.get()
            if (context == null) {
                // Ignore incoming connection or disconnection after activity is destroyed.
                return
            }

            context.runOnUiThread {
                val activity = activity.get()
                if (activity == null) {
                    // Ignore incoming connection or disconnection after activity is
                    // destroyed.
                    return@runOnUiThread
                }
                activity.handleInstallError(displayText)
            }
        }
    }

    @MainThread
    class InstallerServiceConnection internal constructor(activity: InstallerActivity) :
        ServiceConnection {
        private val activity: WeakReference<InstallerActivity> =
            WeakReference<InstallerActivity>(activity)

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val activity = activity.get()
            if (activity == null || activity.installerServiceConnection == null) {
                // Ignore incoming connection or disconnection after activity is destroyed.
                return
            }
            if (service == null) {
                activity.handleInternalError(Exception("service shouldn't be null"))
            }

            activity.service = IInstallerService.Stub.asInterface(service)
            activity.handleInstallerServiceConnected()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val activity = activity.get()
            if (activity == null || activity.installerServiceConnection == null) {
                // Ignore incoming connection or disconnection after activity is destroyed.
                return
            }

            activity.unbindService(activity.installerServiceConnection!!)
            activity.installerServiceConnection = null
            activity.handleInstallerServiceDisconnected()
        }
    }

    companion object {
        private val AUTO_INSTALL_EXTRA = "AUTO_INSTALL"
        private val ESTIMATED_IMG_SIZE_BYTES = FileUtils.parseSize("550MB")
    }
}
