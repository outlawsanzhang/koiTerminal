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

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.os.ResultReceiver
import android.os.StatFs
import android.os.SystemProperties
import android.system.virtualmachine.VirtualMachine
import android.system.virtualmachine.VirtualMachineCustomImageConfig
import android.system.virtualmachine.VirtualMachineCustomImageConfig.AudioConfig
import android.system.virtualmachine.VirtualMachineException
import android.util.Log
import androidx.annotation.WorkerThread
import com.android.system.virtualmachine.flags.Flags
import com.android.virtualization.koiterminal.InstalledImage.Companion.roundUp
import com.android.virtualization.koiterminal.MainActivity.Companion.PREFIX
import com.android.virtualization.koiterminal.MainActivity.Companion.TAG
import com.android.virtualization.terminal.proto.DebianServiceGrpc.DebianServiceImplBase
import io.grpc.InsecureServerCredentials
import io.grpc.Server
import io.grpc.okhttp.OkHttpServerBuilder
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VmLauncherService : Service() {
    // Thread pool
    private lateinit var bgThreads: ExecutorService
    // Single thread
    private lateinit var mainWorkerThread: ExecutorService
    private lateinit var image: InstalledImage

    // TODO: using lateinit for some fields to avoid null
    private var virtualMachine: VirtualMachine? = null
    private var server: Server? = null
    private var debianService: DebianServiceBase? = null
    private var portNotifier: PortNotifier? = null
    private var runner: Runner? = null
    private var handler: Handler? = null

    interface VmLauncherServiceCallback {
        fun onVmStart()

        fun onTerminalAvailable(info: TerminalInfo)

        fun onVmShuttingDown()

        fun onVmStop()

        fun onVmError()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        val threadFactory = TerminalThreadFactory(applicationContext)
        bgThreads = Executors.newCachedThreadPool(threadFactory)
        mainWorkerThread = Executors.newSingleThreadExecutor(threadFactory)
        image = InstalledImage.getDefault(this)
        handler = Handler(Looper.getMainLooper())
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val resultReceiver =
            intent.getParcelableExtra<ResultReceiver>(
                Intent.EXTRA_RESULT_RECEIVER,
                ResultReceiver::class.java,
            )

        when (intent.action) {
            ACTION_START_VM -> {
                val notification =
                    intent.getParcelableExtra<Notification>(
                        EXTRA_NOTIFICATION,
                        Notification::class.java,
                    )!!

                val displayInfo =
                    intent.getParcelableExtra(EXTRA_DISPLAY_INFO, DisplayInfo::class.java)!!

                mainWorkerThread.execute({ doStart(displayInfo, resultReceiver!!) })

                // Do this outside of the main worker thread, so that we don't cause
                // ForegroundServiceDidNotStartInTimeException
                startForeground(this.hashCode(), notification)
            }
            ACTION_SHUTDOWN_VM -> mainWorkerThread.execute({ doShutdown(resultReceiver) })
            else -> {
                Log.e(TAG, "Unknown command " + intent.action)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun calculateSparseDiskSize(): Long {
        // Create a sparse file with 95% of the total size for storage ballooning.
        val statFs = StatFs(filesDir.absolutePath)
        val hostSize = statFs.totalBytes
        return roundUp(hostSize * GUEST_SPARSE_DISK_SIZE_PERCENTAGE / 100)
    }

    private fun truncateDiskIfNecessary(image: InstalledImage) {
        val curSize = image.getApparentSize()
        val physicalSize = image.getPhysicalSize()

        val expectedSize = calculateSparseDiskSize()
        Log.d(
            TAG,
            "rootfs apparent size=$curSize, physical size=$physicalSize, expectedSize=$expectedSize",
        )

        if (curSize != expectedSize) {
            try {
                image.truncate(expectedSize)
            } catch (e: IOException) {
                throw RuntimeException("Failed to truncate a disk", e)
            }
        }
    }

    @WorkerThread
    private fun doStart(displayInfo: DisplayInfo, resultReceiver: ResultReceiver) {
        val image = InstalledImage.getDefault(this)
        val json = ConfigJson.from(this, image.configPath)
        val configBuilder = json.toConfigBuilder(this)
        val customImageConfigBuilder = json.toCustomImageConfigBuilder(this)
        val timeout_secs = json.getBootTimeoutSecs() * (if (IS_EMULATOR) 20 else 1)

        // Convert rootfs disk into a sparse file for storage ballooning.
        truncateDiskIfNecessary(image)

        val port = startDebianServer()
        customImageConfigBuilder.addParam("debian_server_port=$port")

        customImageConfigBuilder.setAudioConfig(
            AudioConfig.Builder().setUseSpeaker(true).setUseMicrophone(true).build()
        )
        if (overrideConfigIfNecessary(customImageConfigBuilder, displayInfo)) {
            configBuilder.setCustomImageConfig(customImageConfigBuilder.build())
        }
        val config = configBuilder.build()

        runner =
            try {
                Runner.create(this, config)
            } catch (e: VirtualMachineException) {
                throw RuntimeException("cannot create runner", e)
            }

        val virtualMachine = runner!!.vm
        val mbc = MemBalloonController(this, virtualMachine)
        mbc.start()

        runner!!.shutdownStarted.thenAcceptAsync { resultReceiver.send(RESULT_SHUTTING_DOWN, null) }
        runner!!.exitStatus.thenAcceptAsync { success: Boolean ->
            mbc.stop()
            resultReceiver.send(if (success) RESULT_STOP else RESULT_ERROR, null)
            stopSelf()
        }
        Logger.setup(this, virtualMachine, bgThreads)

        resultReceiver.send(RESULT_START, null)

        portNotifier = PortNotifier(this)

        if (canUseTtydOverVsock()) {
            Log.i(TAG, "canUseTtydOverVsock() true")
            val bridge = AndroidToVmBridge(virtualMachine.getCid())
            val port = bridge.start()
            Log.i(TAG, "canUseTtydOverVsock() bridge started, port = $port")
            if (port == null) {
                Log.e(TAG, "Failed to start bridge")
                resultReceiver.send(RESULT_ERROR, null)
                stopSelf()
            } else {
                val bundle = Bundle()
                bundle.putString(KEY_TERMINAL_IPADDRESS, "localhost")
                bundle.putInt(KEY_TERMINAL_PORT, port)
                bundle.putString(KEY_TERMINAL_KEY, bridge.secretKey)
                Log.i(TAG, "canUseTtydOverVsock() Sending result RESULT_TERMINAL_AVAIL")
                resultReceiver.send(RESULT_TERMINAL_AVAIL, bundle)
                Log.i(TAG, "canUseTtydOverVsock() Sent result RESULT_TERMINAL_AVAIL")
            }
        } else {
            getTerminalServiceInfo(timeout_secs)
                .thenAcceptAsync(
                    { info ->
                        // It must exist because it is checked in `getTerminalServiceInfo`
                        Log.i("$TAG-getTerminalServiceInfo()", "thenAcceptAsync")
                        val guestIpAddress =
                            info.hostAddresses
                                .firstOrNull { !it.isLinkLocalAddress }!!
                                .hostAddress!!
                        Log.i("$TAG-getTerminalServiceInfo().thenAcceptAsync", "ip = $guestIpAddress")

                        val bundle = Bundle()
                        bundle.putString(KEY_TERMINAL_IPADDRESS, guestIpAddress)
                        bundle.putInt(KEY_TERMINAL_PORT, info.port)
                        Log.i("$TAG-getTerminalServiceInfo().thenAcceptAsync", "Sending result RESULT_TERMINAL_AVAIL")
                        resultReceiver.send(RESULT_TERMINAL_AVAIL, bundle)
                        Log.i("$TAG-getTerminalServiceInfo().thenAcceptAsync", "Sent result RESULT_TERMINAL_AVAIL")
                    },
                    bgThreads,
                )
                .exceptionallyAsync(
                    { e ->
                        Log.e(TAG, "Failed to start VM", e)
                        resultReceiver.send(RESULT_ERROR, null)
                        stopSelf()
                        null
                    },
                    bgThreads,
                )
        }
    }

    private fun canUseTtydOverVsock(): Boolean {
        val buildId = InstalledImage.getDefault(this).buildInfo?.buildId ?: 0
        val FIRST_VERSION_SUPPORTS_TTYD_VSOCK = 5106
        return Flags.terminalVmCommunicationRefactoring() &&
            buildId >= FIRST_VERSION_SUPPORTS_TTYD_VSOCK
    }

    private fun getTerminalServiceInfo(timeout_secs: Int): CompletableFuture<NsdServiceInfo> {
        check(!canUseTtydOverVsock()) {
            "getTerminalServiceInfo should only be called when ttyd over vsock is not supported"
        }
        val executor = Executors.newSingleThreadExecutor(TerminalThreadFactory(applicationContext))
        val nsdManager = getSystemService(NsdManager::class.java)!!
        val queryInfo = NsdServiceInfo()
        queryInfo.serviceType = "_http._tcp"
        queryInfo.serviceName = "ttyd"
        var resolvedInfo = CompletableFuture<NsdServiceInfo>()

        nsdManager.registerServiceInfoCallback(
            queryInfo,
            executor,
            object : NsdManager.ServiceInfoCallback {
                var found: Boolean = false

                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}

                override fun onServiceInfoCallbackUnregistered() {
                    executor.shutdown()
                }

                override fun onServiceLost() {}

                override fun onServiceUpdated(info: NsdServiceInfo) {
                    Log.i(TAG, "Service found: $info")
                    val hasUsableAddress = info.hostAddresses.any { !it.isLinkLocalAddress }

                    if (!hasUsableAddress) {
                        Log.d(TAG, "Global ip addr isn't found, wait more: $info")
                        return
                    }

                    if (!found) {
                        found = true
                        nsdManager.unregisterServiceInfoCallback(this)
                        resolvedInfo.complete(info)
                    }
                }
            },
        )

        resolvedInfo.orTimeout(timeout_secs.toLong(), TimeUnit.SECONDS)
        return resolvedInfo
    }

    private fun createNotificationForTerminalClose(): Notification {
        val stopIntent = Intent()
        stopIntent.setClass(this, VmLauncherService::class.java)
        stopIntent.setAction(ACTION_SHUTDOWN_VM)
        val stopPendingIntent =
            PendingIntent.getService(
                this,
                0,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val icon = Icon.createWithResource(resources, R.drawable.ic_launcher_foreground)
        val stopActionText: String? = resources.getString(R.string.notif_action_force_close)
        val stopNotificationTitle: String? = resources.getString(R.string.notif_title_closing)
        return Notification.Builder(this, Application.CHANNEL_SYSTEM_EVENTS_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(stopNotificationTitle)
            .setOngoing(true)
            .setSilent(true)
            .addAction(Notification.Action.Builder(icon, stopActionText, stopPendingIntent).build())
            .build()
    }

    private fun runOnMainThread(r: Runnable) {
        handler!!.post(r)
    }

    private fun overrideConfigIfNecessary(
        builder: VirtualMachineCustomImageConfig.Builder,
        displayInfo: DisplayInfo,
    ): Boolean {
        var changed = false
        if (GraphicsManager.getInstance(this).isGfxstreamEnabled()) {
            builder.addParam("gfxstream_enabled")
            builder.setGpuConfig(
                VirtualMachineCustomImageConfig.GpuConfig.Builder()
                    .setBackend("gfxstream")
                    .setRendererUseEgl(false)
                    .setRendererUseGles(false)
                    .setRendererUseGlx(false)
                    .setRendererUseSurfaceless(true)
                    .setRendererUseVulkan(true)
                    .setContextTypes(arrayOf<String>("gfxstream-vulkan", "gfxstream-composer"))
                    // TODO(b/492372616): check if we need to set these features for kernel 6.12
                    .setRendererFeatures(
                        "VulkanDisableCoherentMemoryAndEmulate:enabled;VulkanAllocateHostVisibleAsUdmabuf:enabled;ExternalBlob:enabled"
                    )
                    .build()
            )
            changed = true
        }

        // Set the initial display size
        // TODO(jeongik): set up the display size on demand
        builder
            .setDisplayConfig(
                VirtualMachineCustomImageConfig.DisplayConfig.Builder()
                    .setWidth(displayInfo.width)
                    .setHeight(displayInfo.height)
                    .setHorizontalDpi(displayInfo.dpi)
                    .setVerticalDpi(displayInfo.dpi)
                    .setRefreshRate(displayInfo.refreshRate)
                    .build()
            )
            .useKeyboard(true)
            .useMouse(true)
            .useTouch(true)
        changed = true

        val image = InstalledImage.getDefault(this)
        if (image.hasBackup()) {
            val backup = image.backupFile
            builder.addDisk(VirtualMachineCustomImageConfig.Disk.RWDisk(backup.toString()))
            changed = true
        }
        return changed
    }

    private fun startDebianServer(): Int {
        try {
            // TODO(b/372666638): gRPC for java doesn't support vsock for now.
            val port = 0
            debianService = DebianServiceGrpc(this)
            server =
                OkHttpServerBuilder.forPort(port, InsecureServerCredentials.create())
                    .addService(debianService as DebianServiceImplBase)
                    .build()
                    .start()
        } catch (e: IOException) {
            Log.d(TAG, "grpc server error", e)
            throw RuntimeException("cannot start grpc server", e)
        }

        return server!!.port
    }

    @WorkerThread
    private fun doShutdown(resultReceiver: ResultReceiver?) {
        runner?.run {
            // This prevents MainActivity from re-starting VmLauncherService to shut the VM down.
            shutdownStarted.complete(null)
            exitStatus.thenAcceptAsync { success: Boolean ->
                resultReceiver?.send(if (success) RESULT_STOP else RESULT_ERROR, null)
            }
        }
        if (debianService != null && debianService!!.shutdownDebian()) {
            // During shutdown, change the notification content to indicate that it's closing
            val notification = createNotificationForTerminalClose()
            getSystemService(NotificationManager::class.java)!!.notify(
                this.hashCode(),
                notification,
            )

            runner?.also { r ->
                // For the case that shutdown from the guest agent fails.
                // When timeout is set, the original CompletableFuture's every `thenAcceptAsync` is
                // canceled as well. So add empty `thenAcceptAsync` to avoid interference.
                r.exitStatus
                    .thenAcceptAsync {}
                    .orTimeout(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally {
                        Log.e(
                            TAG,
                            "Stop the service directly because the VM instance isn't stopped with " +
                                "graceful shutdown",
                        )
                        r.vm.stop()
                        null
                    }
            }
            runner = null
        } else {
            // If there is no Debian service or it fails to shutdown, just stop the service.
            runner?.vm?.stop()
        }
    }

    private fun stopDebianServer() {
        debianService?.stop()
        server?.shutdown()
    }

    override fun onDestroy() {
        handler = null
        mainWorkerThread.execute({
            if (runner?.vm?.getStatus() == VirtualMachine.STATUS_RUNNING) {
                // TODO(b/431755357): Ensure VM is shutdown before being destroyed.
                doShutdown(null)
            }
        })
        portNotifier?.stop()
        getSystemService(NotificationManager::class.java)!!.cancelAll()
        stopDebianServer()
        bgThreads.shutdownNow()
        mainWorkerThread.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START_VM: String = PREFIX + "ACTION_START_VM"
        private const val EXTRA_NOTIFICATION = PREFIX + "EXTRA_NOTIFICATION"
        private const val EXTRA_DISPLAY_INFO = PREFIX + "EXTRA_DISPLAY_INFO"

        private const val ACTION_SHUTDOWN_VM: String = PREFIX + "ACTION_SHUTDOWN_VM"

        private const val RESULT_START = 0
        private const val RESULT_STOP = 1
        private const val RESULT_ERROR = 2
        private const val RESULT_TERMINAL_AVAIL = 3
        private const val RESULT_SHUTTING_DOWN = 4

        private const val KEY_TERMINAL_IPADDRESS = "address"
        private const val KEY_TERMINAL_PORT = "port"
        private const val KEY_TERMINAL_KEY = "terminal_key"

        private const val SHUTDOWN_TIMEOUT_SECONDS = 3L

        private const val GUEST_SPARSE_DISK_SIZE_PERCENTAGE = 95

        private val IS_EMULATOR: Boolean =
            {
                val deviceName = SystemProperties.get("ro.product.vendor.device", "")
                val cuttlefish = deviceName.startsWith("vsoc_")
                val goldfish = deviceName.startsWith("emu64")

                cuttlefish || goldfish
            }()

        private fun prepareIntent(context: Context, callback: VmLauncherServiceCallback): Intent {
            val intent = Intent(context.getApplicationContext(), VmLauncherService::class.java)
            val resultReceiver =
                object : ResultReceiver(Handler(Looper.myLooper()!!)) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                        when (resultCode) {
                            RESULT_START -> callback.onVmStart()
                            RESULT_TERMINAL_AVAIL -> {
                                Log.i(TAG, "received result RESULT_TERMINAL_AVAIL")
                                val ipAddress = resultData!!.getString(KEY_TERMINAL_IPADDRESS)
                                val port = resultData!!.getInt(KEY_TERMINAL_PORT)
                                val key = resultData!!.getString(KEY_TERMINAL_KEY)
                                callback.onTerminalAvailable(TerminalInfo(ipAddress!!, port, key))
                            }
                            RESULT_SHUTTING_DOWN -> callback.onVmShuttingDown()
                            RESULT_STOP -> callback.onVmStop()
                            RESULT_ERROR -> callback.onVmError()
                            else -> Log.e(TAG, "unknown result code: " + resultCode)
                        }
                    }
                }

            val parcel = Parcel.obtain()
            resultReceiver.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            intent.putExtra(
                Intent.EXTRA_RESULT_RECEIVER,
                ResultReceiver.CREATOR.createFromParcel(parcel).also { parcel.recycle() },
            )
            return intent
        }

        fun getIntentForStart(
            context: Context,
            callback: VmLauncherServiceCallback,
            notification: Notification?,
            displayInfo: DisplayInfo,
        ): Intent {
            val i = prepareIntent(context, callback)
            i.setAction(ACTION_START_VM)
            i.putExtra(EXTRA_NOTIFICATION, notification)
            i.putExtra(EXTRA_DISPLAY_INFO, displayInfo)
            return i
        }

        fun getIntentForShutdown(context: Context, callback: VmLauncherServiceCallback): Intent {
            val i = prepareIntent(context, callback)
            i.setAction(ACTION_SHUTDOWN_VM)
            return i
        }
    }
}

data class TerminalInfo(val ipAddress: String, val port: Int, val key: String?)

data class DisplayInfo(val width: Int, val height: Int, val dpi: Int, val refreshRate: Int) :
    Parcelable {
    constructor(
        source: Parcel
    ) : this(source.readInt(), source.readInt(), source.readInt(), source.readInt())

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(width)
        dest.writeInt(height)
        dest.writeInt(dpi)
        dest.writeInt(refreshRate)
    }

    companion object {
        @JvmField
        val CREATOR =
            object : Parcelable.Creator<DisplayInfo> {
                override fun createFromParcel(source: Parcel): DisplayInfo = DisplayInfo(source)

                override fun newArray(size: Int) = arrayOfNulls<DisplayInfo>(size)
            }
    }
}
