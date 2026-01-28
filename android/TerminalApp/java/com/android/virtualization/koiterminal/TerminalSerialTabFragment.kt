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
package com.android.virtualization.koiterminal

import android.annotation.IntDef
import android.annotation.MainThread
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.ClientCertRequest
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.android.system.virtualmachine.flags.Flags.terminalGuiSupport
import com.android.virtualization.koiterminal.CertificateUtils.createOrGetKey
import com.android.virtualization.koiterminal.CertificateUtils.writeCertificateToFile
import java.security.PrivateKey
import java.security.cert.X509Certificate
import com.termux.view.TerminalView
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient

class TerminalSerialTabFragment() : Fragment() {
    private lateinit var terminalView: TerminalView
    public fun getTerminalView(): TerminalView? {
        if (this::terminalView.isInitialized && this::terminalSession.isInitialized) {
            return terminalView
        } else {
            return null
        }
    }
    private var fontSizeInit: Float = 13.0f * FONT_SIZE_MULTIPLIER
    private var fontSizeNow: Int = 13
    private lateinit var terminalSession: TerminalSession
    private lateinit var bootProgressView: View
    private lateinit var id: String
    private val terminalViewModel: TerminalViewModel by activityViewModels()

    public fun attachSession(): Boolean {
        if (this::terminalView.isInitialized && this::terminalSession.isInitialized) {
            return terminalView.attachSession(terminalSession)!!
        } else {
            return false
        }
    }

    public fun attachSession(session: TerminalSession): Boolean {
        terminalSession = session
        return attachSession()
    }

    public fun disconnectTerminal() {
        terminalView.mTermSession.disconnectTerminal()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = inflater.inflate(R.layout.fragment_terminal_serial_tab, container, false)
        arguments?.let { id = it.getString("id")!! }
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        terminalView = view.findViewById(R.id.serialview)
        attachSession()
        terminalView.setTerminalViewClient(this.asViewClient())
        (activity as? MainActivity)?.modifierKeysController?.addTerminalView(terminalView)
        bootProgressView = view.findViewById(R.id.boot_progress)

        if (savedInstanceState != null) {
            // terminalView.restoreState(savedInstanceState) // nothing for now.
        } else {
            // nothing
        }
        if (true) { // Similar to view.postVisualStateCallback() in onPageFinished()
                            bootProgressView.visibility = View.GONE
                            terminalView.visibility = View.VISIBLE
                            // terminalView.mapTouchToMouseEvent()
                            // terminalView.applyTerminalDisconnectCallback()
                            updateMainActivity()
                            updateFocus()
        }
        updateViewFontSize(fontSizeInit.toInt())
        terminalViewModel.terminalTabFragments.add(this)
    }

    private fun updateViewFontSize(size: Int) {
        terminalView.setTextSize(size) // initial font size, no scaling
        fontSizeNow = size
    }

    public fun getTabId(): String? {
        if (this::id.isInitialized) {
            return id
        } else {
            return null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // terminalView.saveState(outState) // nothing for now.
    }

    override fun onResume() {
        super.onResume()
        updateFocus()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        // terminalView.terminalClose() // No need
        terminalViewModel.terminalTabFragments.remove(this)
        disconnectTerminal()
        super.onDestroy()
    }

    private fun updateMainActivity() {
        val mainActivity = activity as? MainActivity ?: return
        if (terminalGuiSupport()) {
            mainActivity.displayMenu!!.visibility = View.VISIBLE
            mainActivity.displayMenu!!.isEnabled = true
        }
        mainActivity.bootCompleted.open()

        fontSizeInit = mainActivity.fontSize() * FONT_SIZE_MULTIPLIER
    }

    private fun updateFocus() {
        if (terminalViewModel.selectedTabViewId == id) {
            terminalView.requestFocus()
        }
    }

    private fun toggleSoftKeyboardIfNecessary() {
        (activity as? MainActivity)?.let { activity ->
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (activity.resources.configuration.keyboard != Configuration.KEYBOARD_QWERTY) {
                imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
            } else {
                imm.hideSoftInputFromWindow(terminalView.getWindowToken(), 0)
            }
            activity.modifierKeysController.update()
        }
    }

    fun asSessionClient(): SerialSessionClient {
        return SerialSessionClient()
    }

    fun asViewClient(): SerialViewClient {
        return SerialViewClient()
    }

    companion object {
        const val TAG: String = "VmTerminalApp"
        const val FONT_SIZE_MULTIPLIER = 2.0f
    }
    
    // Serial session interface
    inner class SerialSessionClient(): TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            getTerminalView()?.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            getTabId()?.let {
                terminalViewModel.terminalTabs[it]
                    ?.customView
                    ?.findViewById<TextView>(R.id.tab_title)
                    ?.text = changedSession.getTitle() ?: ""
            }
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {}

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}

