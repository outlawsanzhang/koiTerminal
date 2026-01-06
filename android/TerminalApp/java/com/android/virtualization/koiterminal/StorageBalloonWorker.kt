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
import android.os.storage.StorageManager
import android.os.storage.StorageManager.UUID_DEFAULT
import android.util.Log
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.android.virtualization.terminal.MainActivity.Companion.TAG
import java.util.concurrent.TimeUnit

class StorageBalloonWorker(appContext: Context, workerParams: WorkerParameters) :
    Worker(appContext, workerParams) {

    override fun doWork(): Result {
        Log.d(TAG, "StorageBalloonWorker.doWork() called")

        var storageManager =
            applicationContext.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val hostAllocatableBytes = storageManager.getAllocatableBytes(UUID_DEFAULT)

        val guestAvailableBytes = calculateGuestAvailableStorageSize(hostAllocatableBytes)
        // debianService must be set when this function is called.
        debianService!!.setAvailableStorageBytes(guestAvailableBytes)

        val delaySeconds = calculateDelaySeconds(hostAllocatableBytes)
        scheduleNextTask(delaySeconds)

        return Result.success()
    }

    private fun calculateGuestAvailableStorageSize(hostAllocatableBytes: Long): Long {
        return (hostAllocatableBytes - HOST_RESERVED_BYTES).coerceAtLeast(0L)
    }

    private fun calculateDelaySeconds(hostAvailableBytes: Long): Long {
        return when {
            hostAvailableBytes < CRITICAL_STORAGE_THRESHOLD_BYTES -> CRITICAL_DELAY_SECONDS
            hostAvailableBytes < LOW_STORAGE_THRESHOLD_BYTES -> LOW_STORAGE_DELAY_SECONDS
            hostAvailableBytes < MODERATE_STORAGE_THRESHOLD_BYTES -> MODERATE_STORAGE_DELAY_SECONDS
            else -> NORMAL_DELAY_SECONDS
        }
    }

    private fun scheduleNextTask(delaySeconds: Long) {
        val storageBalloonTaskRequest =
            androidx.work.OneTimeWorkRequest.Builder(StorageBalloonWorker::class.java)
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .build()
        androidx.work.WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(
                "storageBalloonTask",
                androidx.work.ExistingWorkPolicy.REPLACE,
                storageBalloonTaskRequest,
            )
        Log.d(TAG, "next storage balloon task is scheduled in $delaySeconds seconds")
    }

    companion object {
        private var debianService: DebianServiceImpl? = null

        // Reserve 1GB as host-only region.
        private const val HOST_RESERVED_BYTES = 1024L * 1024 * 1024

        // Thresholds for deciding time period to report storage information to the guest.
        // Less storage is available on the host, more frequently the host will report storage
        // information to the guest.
        //
        // Critical: (host storage < 1GB) => report every 5 seconds
        private const val CRITICAL_STORAGE_THRESHOLD_BYTES = 1L * 1024 * 1024 * 1024
        private const val CRITICAL_DELAY_SECONDS = 5L
        // Low: (1GB <= storage < 5GB) => report every 10 seconds
        private const val LOW_STORAGE_THRESHOLD_BYTES = 5L * 1024 * 1024 * 1024
        private const val LOW_STORAGE_DELAY_SECONDS = 10L
        // Moderate: (5GB <= storage < 10GB) => report every 60 seconds
        private const val MODERATE_STORAGE_THRESHOLD_BYTES = 10L * 1024 * 1024 * 1024
        private const val MODERATE_STORAGE_DELAY_SECONDS = 60L
        // Normal: (10GB <= storage) report every 15 minutes
        private const val NORMAL_DELAY_SECONDS = 60L * 15

        internal fun start(ctx: Context, ds: DebianServiceImpl) {
            debianService = ds
            val storageBalloonTaskRequest =
                androidx.work.OneTimeWorkRequest.Builder(StorageBalloonWorker::class.java)
                    .setInitialDelay(1, TimeUnit.SECONDS)
                    .build()
            androidx.work.WorkManager.getInstance(ctx)
                .enqueueUniqueWork(
                    "storageBalloonTask",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    storageBalloonTaskRequest,
                )
        }
    }
}
