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

import android.annotation.MainThread
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.core.content.FileProvider
import com.android.virtualization.terminal.InstalledImage.Companion.getDefault
import java.lang.Exception
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Launches better bug with VM logs */
class BetterBugLauncher {
    private var mainWorkerThread: ExecutorService? = null

    @MainThread
    private fun launchBetterBugActivity(activity: Activity, error: Exception?): Boolean {
        if (mainWorkerThread != null) {
            Log.w(TAG, "Bugreport is in progress. Skipping multiple runs")
            return false
        }

        mainWorkerThread = Executors.newSingleThreadExecutor()
        mainWorkerThread!!.execute({
            val bugReport = collectBugReport(activity, error)
            activity.runOnUiThread({
                mainWorkerThread = null
                launchBetterBugActivityInternal(activity, bugReport)
            })
        })

        return true
    }

    @WorkerThread
    private fun tryZipLogs(context: Context): Uri? {
        // Directly sharing file Uri is no longer supported.
        // Need to convert it with content Uri via FileProvider.
        val dir = context.getFileStreamPath(LOG_ZIP_DIR).toPath()
        try {
            Files.createDirectories(dir)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create shareable directory. Skip attaching VM logs", e)
            return null
        }

        val logZipFilePath = dir.resolve(LocalDateTime.now().toString() + ".vm_logs.zip")
        try {
            Logger.zipLogs(context, logZipFilePath)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to zip logs. Skip attaching VM logs", e)
            return null
        }

        return FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, logZipFilePath.toFile())
    }

    @WorkerThread
    private fun collectBugReport(context: Context, error: Exception?): BugReport {
        val image = getDefault(context)
        val rawBuildId = image.buildInfo?.rawString ?: "<no build id>"
        val cidataBuildId = image.cidataBuildId ?: "<no build id>"
        val logZipContentUri = tryZipLogs(context)

        return BugReport(error, rawBuildId, cidataBuildId, logZipContentUri)
    }

    private fun launchBetterBugActivityInternal(context: Context, bugReport: BugReport) {
        val reportIntent = Intent(BUGREPORT_ACTION)
        reportIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        reportIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        reportIntent.putExtra(BUGREPORT_EXTRA_DEEP_LINK, true)
        reportIntent.putExtra(BUGREPORT_EXTRA_TARGET_PACKAGE, context.getPackageName())
        reportIntent.putExtra(BUGREPORT_EXTRA_DELETE_ATTACHMENTS, true)
        reportIntent.putExtra(BUGREPORT_EXTRA_COMPONENT_ID, FERROCHROME_BUG_COMPONENT_ID)
        reportIntent.putExtra(
            BUGREPORT_EXTRA_TITLE,
            if (bugReport.error != null) "Crash in TerminalApp (${bugReport.error})"
            else "TerminalApp manual bug report",
        )
        reportIntent.putExtra(
            BUGREPORT_EXTRA_ADDITIONAL_COMMENT,
            "Build id: ${bugReport.rawBuildId}\nCIDATA bid: ${bugReport.cidataBuildId}\n",
        )
        reportIntent.setData(bugReport.logZipContentUri)

        context.startActivity(reportIntent)
    }

    class BugReport(
        val error: Exception?,
        val rawBuildId: String,
        val cidataBuildId: String,
        val logZipContentUri: Uri?,
    )

    companion object {
        private const val TAG = "TerminalBugReport"

        // Defined in AndroidManifest.xml
        private const val FILE_PROVIDER_AUTHORITY =
            "com.android.virtualization.terminal.fileprovider"
        private const val LOG_ZIP_DIR = "bugreport"

        // From go/betterbug-integration
        private const val BUGREPORT_ACTION =
            "com.google.android.apps.betterbug.intent.FILE_BUG_DEEPLINK"
        private const val BUGREPORT_EXTRA_DEEP_LINK = "EXTRA_DEEPLINK"
        private const val BUGREPORT_EXTRA_TITLE = "EXTRA_ISSUE_TITLE"
        private const val BUGREPORT_EXTRA_TARGET_PACKAGE = "EXTRA_TARGET_PACKAGE"
        private const val BUGREPORT_EXTRA_COMPONENT_ID = "EXTRA_COMPONENT_ID"
        private const val BUGREPORT_EXTRA_REQUIRE_BUGREPORT = "EXTRA_REQUIRE_BUGREPORT"
        private const val BUGREPORT_EXTRA_DELETE_ATTACHMENTS = "EXTRA_DELETE_ATTACHMENTS"
        private const val BUGREPORT_EXTRA_ADDITIONAL_COMMENT = "EXTRA_ADDITIONAL_COMMENT"

        private const val FERROCHROME_BUG_COMPONENT_ID = 1517278

        private val betterBugLauncher: BetterBugLauncher = BetterBugLauncher()

        @MainThread
        public fun isBetterBugEnabled(context: Context): Boolean {
            val reportIntent = Intent(BUGREPORT_ACTION)

            return context.getPackageManager().resolveActivity(reportIntent, /* flags= */ 0) != null
        }

        @MainThread
        public fun launchBetterBugActivity(activity: Activity, error: Exception?) {
            betterBugLauncher.launchBetterBugActivity(activity, error)
        }
    }
}
