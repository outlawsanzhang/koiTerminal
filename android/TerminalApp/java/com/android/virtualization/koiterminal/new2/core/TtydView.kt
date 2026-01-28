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
package com.android.virtualization.koiterminal.new2.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.fonts.FontStyle
import android.net.http.SslError
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.webkit.ClientCertRequest
import android.webkit.ConsoleMessage
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.android.virtualization.koiterminal.CertificateUtils
import com.android.virtualization.koiterminal.TerminalView
import com.termux.view.TerminalView as TermuxView
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import java.net.MalformedURLException
import java.net.URL
import java.security.cert.X509Certificate

sealed interface TtyView {
    var onTerminalReady: (() -> Unit)?
    var onTerminalDisconnected: (() -> Unit)?
    var onSessionDiscard: (() -> Unit)?
    var onTitleChanged: ((String) -> Unit)?
    fun showSoftInput()
    fun hideSoftInput()

    fun asView(): View =
        when (this) {
            is TtydView -> { this as View }
            is TtySView -> { this as View }
        }

    fun terminalClose()
    fun disableCtrlKey()
    fun onResume()
    fun onPause()
}
    
class TtydView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    TerminalView(context, attrs), TtyView {
    override var onTerminalReady: (() -> Unit)? = null
    override var onTerminalDisconnected: (() -> Unit)? = null
    override var onSessionDiscard: (() -> Unit)? = null
    override var onTitleChanged: ((String) -> Unit)? = null

    private var fontSize = (context.resources.configuration.fontScale * DEFAULT_FONT_SIZE).toInt()

