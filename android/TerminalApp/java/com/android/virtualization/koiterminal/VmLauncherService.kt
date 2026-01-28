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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import android.os.StatFs
import android.os.SystemProperties
import android.system.virtualmachine.VirtualMachine
import android.system.virtualmachine.VirtualMachineConfig
import android.system.virtualmachine.VirtualMachineCustomImageConfig
import android.system.virtualmachine.VirtualMachineCustomImageConfig.AudioConfig
import android.system.virtualmachine.VirtualMachineException
import android.util.Log
import androidx.annotation.WorkerThread
import com.android.system.virtualmachine.flags.Flags
import com.android.virtualization.koiterminal.InstalledImage.Companion.roundUp
import com.android.virtualization.koiterminal.MainActivity.Companion.PREFIX
import com.android.virtualization.koiterminal.MainActivity.Companion.TAG
import io.grpc.Grpc
import io.grpc.InsecureServerCredentials
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.grpc.okhttp.OkHttpServerBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import com.android.virtualization.koiterminal.SerialIOManager

class VmLauncherService : Service() {
    // Thread pool
    private lateinit var bgThreads: ExecutorService
    // Single thread
    private lateinit var mainWorkerThread: ExecutorService
    private lateinit var image: InstalledImage

    // TODO: using lateinit for some fields to avoid null
    private var virtualMachine: VirtualMachine? = null
    val vm get() = virtualMachine
    private var serialSplitter: SerialIOManager? = null
    private var server: Server? = null
    private var debianService: DebianServiceImpl? = null
    private var portNotifier: PortNotifier? = null
    private var runner: Runner? = null
    private var handler: Handler? = null

    interface VmLauncherServiceCallback {
        fun onVmStart()

        fun onTerminalAvailable(info: TerminalInfo)

        fun onSerialAvailable(outReadingPfd: ParcelFileDescriptor, inWritingPfd: ParcelFileDescriptor)

        fun onVmShuttingDown()

        fun onVmStop()

        fun onVmError()
        
        fun onTtydTimeout()
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

                // Note: this doesn't always do the resizing. If the current image size is the same
                // as the requested size which is rounded up to the page alignment, resizing is not
                // done.
                val diskSize = intent.getLongExtra(EXTRA_DISK_SIZE, image.getApparentSize())

                mainWorkerThread.execute({ doStart(displayInfo, diskSize, resultReceiver!!) })

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
        // With storage ballooning enabled, we create a sparse file with 95% of the total size.
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

    // Convert the rootfs disk to a non-sparse file.
    private fun convertToNonSparseDiskIfNecessary(image: InstalledImage) {
        try {
            val curApparentSize = image.getApparentSize()
            val curPhysicalSize = image.getPhysicalSize()
            Log.d(TAG, "Current disk size: apparent=$curApparentSize, physical=$curPhysicalSize")

            // If storage ballooning was enabled via Flags.terminalStorageBalloon() before but it's
            // now disabled, the disk is still a sparse file whose apparent size is too large.
            // We need to shrink it to the minimum size.
            //
            // The disk file is considered sparse if its apparent disk size matches the expected
            // sparse disk size.
            // In addition, we consider it sparse if the physical size is clearly smaller than its
            // apparent size. This additional condition is a fallback for cases
            // where the logic of calculating the expected sparse disk size since the disk is
            // created.
            if (
                curApparentSize == calculateSparseDiskSize() ||
                    curPhysicalSize <
                        curApparentSize * EXPECTED_PHYSICAL_SIZE_PERCENTAGE_FOR_NON_SPARSE / 100
            ) {
                Log.d(TAG, "A sparse disk is detected. Shrink it to the minimum size.")
                val newSize = image.shrinkToMinimumSize()
                Log.d(TAG, "Shrink the disk image: $curApparentSize -> $newSize")
            }
        } catch (e: IOException) {
            throw RuntimeException("Failed to shrink rootfs disk", e)
            return
        }
    }

