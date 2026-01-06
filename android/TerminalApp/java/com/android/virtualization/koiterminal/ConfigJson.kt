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
package com.android.virtualization.terminal

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.system.virtualmachine.VirtualMachineConfig
import android.system.virtualmachine.VirtualMachineCustomImageConfig
import android.util.DisplayMetrics
import android.view.WindowManager
import com.android.virtualization.terminal.ConfigJson.AudioJson
import com.android.virtualization.terminal.ConfigJson.DiskJson
import com.android.virtualization.terminal.ConfigJson.DisplayJson
import com.android.virtualization.terminal.ConfigJson.GpuJson
import com.android.virtualization.terminal.ConfigJson.InputJson
import com.android.virtualization.terminal.ConfigJson.PartitionJson
import com.android.virtualization.terminal.ConfigJson.SharedPathJson
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.BufferedReader
import java.io.FileReader
import java.io.IOException
import java.io.Reader
import java.lang.Exception
import java.lang.RuntimeException
import java.nio.file.Files
import java.nio.file.Path

/** This class and its inner classes model vm_config.json. */
internal data class ConfigJson(
    @SerializedName("protected") private val isProtected: Boolean,
    private val name: String?,
    private val cpu_topology: String?,
    private val platform_version: String?,
    private val memory_mib: Int = 1024,
    private val console_input_device: String?,
    private val bootloader: String?,
    private val kernel: String?,
    private val initrd: String?,
    private val params: String?,
    private val debuggable: Boolean,
    private val console_out: Boolean,
    private val connect_console: Boolean,
    private val network: Boolean,
    private val input: InputJson?,
    private val audio: AudioJson?,
    private val disks: Array<DiskJson>?,
    private val sharedPath: Array<SharedPathJson>?,
    private val display: DisplayJson?,
    private val gpu: GpuJson?,
    private val auto_memory_balloon: Boolean,
    private val boot_timeout_secs: Int?,
) {
    private fun getCpuTopology(): Int {
        return when (cpu_topology) {
            "one_cpu" -> VirtualMachineConfig.CPU_TOPOLOGY_ONE_CPU
            "match_host" -> VirtualMachineConfig.CPU_TOPOLOGY_MATCH_HOST
            else -> throw RuntimeException("invalid cpu topology: $cpu_topology")
        }
    }

    private fun getDebugLevel(): Int {
        return if (debuggable) VirtualMachineConfig.DEBUG_LEVEL_FULL
        else VirtualMachineConfig.DEBUG_LEVEL_NONE
    }

    internal fun getBootTimeoutSecs(): Int {
        return boot_timeout_secs ?: DEFAULT_BOOT_TIMEOUT
    }

    /** Converts this parsed JSON into VirtualMachineConfig Builder */
    fun toConfigBuilder(context: Context): VirtualMachineConfig.Builder {
        return VirtualMachineConfig.Builder(context)
            .setProtectedVm(isProtected)
            .setMemoryBytes(memory_mib.toLong() * 1024 * 1024)
            .setConsoleInputDevice(console_input_device)
            .setCpuTopology(getCpuTopology())
            .setCustomImageConfig(toCustomImageConfigBuilder(context).build())
            .setDebugLevel(getDebugLevel())
            .setVmOutputCaptured(console_out)
            .setConnectVmConsole(connect_console)
    }

    fun toCustomImageConfigBuilder(context: Context): VirtualMachineCustomImageConfig.Builder {
        val builder = VirtualMachineCustomImageConfig.Builder()

        builder
            .setName(name)
            .setOsName("debian")
            .setBootloaderPath(bootloader)
            .setKernelPath(kernel)
            .setInitrdPath(initrd)
            .useNetwork(network)
            .useAutoMemoryBalloon(auto_memory_balloon)

        if (input != null) {
            builder
                .useTouch(input.touchscreen)
                .useKeyboard(input.keyboard)
                .useMouse(input.mouse)
                .useTrackpad(input.trackpad)
                .useSwitches(input.switches)
        }

        if (audio != null) {
            builder.setAudioConfig(audio.toConfig())
        }

        if (display != null) {
            builder.setDisplayConfig(display.toConfig(context))
        }

        if (gpu != null) {
            builder.setGpuConfig(gpu.toConfig())
        }

        params?.split(" ".toRegex())?.filter { it.isNotEmpty() }?.forEach { builder.addParam(it) }

        disks?.forEach { builder.addDisk(it.toConfig()) }

        sharedPath?.mapNotNull { it.toConfig(context) }?.forEach { builder.addSharedPath(it) }

        return builder
    }

    internal data class SharedPathJson(private val sharedPath: String?) {
        fun toConfig(context: Context): VirtualMachineCustomImageConfig.SharedPath? {
            try {
                val terminalUid = getTerminalUid(context)
                if (sharedPath?.contains("emulated") == true) {
                    if (Environment.isExternalStorageManager()) {
                        val currentUserId = context.userId
                        val path = "$sharedPath/$currentUserId"
                        return VirtualMachineCustomImageConfig.SharedPath(
                            path,
                            terminalUid,
                            terminalUid,
                            GUEST_UID,
                            GUEST_GID,
                            7,
                            "android",
                            "android",
                            false, /* app domain is set to false so that crosvm is spin up as child of virtmgr */
                            "",
                        )
                    }
                    return null
                }
                val socketPath = context.getFilesDir().toPath().resolve("internal.virtiofs")
                Files.deleteIfExists(socketPath)
                return VirtualMachineCustomImageConfig.SharedPath(
                    sharedPath,
                    terminalUid,
                    terminalUid,
                    0,
                    0,
                    7,
                    "internal",
                    "internal",
                    true, /* app domain is set to true so that crosvm is spin up from app context */
                    socketPath.toString(),
                )
            } catch (e: PackageManager.NameNotFoundException) {
                return null
            } catch (e: IOException) {
                return null
            }
        }

        @Throws(PackageManager.NameNotFoundException::class)
        fun getTerminalUid(context: Context): Int {
            return context
                .getPackageManager()
                .getPackageUidAsUser(context.getPackageName(), context.userId)
        }

        companion object {
            private const val GUEST_UID = 1000
            private const val GUEST_GID = 100
        }
    }

    internal data class InputJson(
        val touchscreen: Boolean,
        val keyboard: Boolean,
        val mouse: Boolean,
        val switches: Boolean,
        val trackpad: Boolean,
    )

    internal data class AudioJson(private val microphone: Boolean, private val speaker: Boolean) {

        fun toConfig(): VirtualMachineCustomImageConfig.AudioConfig {
            return VirtualMachineCustomImageConfig.AudioConfig.Builder()
                .setUseMicrophone(microphone)
                .setUseSpeaker(speaker)
                .build()
        }
    }

    internal data class DiskJson(
        private val writable: Boolean,
        private val image: String?,
        private val partitions: Array<PartitionJson>?,
    ) {
        fun toConfig(): VirtualMachineCustomImageConfig.Disk {
            val d =
                if (writable) VirtualMachineCustomImageConfig.Disk.RWDisk(image)
                else VirtualMachineCustomImageConfig.Disk.RODisk(image)
            partitions?.forEach {
                val writable = this.writable && it.writable
                d.addPartition(
                    VirtualMachineCustomImageConfig.Partition(it.label, it.path, writable, it.guid)
                )
            }
            return d
        }
    }

    internal data class PartitionJson(
        val writable: Boolean,
        val label: String?,
        val path: String?,
        val guid: String?,
    )

    internal data class DisplayJson(
        private val scale: Float = 0f,
        private val refresh_rate: Int = 0,
        private val width_pixels: Int = 0,
        private val height_pixels: Int = 0,
    ) {
        fun toConfig(context: Context): VirtualMachineCustomImageConfig.DisplayConfig {
            val wm = context.getSystemService<WindowManager>(WindowManager::class.java)
            val metrics = wm.currentWindowMetrics
            val dispBounds = metrics.bounds

            val width = if (width_pixels > 0) width_pixels else dispBounds.right
            val height = if (height_pixels > 0) height_pixels else dispBounds.bottom

            var dpi = (DisplayMetrics.DENSITY_DEFAULT * metrics.density).toInt()
            if (scale > 0.0f) {
                dpi = (dpi * scale).toInt()
            }

            var refreshRate = context.display.refreshRate.toInt()
            if (this.refresh_rate != 0) {
                refreshRate = this.refresh_rate
            }

            return VirtualMachineCustomImageConfig.DisplayConfig.Builder()
                .setWidth(width)
                .setHeight(height)
                .setHorizontalDpi(dpi)
                .setVerticalDpi(dpi)
                .setRefreshRate(refreshRate)
                .build()
        }
    }

    internal data class GpuJson(
        private val backend: String?,
        private val pci_address: String?,
        private val renderer_features: String?,
        // TODO: GSON actaully ignores the default values
        private val renderer_use_egl: Boolean = true,
        private val renderer_use_gles: Boolean = true,
        private val renderer_use_glx: Boolean = false,
        private val renderer_use_surfaceless: Boolean = true,
        private val renderer_use_vulkan: Boolean = false,
        private val context_types: Array<String>?,
    ) {
        fun toConfig(): VirtualMachineCustomImageConfig.GpuConfig {
            return VirtualMachineCustomImageConfig.GpuConfig.Builder()
                .setBackend(backend)
                .setPciAddress(pci_address)
                .setRendererFeatures(renderer_features)
                .setRendererUseEgl(renderer_use_egl)
                .setRendererUseGles(renderer_use_gles)
                .setRendererUseGlx(renderer_use_glx)
                .setRendererUseSurfaceless(renderer_use_surfaceless)
                .setRendererUseVulkan(renderer_use_vulkan)
                .setContextTypes(context_types)
                .build()
        }
    }

    companion object {
        private const val DEBUG = true
        const val DEFAULT_BOOT_TIMEOUT: Int = 30

        /** Parses JSON file at jsonPath */
        fun from(context: Context, jsonPath: Path): ConfigJson {
            try {
                FileReader(jsonPath.toFile()).use { fileReader ->
                    val content = replaceKeywords(fileReader, context)
                    return Gson().fromJson<ConfigJson?>(content, ConfigJson::class.java)
                }
            } catch (e: Exception) {
                throw RuntimeException("Failed to parse $jsonPath", e)
            }
        }

        @Throws(IOException::class)
        private fun replaceKeywords(r: Reader, context: Context): String {
            val rules: Map<String, String> =
                mapOf(
                    "\\\$PAYLOAD_DIR" to InstalledImage.getDefault(context).installDir.toString(),
                    "\\\$USER_ID" to context.userId.toString(),
                    "\\\$PACKAGE_NAME" to context.getPackageName(),
                    "\\\$APP_DATA_DIR" to context.getDataDir().toString(),
                )

            return BufferedReader(r).useLines { lines ->
                lines
                    .map { line ->
                        rules.entries.fold(line) { acc, rule ->
                            acc.replace(rule.key.toRegex(), rule.value)
                        }
                    }
                    .joinToString("\n")
            }
        }
    }
}
