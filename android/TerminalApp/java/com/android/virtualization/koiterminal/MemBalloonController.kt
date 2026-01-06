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
import android.os.Handler
import android.os.Looper
import android.system.virtualmachine.VirtualMachine
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.android.virtualization.terminal.MainActivity.Companion.TAG
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * MemBalloonController is responsible for adjusting the memory ballon size of a VM depending on
 * whether the app is visible or running in the background
 */
class MemBalloonController(val context: Context, val vm: VirtualMachine) {
    companion object {
        private const val INITIAL_PERCENT = 10
        private const val MAX_PERCENT = 50
        private const val INFLATION_STEP_PERCENT = 5
        private const val INFLATION_PERIOD_SEC = 60L

        private val mainHandler = Handler(Looper.getMainLooper())

        private fun runOnMainThread(runnable: Runnable) {
            mainHandler.post(runnable)
        }
    }

    private val executor =
        Executors.newSingleThreadScheduledExecutor(
            TerminalThreadFactory(context.getApplicationContext())
        )

    private val observer =
        object : DefaultLifecycleObserver {

            // If the app is started or resumed, give deflate the balloon to 0 to give maximum
            // available memory to the virtual machine
            override fun onResume(owner: LifecycleOwner) {
                ongoingInflation?.cancel(false)
                executor.execute({
                    Log.v(TAG, "app resumed. deflating mem balloon to the minimum")
                    vm.setMemoryBalloonByPercent(0)
                })
            }

            // If the app goes into background, progressively inflate the balloon from
            // INITIAL_PERCENT until it reaches MAX_PERCENT
            override fun onStop(owner: LifecycleOwner) {
                ongoingInflation?.cancel(false)
                balloonPercent = INITIAL_PERCENT
                ongoingInflation =
                    executor.scheduleAtFixedRate(
                        {
                            if (balloonPercent <= MAX_PERCENT) {
                                Log.v(TAG, "inflating mem balloon to ${balloonPercent} %")
                                vm.setMemoryBalloonByPercent(balloonPercent)
                                balloonPercent += INFLATION_STEP_PERCENT
                            } else {
                                Log.v(TAG, "mem balloon is inflated to its max (${MAX_PERCENT} %)")
                                ongoingInflation!!.cancel(false)
                            }
                        },
                        0 /* initialDelay */,
                        INFLATION_PERIOD_SEC,
                        TimeUnit.SECONDS,
                    )
            }
        }

    private var balloonPercent = 0
    private var ongoingInflation: ScheduledFuture<*>? = null

    fun start() {
        // addObserver is @MainThread
        runOnMainThread({ ProcessLifecycleOwner.get().lifecycle.addObserver(observer) })
    }

    fun stop() {
        // removeObserver is @MainThread
        runOnMainThread({
            ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
            executor.shutdown()
        })
    }
}