    private val scaleGestureDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (Math.abs(detector.scaleFactor - 1.0f) < 0.1f) {
                        return false
                    }
                    if (detector.scaleFactor > 1.0f) {
                        if (fontSize < MAX_FONT_SIZE) {
                            fontSize++
                            updateFontSize()
                        }
                    } else {
                        if (fontSize > MIN_FONT_SIZE) {
                            fontSize--
                            updateFontSize()
                        }
                    }
                    return true
                }
            },
        )

    init {
        settings.domStorageEnabled = true
        settings.javaScriptEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.textZoom = 100

        addJavascriptInterface(
            object {
                @android.webkit.JavascriptInterface
                fun onTerminalReady() {
                    this@TtydView.onTerminalReady!!.invoke()
                }

                @android.webkit.JavascriptInterface
                fun onTerminalDisconnected() {
                    post { this@TtydView.onTerminalDisconnected?.invoke() }
                }

                @android.webkit.JavascriptInterface
                fun closeTab() {
                    post { this@TtydView.onSessionDiscard?.invoke() }
                }

                @android.webkit.JavascriptInterface
                fun showError() {
                    // TODO: UI for showing connection error
                }
            },
            "TerminalApp",
        )
        webViewClient = TtydWebViewClient()
        layoutParams =
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(android.graphics.Color.BLACK)

        enableJavascriptConsoleDebug()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val newFontSize = (newConfig.fontScale * DEFAULT_FONT_SIZE).toInt()
        if (fontSize != newFontSize) {
            fontSize = newFontSize
            updateFontSize()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_EQUALS,
                KeyEvent.KEYCODE_PLUS -> {
                    if (fontSize < MAX_FONT_SIZE) {
                        fontSize++
                        updateFontSize()
                    }
                    return true
                }
                KeyEvent.KEYCODE_MINUS -> {
                    if (fontSize > MIN_FONT_SIZE) {
                        fontSize--
                        updateFontSize()
                    }
                    return true
                }
                KeyEvent.KEYCODE_0 -> {
                    val defaultFontSize =
                        (context.resources.configuration.fontScale * DEFAULT_FONT_SIZE).toInt()
                    if (fontSize != defaultFontSize) {
                        fontSize = defaultFontSize
                        updateFontSize()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun updateFontSize() {
        evaluateJavascript(
            "term.options.fontSize = $fontSize; window.dispatchEvent(new Event('resize'));",
            null,
        )
    }

    fun load(terminalAddress: TerminalAddress) {
        val ssl = terminalAddress.key.isNullOrEmpty()
        val url = getTerminalServiceUrl(terminalAddress, ssl)
        Log.d("TtydView", "Loading URL: ${url.toString()}")

        terminalAddress.key?.let { key ->
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setCookie(url.toString(), "access_token=$key")
            cookieManager.flush()
        }

        loadUrl(url.toString())
    }

    override fun showSoftInput() {
        if (requestFocus()) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(this, 0)
        }
    }

    override fun hideSoftInput() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun getTerminalServiceUrl(terminalAddress: TerminalAddress, ssl: Boolean): URL? {
        val config = resources.configuration
        val a11yManager = context.getSystemService(AccessibilityManager::class.java)
        // TODO: Always enable screenReaderMode (b/395845063)
        val query =
            ("?fontSize=" +
                (config.fontScale * DEFAULT_FONT_SIZE).toInt() +
                "&fontWeight=" +
                (FontStyle.FONT_WEIGHT_NORMAL + config.fontWeightAdjustment) +
                "&fontWeightBold=" +
                (FontStyle.FONT_WEIGHT_BOLD + config.fontWeightAdjustment) +
                "&screenReaderMode=" +
                a11yManager.isEnabled +
                "&disableResizeOverlay=true" +
                // Use DOM renderer to ensure sharp text and proper anti-aliasing on all displays,
                // especially on external monitors where the default canvas renderer might produce
                // blurry output due to scaling or density mismatches.
                "&rendererType=dom")

        try {
            return URL(
                if (ssl) "https" else "http",
                terminalAddress.ipAddress,
                terminalAddress.port,
                query,
            )
        } catch (e: MalformedURLException) {
            // this cannot happen
            return null
        }
    }

    override fun onCheckIsTextEditor(): Boolean {
        return true
    }

    private fun enableJavascriptConsoleDebug() {
        webChromeClient =
            object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                    Log.d("TTYD", "${msg?.message()}")
                    return true
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    title?.let { originalTitle ->
                        val displayedTitle = originalTitle.substringBeforeLast(" | ")
                        onTitleChanged?.invoke(displayedTitle)
                    }
                }
            }
    }

    private inner class TtydWebViewClient : WebViewClient() {
        override fun onPageStarted(
            view: WebView?,
            url: String?,
            favicon: android.graphics.Bitmap?,
        ) {
            super.onPageStarted(view, url, favicon)
            injectWebSocketInterceptor()
        }

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            val intent = Intent(Intent.ACTION_VIEW, request?.url)
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Sanitize the Intent, ensuring web pages can not bypass browser security (only access
            // to BROWSABLE activities).
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.setComponent(null)
            // Intent Selectors allow intents to bypass the intent filter and potentially send apps
            // URIs they were not expecting to handle.
            intent.setSelector(null)
            context.startActivity(intent)
            return true
        }

        override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
            val pke = CertificateUtils.createOrGetKey()
            val certificates = arrayOf<X509Certificate>(pke.certificate as X509Certificate)
            request.proceed(pke.privateKey, certificates)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError?) {
            // ttyd uses self-signed certificate
            handler.proceed()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            Log.e("TtydWebViewClient", "WebView Error: ${error.errorCode} - ${error.description}")
            // Consider errors like network loss, host lookup failure as disconnection
            if (
                error.errorCode == WebViewClient.ERROR_HOST_LOOKUP ||
                    error.errorCode == WebViewClient.ERROR_CONNECT ||
                    error.errorCode == WebViewClient.ERROR_TIMEOUT ||
                    error.errorCode == WebViewClient.ERROR_BAD_URL
            ) {
                this@TtydView.onTerminalDisconnected?.invoke()
            }
            super.onReceivedError(view, request, error)
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            mapTouchToMouseEvent()
            applyTerminalDisconnectCallback()
            // TODO: explain reason for this
            val js =
                """
                (function() {
                    var notifyReady = function() {
                        window.term.focus();
                        window.TerminalApp.onTerminalReady();
                    };
                    var check = function() {
                        var xterm = document.querySelector('.terminal.xterm');
                        if (window.term && xterm) {
                            console.log("xterm found");
                            setTimeout(notifyReady, 500);
                        } else {
                            console.log("xterm not found. waiting...");
                            setTimeout(check, 100);
                        }
                    };
                    check();
                })();
            """
            view.evaluateJavascript(js, null)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        display?.let { d ->
            val lp = android.util.DisplayMetrics()
            d.getMetrics(lp)
            // Use logical density which represents the system's intended scaling for the display.
            setInitialScale((lp.density * 100).toInt())
        }
    }

    companion object {
        private const val DEFAULT_FONT_SIZE = 13
        private const val MIN_FONT_SIZE = 5
        private const val MAX_FONT_SIZE = 200
    }
}

class TtySView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    TermuxView(context, attrs), TtyView {
    override var onTerminalReady: (() -> Unit)? = null
    override var onTerminalDisconnected: (() -> Unit)? = null
    override var onSessionDiscard: (() -> Unit)? = null
    override var onTitleChanged: ((String) -> Unit)? = null

    private var fontSizeInit: Float = context.resources.configuration.fontScale * DEFAULT_FONT_SIZE
    private var fontSizeNow: Int = fontSizeInit.toInt()

    init {
        updateViewFontSize(fontSizeInit.toInt())
        setDefaultFocusHighlightEnabled(false)
        setFocusableInTouchMode(true)
    }

