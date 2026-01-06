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

import android.annotation.IntDef
import android.annotation.MainThread
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.android.virtualization.terminal.CertificateUtils.createOrGetKey
import com.android.virtualization.terminal.CertificateUtils.writeCertificateToFile
import java.security.PrivateKey
import java.security.cert.X509Certificate

class TerminalTabFragment() : Fragment() {
    private lateinit var terminalView: TerminalView
    private lateinit var bootProgressView: View
    private lateinit var id: String
    @TTYDStatus private var ttydStatus: Int = TTYD_STATUS_UNAVAILABLE
    private var certificates: Array<X509Certificate>? = null
    private var privateKey: PrivateKey? = null
    private val terminalViewModel: TerminalViewModel by activityViewModels()
    private val ttydTimeoutRunnable =
        object : Runnable {
            override fun run() {
                Log.e(TAG, "ttyd timeout")
                // Let unhandled exception handler to handle this
                throw Exception("ttyd timeout")
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = inflater.inflate(R.layout.fragment_terminal_tab, container, false)
        arguments?.let { id = it.getString("id")!! }
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        terminalView = view.findViewById(R.id.webview)
        bootProgressView = view.findViewById(R.id.boot_progress)
        initializeWebView()
        readClientCertificate()

        terminalView.webViewClient = TerminalWebViewClient()

        if (savedInstanceState != null) {
            terminalView.restoreState(savedInstanceState)
        } else {
            (activity as MainActivity).connectToTerminalService(this)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        terminalView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        updateFocus()

        if (ttydStatus != TTYD_STATUS_UNAVAILABLE && ttydStatus != TTYD_STATUS_LOADED) {
            terminalView.getHandler().postDelayed(ttydTimeoutRunnable, TTYD_TIMEOUT_MS)
        }
    }

    override fun onPause() {
        super.onPause()

        terminalView.getHandler().removeCallbacks(ttydTimeoutRunnable)
    }

    override fun onDestroy() {
        terminalView.terminalClose()
        terminalViewModel.terminalTabFragments.remove(this)
        super.onDestroy()
    }

    @MainThread
    public fun loadUrl(url: String) {
        Log.d(TAG, "loading $url")
        if (isResumed()) {
            terminalView.getHandler().postDelayed(ttydTimeoutRunnable, TTYD_TIMEOUT_MS)
        }
        ttydStatus = TTYD_STATUS_STARTED
        terminalView.loadUrl(url)
    }

    private fun initializeWebView() {
        terminalView.settings.databaseEnabled = true
        terminalView.settings.domStorageEnabled = true
        terminalView.settings.javaScriptEnabled = true
        terminalView.settings.cacheMode = WebSettings.LOAD_DEFAULT

        terminalView.webChromeClient = TerminalWebChromeClient()
        terminalView.webViewClient = TerminalWebViewClient()
        terminalView.addJavascriptInterface(TerminalViewInterface(context!!), "TerminalApp")

        (activity as MainActivity).modifierKeysController.addTerminalView(terminalView)
        terminalViewModel.terminalTabFragments.add(this)
    }

    private inner class TerminalWebChromeClient : WebChromeClient() {
        override fun onReceivedTitle(view: WebView?, title: String?) {
            super.onReceivedTitle(view, title)
            title?.let { originalTitle ->
                // When the session is created. The format of the title will be
                // 'droid@localhost: ~ | login -f droid (localhost)'
                // or 'droid@debian: ~ | login -f droid (debian)'.
                val displayedTitle = originalTitle.substringBeforeLast(" | login -f droid (")

                terminalViewModel.terminalTabs[id]
                    ?.customView
                    ?.findViewById<TextView>(R.id.tab_title)
                    ?.text = displayedTitle
            }
        }
    }

    inner class TerminalViewInterface(private val mContext: android.content.Context) {
        @JavascriptInterface
        fun closeTab() {
            if (terminalViewModel.terminalTabs.containsKey(id)) {
                if (activity != null) {
                    activity?.runOnUiThread {
                        val mainActivity = (activity as MainActivity)
                        mainActivity.closeTab(terminalViewModel.terminalTabs[id]!!)
                    }
                }
            }
        }
    }

    private inner class TerminalWebViewClient : WebViewClient() {
        private var loadFailed = false
        private var requestId: Long = 0

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
            startActivity(intent)
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            // Resets error status if it was
            ttydStatus = TTYD_STATUS_STARTED
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            ttydStatus = TTYD_STATUS_ERROR
            when (error.getErrorCode()) {
                ERROR_CONNECT,
                ERROR_HOST_LOOKUP,
                ERROR_FAILED_SSL_HANDSHAKE,
                ERROR_TIMEOUT -> {
                    // Note that ERROR_TIMEOUT is for timeout after onPageStarted()
                    // and can be called if MainActivity is started while screen is locked
                    // after installation is completed.
                    view.reload()
                    return
                }
            }

            val url: String? = request.getUrl().toString()
            val msg = error.getDescription()
            Log.e(MainActivity.TAG, "Failed to load $url: $msg")

            // Let unhandled exception handler to handle this
            throw Exception(msg.toString())
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (ttydStatus == TTYD_STATUS_ERROR) {
                return
            }

            requestId++
            view.postVisualStateCallback(
                requestId,
                object : WebView.VisualStateCallback() {
                    override fun onComplete(completedRequestId: Long) {
                        if (completedRequestId == requestId) {
                            ttydStatus = TTYD_STATUS_LOADED
                            view.getHandler().removeCallbacks(ttydTimeoutRunnable)

                            bootProgressView.visibility = View.GONE
                            terminalView.visibility = View.VISIBLE
                            terminalView.mapTouchToMouseEvent()
                            terminalView.applyTerminalDisconnectCallback()
                            updateMainActivity()
                            updateFocus()
                        }
                    }
                },
            )
        }

        override fun onReceivedClientCertRequest(view: WebView?, request: ClientCertRequest) {
            if (privateKey != null && certificates != null) {
                request.proceed(privateKey, certificates)
                return
            }
            super.onReceivedClientCertRequest(view, request)
        }

        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler,
            error: SslError?,
        ) {
            // ttyd uses self-signed certificate
            handler.proceed()
        }
    }

    private fun updateMainActivity() {
        val mainActivity = activity as? MainActivity ?: return
        if (terminalGuiSupport()) {
            mainActivity.displayMenu!!.visibility = View.VISIBLE
            mainActivity.displayMenu!!.isEnabled = true
        }
        mainActivity.tabAddButton!!.isEnabled = true
        mainActivity.bootCompleted.open()
    }

    private fun readClientCertificate() {
        val pke = createOrGetKey()
        writeCertificateToFile(activity!!, pke.certificate)
        privateKey = pke.privateKey
        certificates = arrayOf<X509Certificate>(pke.certificate as X509Certificate)
    }

    private fun updateFocus() {
        if (terminalViewModel.selectedTabViewId == id) {
            terminalView.requestFocus()
        }
    }

    companion object {
        const val TAG: String = "VmTerminalApp"
        const val TTYD_TIMEOUT_MS = 5_000L

        @IntDef(TTYD_STATUS_UNAVAILABLE, TTYD_STATUS_STARTED, TTYD_STATUS_LOADED, TTYD_STATUS_ERROR)
        @Retention(AnnotationRetention.SOURCE)
        annotation class TTYDStatus

        const val TTYD_STATUS_UNAVAILABLE = 0
        const val TTYD_STATUS_STARTED = 1
        const val TTYD_STATUS_LOADED = 2
        const val TTYD_STATUS_ERROR = 3
    }
}
