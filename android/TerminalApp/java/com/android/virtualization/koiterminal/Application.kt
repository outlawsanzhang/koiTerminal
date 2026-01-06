/*
 * Copyright 2025 The Android Open Source Project
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

import android.app.Application as AndroidApplication
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

public class Application : AndroidApplication() {
    override fun onCreate() {
        super.onCreate()
        setupNotificationChannels()
    }

    private fun setupNotificationChannels() {
        val nm = getSystemService<NotificationManager>(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LONG_RUNNING_ID,
                getString(R.string.notification_channel_long_running_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYSTEM_EVENTS_ID,
                getString(R.string.notification_channel_system_events_name),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
    }

    companion object {
        const val CHANNEL_LONG_RUNNING_ID = "long_running"
        const val CHANNEL_SYSTEM_EVENTS_ID = "system_events"

        fun getInstance(c: Context): Application = c.getApplicationContext() as Application
    }
}
