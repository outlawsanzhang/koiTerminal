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

package com.android.virtualization.terminal.new2.util

import android.util.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalCoroutinesApi::class)
class LoggingMutableStateFlow<T>(
    private val originalFlow: MutableStateFlow<T>,
    private val tag: String,
) : MutableStateFlow<T> by originalFlow {

    override var value: T
        get() = originalFlow.value
        set(newValue) {
            if (originalFlow.value != newValue) {
                Log.d(tag, "State changed from ${originalFlow.value} to $newValue")
            }
            originalFlow.value = newValue
        }

    override suspend fun emit(value: T) {
        if (originalFlow.value != value) {
            Log.d(tag, "State changed from ${originalFlow.value} to $value")
        }
        originalFlow.emit(value)
    }

    override fun tryEmit(value: T): Boolean {
        if (originalFlow.value != value) {
            Log.d(tag, "State changed from ${originalFlow.value} to $value")
        }
        return originalFlow.tryEmit(value)
    }
}
