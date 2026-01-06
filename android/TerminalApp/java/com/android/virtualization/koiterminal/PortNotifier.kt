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

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import com.android.virtualization.terminal.MainActivity.Companion.TAG
import java.util.Locale

/**
 * PortNotifier is responsible for posting a notification when a new open port is detected. User can
 * enable or disable forwarding of the port in notification panel.
 */
internal class PortNotifier(val context: Context) {
    private val notificationManager: NotificationManager =
        context.getSystemService<NotificationManager>(NotificationManager::class.java)
    private val receiver: BroadcastReceiver =
        PortForwardingRequestReceiver().also {
            val intentFilter = IntentFilter(ACTION_PORT_FORWARDING)
            context.registerReceiver(it, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        }
    private val portsStateManager: PortsStateManager = PortsStateManager.getInstance(context)
    private val portsStateListener: PortsStateManager.Listener =
        object : PortsStateManager.Listener {
                override fun onPortsStateUpdated(
                    oldActivePorts: Set<Int>,
                    newActivePorts: Set<Int>,
                ) {
                    // added active ports
                    (newActivePorts - oldActivePorts - portsStateManager.getEnabledPorts())
                        .forEach { showNotificationFor(it) }
                    // removed active ports
                    (oldActivePorts - newActivePorts).forEach { discardNotificationFor(it) }
                }
            }
            .also { portsStateManager.registerListener(it) }

    fun stop() {
        portsStateManager.unregisterListener(portsStateListener)
        context.unregisterReceiver(receiver)
    }

    private fun getString(resId: Int): String {
        return context.getString(resId)
    }

    private fun getPendingIntentFor(port: Int, enabled: Boolean): PendingIntent {
        val intent = Intent(ACTION_PORT_FORWARDING)
        intent.setPackage(context.getPackageName())
        intent.setIdentifier(String.format(Locale.ROOT, "%d_%b", port, enabled))
        intent.putExtra(KEY_PORT, port)
        intent.putExtra(KEY_ENABLED, enabled)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun showNotificationFor(port: Int) {
        val tapIntent = Intent(context, SettingsPortForwardingActivity::class.java)
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tapPendingIntent =
            PendingIntent.getActivity(context, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE)

        val title = getString(R.string.settings_port_forwarding_notification_title)
        val content =
            context.getString(
                R.string.settings_port_forwarding_notification_content,
                port,
                portsStateManager.getActivePortInfo(port)?.comm,
            )
        val acceptText = getString(R.string.settings_port_forwarding_notification_accept)
        val denyText = getString(R.string.settings_port_forwarding_notification_deny)
        val icon = Icon.createWithResource(context, R.drawable.ic_launcher_foreground)

        val acceptAction: Notification.Action =
            Notification.Action.Builder(
                    icon,
                    acceptText,
                    getPendingIntentFor(port, true /* enabled */),
                )
                .build()
        val denyAction: Notification.Action =
            Notification.Action.Builder(
                    icon,
                    denyText,
                    getPendingIntentFor(port, false /* enabled */),
                )
                .build()
        val notification: Notification =
            Notification.Builder(context, Application.CHANNEL_SYSTEM_EVENTS_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(content)
                .setFullScreenIntent(tapPendingIntent, true)
                .addAction(acceptAction)
                .addAction(denyAction)
                .setAutoCancel(true)
                .build()
        notificationManager.notify(TAG, port, notification)
    }

    private fun discardNotificationFor(port: Int) {
        notificationManager.cancel(TAG, port)
    }

    private inner class PortForwardingRequestReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (ACTION_PORT_FORWARDING == intent.action) {
                performActionPortForwarding(intent)
            }
        }

        fun performActionPortForwarding(intent: Intent) {
            val port = intent.getIntExtra(KEY_PORT, 0)
            val enabled = intent.getBooleanExtra(KEY_ENABLED, false)
            portsStateManager.updateEnabledPort(port, enabled)
            discardNotificationFor(port)
        }
    }

    companion object {
        private const val ACTION_PORT_FORWARDING = "android.virtualization.PORT_FORWARDING"
        private const val KEY_PORT = "port"
        private const val KEY_ENABLED = "enabled"
    }
}
