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
package com.android.virtualization.terminal

import android.content.Context
import android.content.SharedPreferences

class GraphicsManager
private constructor(context: Context, private val sharedPref: SharedPreferences) {
    val isGfxstreamSupported = context.resources.getBoolean(R.bool.gfxstream_supported)
    val availableAccelerationTypes = run {
        if (isGfxstreamSupported) {
            GraphicsManager.AccelerationType.entries.toList()
        } else {
            GraphicsManager.AccelerationType.entries.filter {
                it != GraphicsManager.AccelerationType.Gfxstream
            }
        }
    }

    private val lock = Any()

    enum class AccelerationType(val descriptionId: Int) {
        Lavapipe(R.string.settings_graphics_renderer_software),
        Gfxstream(R.string.settings_graphics_renderer_gpu),
    }

    var accelerationType: AccelerationType
        get() =
            synchronized(lock) {
                val typeName = sharedPref.getString(ACCELERATION_TYPE_KEY, null)
                val defaultOption =
                    if (isGfxstreamSupported) AccelerationType.Gfxstream
                    else AccelerationType.Lavapipe
                return try {
                    if (typeName != null) AccelerationType.valueOf(typeName) else defaultOption
                } catch (e: IllegalArgumentException) {
                    defaultOption
                }
            }
        set(value) =
            synchronized(lock) {
                sharedPref.edit().putString(ACCELERATION_TYPE_KEY, value.name).apply()
            }

    fun isGfxstreamEnabled(): Boolean {
        if (
            android.os.Build.isDebuggable() &&
                java.nio.file.Files.exists(
                    ImageArchive.getSdcardPathForTesting().resolve("gfxstream")
                )
        ) {
            return true
        }
        return accelerationType == AccelerationType.Gfxstream
    }

    companion object {
        private const val PREFS_NAME = ".GRAPHICS"
        private const val ACCELERATION_TYPE_KEY = "acceleration_type"

        @Volatile private var instance: GraphicsManager? = null

        @Synchronized
        fun getInstance(context: Context): GraphicsManager {
            // Use double-checked locking for thread safety.
            return instance
                ?: synchronized(this) {
                    instance
                        ?: run {
                            val sharedPref =
                                context.getSharedPreferences(
                                    context.packageName + PREFS_NAME,
                                    Context.MODE_PRIVATE,
                                )
                            GraphicsManager(context, sharedPref).also { instance = it }
                        }
                }
        }
    }
}