    @WorkerThread
    private fun doStart(displayInfo: DisplayInfo, diskSize: Long, resultReceiver: ResultReceiver) {
        val image = InstalledImage.getDefault(this)
        val json = ConfigJson.from(this, image.configPath)
        val configBuilder = json.toConfigBuilder(this)
        val customImageConfigBuilder = json.toCustomImageConfigBuilder(this)
        val timeout_secs = json.getBootTimeoutSecs() * (if (IS_EMULATOR) 5 else 1)

        if (Flags.terminalStorageBalloon()) {
            // When storage ballooning flag is enabled, convert rootfs disk into a sparse file.
            truncateDiskIfNecessary(image)
        } else {
            // Convert rootfs disk into a sparse file if storage ballooning flag had been enabled
            // and then disabled.
            convertToNonSparseDiskIfNecessary(image)

            // Note: this doesn't always do the resizing. If the current image size is the same as
            // the requested size which is rounded up to the page alignment, resizing is not done.
            image.resize(diskSize)
        }

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
        this.virtualMachine = virtualMachine
        val mbc = MemBalloonController(this, virtualMachine)
        mbc.start()

        runner!!.shutdownStarted.thenAcceptAsync { resultReceiver.send(RESULT_SHUTTING_DOWN, null) }
        runner!!.exitStatus.thenAcceptAsync { success: Boolean ->
            mbc.stop()
            resultReceiver.send(if (success) RESULT_STOP else RESULT_ERROR, null)
            serialSplitter?.flagForShutdown()
            stopSelf()
        }
        serialSplitter = Logger.setup(this, virtualMachine, bgThreads)

        resultReceiver.send(RESULT_START, null) // this tells MainActivity.kt that VM has started.
        if (serialSplitter != null) {
            val bundle = Bundle()
            bundle.putParcelable(KEY_TERMINAL_INWRITINGPFD, serialSplitter!!.getSerialInPfd())
            bundle.putParcelable(KEY_TERMINAL_OUTREADINGPFD, serialSplitter!!.newOutSplitPipe())
            resultReceiver.send(RESULT_SERIAL_AVAIL, bundle) // this tells MainActivity.kt the serial console in and out PFDs.
        }

        portNotifier = PortNotifier(this)

        getTerminalServiceInfo(timeout_secs)
            .thenAcceptAsync(
                { info ->
                    // It must exist because it is checked in `getTerminalServiceInfo`
                    Log.i("$TAG-getTerminalServiceInfo()", "thenAcceptAsync")
                    val ipAddress =
                        info.hostAddresses.firstOrNull { !it.isLinkLocalAddress }!!.hostAddress
                    val port = info.port
                    Log.i("$TAG-getTerminalServiceInfo().thenAcceptAsync", "ip = $ipAddress, port = $port")
                    val bundle = Bundle()
                    bundle.putString(KEY_TERMINAL_IPADDRESS, ipAddress)
                    bundle.putInt(KEY_TERMINAL_PORT, port)
                    Log.i("$TAG-getTerminalServiceInfo().thenAcceptAsync", "Sending result RESULT_TERMINAL_AVAIL")
                    resultReceiver.send(RESULT_TERMINAL_AVAIL, bundle)
                    Log.i("$TAG-getTerminalServiceInfo().thenAcceptAsync", "Running startDebianServer()")
                    startDebianServer(ipAddress)
                    Log.i("$TAG-getTerminalServiceInfo().thenAcceptAsync", "Finished running startDebianServer()")
                },
                bgThreads,
            )
            .exceptionallyAsync(
                { e ->
                    if (e.cause is TimeoutException) {
                        resultReceiver.send(RESULT_TTYD_TIMEOUT, null)
                    } else {
                        Log.e("$TAG-VmLauncherService", "Failed to start VM, caused by ${e.cause}", e)
                        resultReceiver.send(RESULT_ERROR, null)
                        stopSelf()
                    }
                    null
                },
                bgThreads,
            )
    }

    private fun getTerminalServiceInfo(timeout_secs: Int): CompletableFuture<NsdServiceInfo> {
        val executor = Executors.newSingleThreadExecutor(TerminalThreadFactory(applicationContext))
        val nsdManager = getSystemService<NsdManager?>(NsdManager::class.java)
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

        if (timeout_secs > 0) {
            resolvedInfo.orTimeout(timeout_secs.toLong(), TimeUnit.SECONDS)
        }
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
        val stopActionText: String? =
            resources.getString(R.string.service_notification_force_quit_action)
        val stopNotificationTitle: String? =
            resources.getString(R.string.service_notification_close_title)
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

    private fun isGfxstreamEnabled(): Boolean {
        if (
            Build.isDebuggable() &&
                Files.exists(ImageArchive.getSdcardPathForTesting().resolve("gfxstream"))
        ) {
            return true
        }
        return GraphicsManager.getInstance(this).accelerationType ==
            GraphicsManager.AccelerationType.Gfxstream
    }

    private fun overrideConfigIfNecessary(
        builder: VirtualMachineCustomImageConfig.Builder,
        displayInfo: DisplayInfo?,
    ): Boolean {
        var changed = false
        // TODO: use resources to check if gfxstream is supported.
        if (isGfxstreamEnabled()) {
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
                    .setRendererFeatures("VulkanDisableCoherentMemoryAndEmulate:enabled")
                    .build()
            )
            changed = true
        }

        // Set the initial display size
        // TODO(jeongik): set up the display size on demand
        if (Flags.terminalGuiSupport() && displayInfo != null) {
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
        }

        val image = InstalledImage.getDefault(this)
        if (image.hasBackup()) {
            val backup = image.backupFile
            builder.addDisk(VirtualMachineCustomImageConfig.Disk.RWDisk(backup.toString()))
            changed = true
        }
        return changed
    }

