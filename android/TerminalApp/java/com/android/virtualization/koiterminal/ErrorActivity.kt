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
import android.os.Bundle
import android.os.PowerManager
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.TextView
import com.android.virtualization.terminal.BetterBugLauncher.Companion.launchBetterBugActivity
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.Exception
import java.lang.RuntimeException

/**
 * Activity when error happens.
 *
 * <p>
 * This runs in dedicated process configured in AndroidManifest.xml
 */
class ErrorActivity : BaseActivity() {
    private var launchingNewActivity: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_error)

        val error = getError()
        if (error != null) {
            Log.e(TAG, "Unrecoverable error", error)
            val cause = findViewById<TextView>(R.id.cause)
            cause.text = getString(R.string.error_text_code, getStackTrace(error))
        }

        val recovery = findViewById<View>(R.id.recovery)
        recovery.setOnClickListener(View.OnClickListener { _ -> launchRecoveryActivity() })

        val report = findViewById<View>(R.id.bugreport)
        if (BetterBugLauncher.isBetterBugEnabled(this)) {
            report.visibility = View.VISIBLE
            report.setOnClickListener { _ -> launchBetterBugActivity(this, error) }
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

    override fun startActivity(intent: Intent) {
        launchingNewActivity = true
        super.startActivity(intent)
    }

    @MainThread
    private fun launchRecoveryActivity() {
        val intent = Intent(this, SettingsRecoveryActivity::class.java)
        startActivity(intent)
    }

    companion object {
        private const val TAG = "TerminalError"

        private const val EXTRA_CAUSE = "cause"

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