        override fun onPasteTextFromClipboard(session: TerminalSession?) {}

        override fun onBell(session: TerminalSession) {}

        override fun onColorsChanged(session: TerminalSession) {}

        override fun onTerminalCursorStateChange(state: Boolean) {}

        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}



        override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE



        override fun logError(tag: String, message: String) { Log.e(TAG, message) }

        override fun logWarn(tag: String, message: String) { Log.w(TAG, message) }

        override fun logInfo(tag: String, message: String) { Log.i(TAG, message) }

        override fun logDebug(tag: String, message: String) { Log.d(TAG, message) }

        override fun logVerbose(tag: String, message: String) { Log.v(TAG, message) }

        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(TAG, message, e) }

        override fun logStackTrace(tag: String, e: Exception) { Log.e(TAG, "$e", e) }
    }

    // Serial terminal view interface
    inner class SerialViewClient(): TerminalViewClient {

        /**
         * Callback function on scale events according to {@link ScaleGestureDetector#getScaleFactor()}.
         */
        override fun onScale(scale: Float): Float {
            val fontSizeNext = (fontSizeInit * scale).toInt()
            if (fontSizeNow != fontSizeNext) {
                updateViewFontSize(fontSizeNext)
            }
            return scale
        }

        /**
         * On a single tap on the terminal if terminal mouse reporting not enabled.
         */
        override fun onSingleTapUp(e: MotionEvent) {
            toggleSoftKeyboardIfNecessary()
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean { return true }

        override fun shouldEnforceCharBasedInput(): Boolean { return false } // TODO: make configurable

        override fun shouldUseCtrlSpaceWorkaround(): Boolean { return false } // TODO: make configurable

        override fun isTerminalViewSelected(): Boolean {
            return terminalViewModel.selectedTabViewId == id
        }

        override fun copyModeChanged(copyMode: Boolean) {} // on finish selecting text or deselecting

        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
            // No special handling
            return false
        }

        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
            // No special handling
            return false
        }

        override fun onLongPress(event: MotionEvent): Boolean {
            // No special handling
            return false
        }

        // TODO: extra key holding from virtual keyboard

        override fun readControlKey(): Boolean { return false }

        override fun readAltKey(): Boolean { return false }

        override fun readShiftKey(): Boolean { return false }

        override fun readFnKey(): Boolean { return false }

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
            // No special handling
            return false
        }

        override fun onEmulatorSet() {}

        override fun logError(tag: String, message: String) { Log.e(TAG, message) }

        override fun logWarn(tag: String, message: String) { Log.w(TAG, message) }

        override fun logInfo(tag: String, message: String) { Log.i(TAG, message) }

        override fun logDebug(tag: String, message: String) { Log.d(TAG, message) }

        override fun logVerbose(tag: String, message: String) { Log.v(TAG, message) }

        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(TAG, message, e) }

        override fun logStackTrace(tag: String, e: Exception) { Log.e(TAG, "$e", e) }
    }
}