    private fun updateViewFontSize(size: Int) {
        setTextSize(size) // initial font size, no scaling
        fontSizeNow = size
    }

    override fun showSoftInput() {
        if (requestFocus()) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (context.resources.configuration.keyboard != Configuration.KEYBOARD_QWERTY) {
                imm.showSoftInput(this, 0)
            }
        }
    }

    override fun hideSoftInput() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    override public fun terminalClose() {
        Log.d("TtydView", "terminalClose")
        mTermSession.disconnectTerminal()
    }

    override fun disableCtrlKey() {}
    override fun onResume() {}
    override fun onPause() {}

    fun asSessionClient(): SerialSessionClient {
        return SerialSessionClient()
    }

    fun asViewClient(): SerialViewClient {
        return SerialViewClient()
    }

    companion object {
        const val TAG: String = "VmTerminalApp"
        private const val DEFAULT_FONT_SIZE = 30
        private const val MODKEY_UP = 0
        private const val MODKEY_DOWN = 1
        private const val MODKEY_VIRTUAL_HELD = 2
        private const val MODKEY_MANUAL_HELD = 3
    }
    
    // Serial session interface
    inner class SerialSessionClient(): TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            (this@TtySView).onTitleChanged?.invoke(changedSession.getTitle() ?: "")
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {}

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text == null) return
            val clipboard = getContext()?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard == null) return
            val clip: ClipData = ClipData.newPlainText("Termux copy text", text)
            clipboard.setPrimaryClip(clip)
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            (getContext()?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.primaryClip
                ?.getItemAt(0)
                ?.let {
                    val text = it.coerceToText(getContext())
                    if (!text.isEmpty()) mEmulator?.paste(text.toString());
                }
        }

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

        public var isCtrlDown: Int = MODKEY_UP
        public var isAltDown: Int = MODKEY_UP

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
            // Let the compose code handles the inconsistency
            showSoftInput()
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean { return true }

        override fun shouldEnforceCharBasedInput(): Boolean { return false } // TODO: make configurable

        override fun shouldUseCtrlSpaceWorkaround(): Boolean { return false } // TODO: make configurable

        override fun isTerminalViewSelected(): Boolean {
            // FIXME: what to do here?
            return isFocused()
        }

        override fun copyModeChanged(copyMode: Boolean) {} // on finish selecting text or deselecting

        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
            if (keyCode == KeyEvent.KEYCODE_CTRL_LEFT) {
                when (isCtrlDown) {
                    MODKEY_UP, MODKEY_VIRTUAL_HELD -> isCtrlDown = MODKEY_DOWN
                }
            }
            if (keyCode == KeyEvent.KEYCODE_ALT_LEFT) {
                when (isAltDown) {
                    MODKEY_UP, MODKEY_VIRTUAL_HELD -> isAltDown = MODKEY_DOWN
                }
            }
            return false
        }

        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
            // This is called with hardware key presses and occasionally software (e.g. AOSP keyboard Enter and Backspace)
            if (KeyEvent.isModifierKey(keyCode)) {
                if (keyCode == KeyEvent.KEYCODE_CTRL_LEFT) {
                    when (isCtrlDown) {
                        MODKEY_DOWN -> isCtrlDown = MODKEY_VIRTUAL_HELD
                        MODKEY_MANUAL_HELD -> isCtrlDown = MODKEY_UP
                    }
                }
                if (keyCode == KeyEvent.KEYCODE_ALT_LEFT) {
                    when (isAltDown) {
                        MODKEY_DOWN -> isAltDown = MODKEY_VIRTUAL_HELD
                        MODKEY_MANUAL_HELD -> isAltDown = MODKEY_UP
                    }
                }
            } else {
                // This cannot be put into onKeyDown because read*Key() is called after that.
                onNonModifierKey()
            }
            return false
        }

        override fun onLongPress(event: MotionEvent): Boolean {
            // No special handling
            return false
        }

        override fun readControlKey(): Boolean { return isCtrlDown != MODKEY_UP }

        override fun readAltKey(): Boolean { return isAltDown != MODKEY_UP }

        // TODO: keys absent from virtual modifier keyboard

        override fun readShiftKey(): Boolean { return false }

        override fun readFnKey(): Boolean { return false }

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
            // This is called after read*Key() are called and logic processed.
            onNonModifierKey()
            return false
        }

        fun onNonModifierKey() {
            // Clear button holding.
            when (isCtrlDown) {
                MODKEY_DOWN -> isCtrlDown = MODKEY_MANUAL_HELD
                MODKEY_VIRTUAL_HELD -> isCtrlDown = MODKEY_UP
            }
            when (isAltDown) {
                MODKEY_DOWN -> isAltDown = MODKEY_MANUAL_HELD
                MODKEY_VIRTUAL_HELD -> isAltDown = MODKEY_UP
            }
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