    private fun startDebianServer(ipAddress: String?) {
        val interceptor: ServerInterceptor =
            object : ServerInterceptor {
                override fun <ReqT, RespT> interceptCall(
                    call: ServerCall<ReqT?, RespT?>,
                    headers: Metadata?,
                    next: ServerCallHandler<ReqT?, RespT?>,
                ): ServerCall.Listener<ReqT?>? {
                    val remoteAddr =
                        call.attributes.get<SocketAddress?>(Grpc.TRANSPORT_ATTR_REMOTE_ADDR)
                            as InetSocketAddress?

                    if (remoteAddr?.address?.hostAddress == ipAddress) {
                        // Allow the request only if it is from VM
                        return next.startCall(call, headers)
                    }
                    Log.d(TAG, "blocked grpc request from $remoteAddr")
                    call.close(Status.Code.PERMISSION_DENIED.toStatus(), Metadata())
                    return object : ServerCall.Listener<ReqT?>() {}
                }
            }
        try {
            // TODO(b/372666638): gRPC for java doesn't support vsock for now.
            val port = 0
            debianService = DebianServiceImpl(this)
            server =
                OkHttpServerBuilder.forPort(port, InsecureServerCredentials.create())
                    .intercept(interceptor)
                    .addService(debianService)
                    .build()
                    .start()
        } catch (e: IOException) {
            Log.d(TAG, "grpc server error", e)
            return
        }

        bgThreads.execute(
            Runnable {
                // TODO(b/373533555): we can use mDNS for that.
                val debianServicePortFile = File(filesDir, "debian_service_port")
                try {
                    FileOutputStream(debianServicePortFile).use { writer ->
                        writer.write(server!!.port.toString().toByteArray())
                    }
                } catch (e: IOException) {
                    Log.d(TAG, "cannot write grpc port number", e)
                }
            }
        )

        if (Flags.terminalStorageBalloon()) {
            StorageBalloonWorker.start(this, debianService!!)
        }
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
            getSystemService<NotificationManager?>(NotificationManager::class.java)
                .notify(this.hashCode(), notification)

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
        debianService?.killForwarderHost()
        debianService?.closeStorageBalloonRequestQueue()
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
        getSystemService<NotificationManager?>(NotificationManager::class.java).cancelAll()
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
        private const val EXTRA_DISK_SIZE = PREFIX + "EXTRA_DISK_SIZE"

        private const val ACTION_SHUTDOWN_VM: String = PREFIX + "ACTION_SHUTDOWN_VM"

        private const val RESULT_START = 0
        private const val RESULT_STOP = 1
        private const val RESULT_ERROR = 2
        private const val RESULT_TERMINAL_AVAIL = 3
        private const val RESULT_SHUTTING_DOWN = 4
        private const val RESULT_SERIAL_AVAIL = 10
        private const val RESULT_TTYD_TIMEOUT = 11

        private const val KEY_TERMINAL_IPADDRESS = "address"
        private const val KEY_TERMINAL_PORT = "port"
        private const val KEY_TERMINAL_INWRITINGPFD = "serial_in_writing_pfd"
        private const val KEY_TERMINAL_OUTREADINGPFD = "serial_out_reading_pfd"

        private const val SHUTDOWN_TIMEOUT_SECONDS = 3L

        private const val GUEST_SPARSE_DISK_SIZE_PERCENTAGE = 95
        private const val EXPECTED_PHYSICAL_SIZE_PERCENTAGE_FOR_NON_SPARSE = 90

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
                            RESULT_SERIAL_AVAIL -> {
                                val inWritingPfd = resultData!!.getParcelable(KEY_TERMINAL_INWRITINGPFD, ParcelFileDescriptor::class.java)!!
                                val outReadingPfd = resultData!!.getParcelable(KEY_TERMINAL_OUTREADINGPFD, ParcelFileDescriptor::class.java)!!
                                callback.onSerialAvailable(outReadingPfd, inWritingPfd)
                            }
                            RESULT_TERMINAL_AVAIL -> {
                                Log.i(TAG, "received result RESULT_TERMINAL_AVAIL")
                                val ipAddress = resultData!!.getString(KEY_TERMINAL_IPADDRESS)
                                val port = resultData!!.getInt(KEY_TERMINAL_PORT)
                                callback.onTerminalAvailable(TerminalInfo(ipAddress!!, port))
                            }
                            RESULT_SHUTTING_DOWN -> callback.onVmShuttingDown()
                            RESULT_STOP -> callback.onVmStop()
                            RESULT_ERROR -> callback.onVmError()
                            RESULT_TTYD_TIMEOUT -> callback.onTtydTimeout()
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
            diskSize: Long?,
        ): Intent {
            val i = prepareIntent(context, callback)
            i.setAction(ACTION_START_VM)
            i.putExtra(EXTRA_NOTIFICATION, notification)
            i.putExtra(EXTRA_DISPLAY_INFO, displayInfo)
            if (diskSize != null) {
                i.putExtra(EXTRA_DISK_SIZE, diskSize)
            }
            return i
        }

        fun getIntentForShutdown(context: Context, callback: VmLauncherServiceCallback): Intent {
            val i = prepareIntent(context, callback)
            i.setAction(ACTION_SHUTDOWN_VM)
            return i
        }
    }
}

data class TerminalInfo(val ipAddress: String, val port: Int)

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
