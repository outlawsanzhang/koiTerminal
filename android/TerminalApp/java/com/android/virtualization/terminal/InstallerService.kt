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
package com.android.virtualization.terminal

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.android.internal.annotations.GuardedBy
import com.android.virtualization.terminal.ImageArchive.Companion.fromInternet
import com.android.virtualization.terminal.ImageArchive.Companion.fromSdCard
import com.android.virtualization.terminal.InstalledImage.Companion.getDefault
import com.android.virtualization.terminal.InstallerService.InstallerServiceImpl
import com.android.virtualization.terminal.InstallerService.WifiCheckInputStream.NoWifiException
import com.android.virtualization.terminal.MainActivity.Companion.TAG
import java.io.IOException
import java.io.InputStream
import java.lang.Exception
import java.lang.RuntimeException
import java.lang.ref.WeakReference
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

class InstallerService : Service() {
    private val lock = Any()

    private lateinit var notification: Notification

    @GuardedBy("lock") private var isInstalling = false

    @GuardedBy("lock") private var hasWifi = false

    @GuardedBy("lock") private var listener: IInstallProgressListener? = null

    private lateinit var executorService: ExecutorService
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: MyNetworkCallback

    override fun onCreate() {
        super.onCreate()

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                /* requestCode= */ 0,
                intent,
                PendingIntent.FLAG_IMMUTABLE,
            )
        notification =
            Notification.Builder(this, Application.CHANNEL_LONG_RUNNING_ID)
                .setSilent(true)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.installer_notif_title_text))
                .setContentText(getString(R.string.installer_notif_desc_text))
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()

        executorService =
            Executors.newSingleThreadExecutor(TerminalThreadFactory(applicationContext))

        connectivityManager = getSystemService<ConnectivityManager>(ConnectivityManager::class.java)
        val defaultNetwork = connectivityManager.boundNetworkForProcess
        if (defaultNetwork != null) {
            val capability = connectivityManager.getNetworkCapabilities(defaultNetwork)
            if (capability != null) {
                hasWifi = capability.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        }
        networkCallback = MyNetworkCallback()
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return InstallerServiceImpl(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        Log.d(TAG, "Starting service ...")

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        Log.d(TAG, "Service is destroyed")
        executorService.shutdown()
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }

    private fun requestInstall(isWifiOnly: Boolean) {
        synchronized(lock) {
            if (isInstalling) {
                Log.i(TAG, "already installing..")
                return
            } else {
                Log.i(TAG, "installing..")
                isInstalling = true
            }
        }

        // Make service to be long running, even after unbind() when InstallerActivity is destroyed
        // The service will still be destroyed if task is remove.
        startService(Intent(this, InstallerService::class.java))
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        executorService.execute(
            Runnable {
                val success = downloadFromSdcard() || downloadFromUrl(isWifiOnly)
                stopForeground(STOP_FOREGROUND_REMOVE)

                synchronized(lock) { isInstalling = false }
                if (success) {
                    notifyCompleted()
                }
            }
        )
    }

    private fun downloadFromSdcard(): Boolean {
        val archive = fromSdCard()
        val archive_path = archive.getPath()

        if (!archive.exists()) {
            return false
        }

        Log.i(TAG, "trying to install $archive_path")

        val dest = getDefault(this).installDir
        try {
            archive.installTo(dest, null)
            Log.i(TAG, "image is installed from $archive_path")
            return true
        } catch (e: IOException) {
            Log.i(TAG, "Failed to install $archive_path", e)
        }
        return false
    }

    private fun checkForWifiOnly(isWifiOnly: Boolean): Boolean {
        if (!isWifiOnly) {
            return true
        }
        synchronized(lock) {
            return hasWifi
        }
    }

    // TODO(b/374015561): Support pause/resume download
    private fun downloadFromUrl(isWifiOnly: Boolean): Boolean {
        if (!checkForWifiOnly(isWifiOnly)) {
            Log.e(TAG, "Install isn't started because Wifi isn't available")
            notifyError(getString(R.string.installer_error_no_wifi))
            return false
        }

        val dest = getDefault(this).installDir
        try {
            fromInternet().installTo(dest) {
                val filter = WifiCheckInputStream(it)
                filter.setWifiOnly(isWifiOnly)
                filter
            }
        } catch (e: NoWifiException) {
            Log.e(TAG, "Install failed because of Wi-Fi is gone")
            notifyError(getString(R.string.installer_error_no_wifi))
            return false
        } catch (e: UnknownHostException) {
            // Log.e() doesn't print stack trace for UnknownHostException
            Log.e(TAG, "Install failed: " + e.message, e)
            notifyError(getString(R.string.installer_error_network))
            return false
        } catch (e: SocketException) {
            Log.e(TAG, "Install failed: " + e.message, e)
            notifyError(getString(R.string.installer_error_network))
            return false
        } catch (e: IOException) {
            Log.e(TAG, "Installation failed", e)
            notifyError(getString(R.string.installer_error_unknown))
            return false
        }
        return true
    }

    private fun notifyError(displayText: String?) {
        var listener: IInstallProgressListener
        synchronized(lock) { listener = this@InstallerService.listener!! }

        try {
            listener.onError(displayText)
        } catch (e: Exception) {
            // ignore. Activity may not exist.
        }
    }

    private fun notifyCompleted() {
        var listener: IInstallProgressListener
        synchronized(lock) { listener = this@InstallerService.listener!! }

        try {
            listener.onCompleted()
        } catch (e: Exception) {
            // ignore. Activity may not exist.
        }
    }

    private class InstallerServiceImpl(service: InstallerService?) : IInstallerService.Stub() {
        // Holds weak reference to avoid Context leak
        private val mService: WeakReference<InstallerService> =
            WeakReference<InstallerService>(service)

        @Throws(RuntimeException::class)
        fun ensureServiceConnected(): InstallerService {
            val service: InstallerService? = mService.get()
            if (service == null) {
                throw RuntimeException(
                    "Internal error: Installer service is being accessed after destroyed"
                )
            }
            return service
        }

        override fun requestInstall(isWifiOnly: Boolean) {
            val service = ensureServiceConnected()
            synchronized(service.lock) { service.requestInstall(isWifiOnly) }
        }

        override fun setProgressListener(listener: IInstallProgressListener) {
            val service = ensureServiceConnected()
            synchronized(service.lock) { service.listener = listener }
        }

        override fun isInstalling(): Boolean {
            val service = ensureServiceConnected()
            synchronized(service.lock) {
                return service.isInstalling
            }
        }

        override fun isInstalled(): Boolean {
            val service = ensureServiceConnected()
            synchronized(service.lock) {
                return !service.isInstalling && getDefault(service).isInstalled()
            }
        }
    }

    private inner class WifiCheckInputStream(private val inputStream: InputStream) : InputStream() {
        private var isWifiOnly = false

        fun setWifiOnly(isWifiOnly: Boolean) {
            this@WifiCheckInputStream.isWifiOnly = isWifiOnly
        }

        @Throws(IOException::class)
        override fun read(buf: ByteArray?, offset: Int, numToRead: Int): Int {
            var remaining = numToRead
            var totalRead = 0
            while (remaining > 0) {
                if (!checkForWifiOnly(isWifiOnly)) {
                    throw NoWifiException()
                }
                val read =
                    this@WifiCheckInputStream.inputStream.read(
                        buf,
                        offset + totalRead,
                        min(READ_BYTES, remaining),
                    )
                if (read <= 0) {
                    break
                }
                totalRead += read
                remaining -= read
            }
            return totalRead
        }

        @Throws(IOException::class)
        override fun read(): Int {
            if (!checkForWifiOnly(isWifiOnly)) {
                throw NoWifiException()
            }
            return this@WifiCheckInputStream.inputStream.read()
        }

        inner class NoWifiException : SocketException()
    }

    private inner class MyNetworkCallback : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capability: NetworkCapabilities) {
            synchronized(lock) {
                hasWifi = capability.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1313 // any unique number among notifications
        private const val READ_BYTES = 1024
    }
}
