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

import android.content.Context
import android.util.Log
import java.lang.Exception

class TerminalExceptionHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val exception = (throwable as? Exception) ?: Exception(throwable)
        try {
            ErrorActivity.start(context, exception)
        } catch (_: Exception) {
            Log.wtf(TAG, "Failed to launch error activity for an exception", exception)
        }
        Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(thread, throwable)
    }

    companion object {
        private const val TAG = "TerminalExceptionHandler"
    }
}
