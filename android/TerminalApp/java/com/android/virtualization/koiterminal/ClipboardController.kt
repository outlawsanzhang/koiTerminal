/*
 * Copyright (C) 2026 The Android Open Source Project
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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.RemoteException
import android.util.Log
import com.android.virtualization.debian.aidl.IDebianService

class ClipboardController(private val context: Context, private val debianService: IDebianService) {
    private val clipboardManager = context.getSystemService(ClipboardManager::class.java)!!
    private var lastSyncedText: String? = null

    init {
        Log.d(TAG, "Initializing ClipboardController")
    }

    fun onDestroy() {
        Log.d(TAG, "Destroying ClipboardController")
    }

    fun pushToGuestIfNeeded() {
        val clip = clipboardManager.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()
            if (text != null && text != lastSyncedText) {
                lastSyncedText = text
                Log.d(TAG, "Pushing text to guest")
                try {
                    debianService.updateClipboard(text)
                } catch (e: RemoteException) {
                    Log.e(TAG, "Failed to update guest clipboard", e)
                }
            }
        }
    }

    fun pullFromGuest() {
        Log.d(TAG, "Pulling clipboard from guest")
        try {
            val text = debianService.readClipboard()
            Log.d(TAG, "Text from guest")
            if (text != null && text.isNotEmpty() && text != lastSyncedText) {
                lastSyncedText = text
                val clip = ClipData.newPlainText("Guest Clipboard", text)
                clipboardManager.setPrimaryClip(clip)
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to read guest clipboard", e)
        }
    }

    companion object {
        private const val TAG = "ClipboardController"
    }
}
