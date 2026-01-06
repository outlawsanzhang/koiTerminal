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

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.permission.flags.Flags
import androidx.appcompat.app.AppCompatActivity

abstract class BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (this !is ErrorActivity) {
            val currentThread = Thread.currentThread()
            if (currentThread.uncaughtExceptionHandler !is TerminalExceptionHandler) {
                currentThread.uncaughtExceptionHandler =
                    TerminalExceptionHandler(applicationContext)
            }
        }

        val permissionsToRequest = mutableListOf<String>()

        // Check for POST_NOTIFICATIONS permission
        if (
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Check for ACCESS_LOCAL_NETWORK permission if the flag is enabled
        if (Flags.accessLocalNetworkPermissionEnabled()) {
            if (
                applicationContext.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
                    PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
        }

        // Request all needed permissions at once
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissions(permissionsToRequest.toTypedArray(), APP_PERMISSIONS_REQUEST_CODE)
        }
    }

    companion object {
        private const val APP_PERMISSIONS_REQUEST_CODE = 101
    }
}
