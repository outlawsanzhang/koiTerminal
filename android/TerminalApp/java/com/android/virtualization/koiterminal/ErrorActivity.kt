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

import android.annotation.MainThread
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.annotation.WorkerThread
import androidx.core.content.FileProvider
import com.android.virtualization.terminal.InstalledImage.Companion.getDefault
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.Exception
import java.lang.RuntimeException
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Activity when error happens.
 *
 * <p>
 * This runs in dedicated process configured in AndroidManifest.xml
 */
class ErrorActivity : BaseActivity() {
    private val bugReport: AtomicReference<BugReport?> = AtomicReference(null)

    private var launchingNewActivity: Boolean = false
    private var mainWorkerThread: ExecutorService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_error)

        val recovery = findViewById<View>(R.id.recovery)
        recovery.setOnClickListener(View.OnClickListener { _ -> launchRecoveryActivity() })

        val cause = findViewById<TextView>(R.id.cause)
        cause.text = getError()?.let { getString(R.string.error_code, getStackTrace(it)) }

        val report = findViewById<View>(R.id.bugreport)
        val reportIntent = Intent(BUGREPORT_ACTION)
        if (getPackageManager().resolveActivity(reportIntent, /* flags= */ 0) != null) {
            report.visibility = View.VISIBLE
            report.setOnClickListener { _ -> launchBetterBugActivity() }
        } else {
            report.visibility = View.GONE
        }

        findViewById<TextView>(R.id.cause).setMovementMethod(ScrollingMovementMethod())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    fun getError(): Exception? {
        val intent = getIntent()
        val e = intent.getParcelableExtra<Exception?>(EXTRA_CAUSE, Exception::class.java)
        return e
    }

    override fun onStop() {
        super.onStop()

        if (launchingNewActivity) {
            launchingNewActivity = false
            return
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isInteractive) {
            // If user is not launching a new activity but actively moving away from
            // error activity, finish immediately here.
            // It would provide convenient way to restart without swiping the task.
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        mainWorkerThread?.shutdownNow()
    }

    @MainThread
    private fun launchBetterBugActivityInternal() {
        val bugReport = this.bugReport.get()
        if (bugReport == null) {
            Log.w(TAG, "Internal error in ErrorActivity. Continue reporting anyway")
        }
        val reportIntent = Intent(BUGREPORT_ACTION)
        reportIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        reportIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        reportIntent.putExtra(BUGREPORT_EXTRA_DEEP_LINK, true)
        reportIntent.putExtra(BUGREPORT_EXTRA_TITLE, "Crash in TerminalApp (${bugReport?.error})")
        reportIntent.putExtra(BUGREPORT_EXTRA_TARGET_PACKAGE, getPackageName())
        reportIntent.putExtra(BUGREPORT_EXTRA_DELETE_ATTACHMENTS, true)
        reportIntent.putExtra(BUGREPORT_EXTRA_COMPONENT_ID, FERROCHROME_BUG_COMPONENT_ID)
        reportIntent.putExtra(
            BUGREPORT_EXTRA_ADDITIONAL_COMMENT,
            "Build id: ${bugReport?.buildId}\n",
        )
        reportIntent.setData(bugReport?.logZipContentUri)

        launchingNewActivity = true
        startActivity(reportIntent)
    }

    @WorkerThread
    private fun tryZipLogs(): Uri? {
        // Directly sharing file Uri is no longer supported.
        // Need to convert it with content Uri via FileProvider.
        val dir = getFileStreamPath(LOG_ZIP_DIR).toPath()
        try {
            Files.createDirectories(dir)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create shareable directory. Skip attaching VM logs", e)
            return null
        }

        val logZipFilePath = dir.resolve(LocalDateTime.now().toString() + ".vm_logs.zip")
        try {
            Logger.zipLogs(this, logZipFilePath)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to zip logs. Skip attaching VM logs", e)
            return null
        }

        return FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, logZipFilePath.toFile())
    }

    @WorkerThread
    private fun collectBugReport() {
        val buildId = getDefault(this).buildId
        val logZipContentUri = tryZipLogs()

        bugReport.set(BugReport(getError(), buildId, logZipContentUri))
    }

    @MainThread
    private fun launchBetterBugActivity() {
        if (mainWorkerThread != null) {
            Log.w(TAG, "Bugreport is in progress. Skipping multiple runs")
            return
        }

        val bugReport = this.bugReport.get()
        if (bugReport != null) {
            launchBetterBugActivityInternal()
        } else {
            // Do not use TerminalThreadFactory to avoid infinite ErrorActivity launches
            mainWorkerThread = Executors.newSingleThreadExecutor()
            mainWorkerThread?.execute({
                collectBugReport()
                runOnUiThread({
                    mainWorkerThread = null
                    launchBetterBugActivityInternal()
                })
            })
        }
    }

    private fun launchRecoveryActivity() {
        launchingNewActivity = true

        val intent = Intent(this, SettingsRecoveryActivity::class.java)
        startActivity(intent)
    }

    class BugReport(val error: Exception?, val buildId: String, val logZipContentUri: Uri?)

    companion object {
        private const val TAG = "TerminalError"

        private const val EXTRA_CAUSE = "cause"

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

        fun start(context: Context, e: Exception) {
            val intent = Intent(context, ErrorActivity::class.java)
            intent.putExtra(EXTRA_CAUSE, e)

            // Prevent go-back to resume MainActivity
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        private fun getStackTrace(e: Exception): String? {
            try {
                StringWriter().use { sWriter ->
                    PrintWriter(sWriter).use { pWriter ->
                        e.printStackTrace(pWriter)
                        return sWriter.toString()
                    }
                }
            } catch (ex: IOException) {
                // This shall never happen
                throw RuntimeException(ex)
            }
        }
    }
}
