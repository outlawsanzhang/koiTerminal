/*
 * Copyright (C) 2024 The Android Open Source Project
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
import android.graphics.Rect
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView
import com.android.virtualization.terminal.MainActivity.Companion.TAG
import java.io.IOException

class TerminalView(context: Context, attrs: AttributeSet?) :
    WebView(context, attrs),
    AccessibilityManager.AccessibilityStateChangeListener,
    AccessibilityManager.TouchExplorationStateChangeListener {
    private val ctrlKeyHandler: String = readAssetAsString(context, "js/ctrl_key_handler.js")
    private val enableCtrlKey: String = readAssetAsString(context, "js/enable_ctrl_key.js")
    private val disableCtrlKey: String = readAssetAsString(context, "js/disable_ctrl_key.js")
    private val terminalDisconnectCallback: String =
        readAssetAsString(context, "js/terminal_disconnect.js")
    private val terminalClose: String = readAssetAsString(context, "js/terminal_close.js")
    private val touchToMouseHandler: String =
        readAssetAsString(context, "js/touch_to_mouse_handler.js")
    private val a11yManager =
        context.getSystemService<AccessibilityManager>(AccessibilityManager::class.java).also {
            it.addTouchExplorationStateChangeListener(this)
            it.addAccessibilityStateChangeListener(this)
        }

    @Throws(IOException::class)
    private fun readAssetAsString(context: Context, filePath: String): String {
        return String(context.assets.open(filePath).readAllBytes())
    }

    fun mapTouchToMouseEvent() {
        this.evaluateJavascript(touchToMouseHandler, null)
    }

    fun mapCtrlKey() {
        this.evaluateJavascript(ctrlKeyHandler, null)
    }

    fun enableCtrlKey() {
        this.evaluateJavascript(enableCtrlKey, null)
    }

    fun disableCtrlKey() {
        this.evaluateJavascript(disableCtrlKey, null)
    }

    fun applyTerminalDisconnectCallback() {
        this.evaluateJavascript(terminalDisconnectCallback, null)
    }

    fun terminalClose() {
        this.evaluateJavascript(terminalClose, null)
    }

    override fun onAccessibilityStateChanged(enabled: Boolean) {
        Log.d(TAG, "accessibility $enabled")
        adjustToA11yStateChange()
    }

    override fun onTouchExplorationStateChanged(enabled: Boolean) {
        Log.d(TAG, "touch exploration $enabled")
        adjustToA11yStateChange()
    }

    private fun adjustToA11yStateChange() {
        if (!a11yManager.isEnabled) {
            setFocusable(true)
            return
        }

        // When accessibility is on, the webview itself doesn't have to be focusable. The (virtual)
        // edittext will be focusable to accept inputs. However, the webview has to be focusable for
        // an accessibility purpose so that users can read the contents in it or scroll the view.
        setFocusable(false)
        setFocusableInTouchMode(true)
    }

    // AccessibilityEvents for WebView are sent directly from WebContentsAccessibilityImpl to the
    // parent of WebView, without going through WebView. So, there's no WebView methods we can
    // override to intercept the event handling process. To work around this, we attach an
    // AccessibilityDelegate to the parent view where the events are sent to. And to guarantee that
    // the parent view exists, wait until the WebView is attached to the window by when the parent
    // must exist.
    private val a11yEventFilter: AccessibilityDelegate =
        object : AccessibilityDelegate() {
            override fun onRequestSendAccessibilityEvent(
                host: ViewGroup,
                child: View,
                e: AccessibilityEvent,
            ): Boolean {
                // We filter only the a11y events from the WebView
                if (child !== this@TerminalView) {
                    return super.onRequestSendAccessibilityEvent(host, child, e)
                }
                when (e.eventType) {
                    AccessibilityEvent.TYPE_ANNOUNCEMENT -> {
                        val text = e.text[0] // there always is a text
                        if (text.length >= TEXT_TOO_LONG_TO_ANNOUNCE) {
                            Log.i(TAG, "Announcement skipped because it's too long: $text")
                            return false
                        }
                    }
                }
                return super.onRequestSendAccessibilityEvent(host, child, e)
            }
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (a11yManager.isEnabled) {
            val parent = getParent() as View
            parent.setAccessibilityDelegate(a11yEventFilter)
        }
    }

    private val a11yNodeProvider: AccessibilityNodeProvider =
        object : AccessibilityNodeProvider() {
            /** Returns the original NodeProvider that WebView implements. */
            private fun getParent(): AccessibilityNodeProvider? {
                return super@TerminalView.getAccessibilityNodeProvider()
            }

            /** Convenience method for reading a string resource. */
            private fun getString(resId: Int): String {
                return this@TerminalView.context.getResources().getString(resId)
            }

            /** Checks if NodeInfo renders an empty line in the terminal. */
            private fun isEmptyLine(info: AccessibilityNodeInfo): Boolean {
                // Node with no text is not considered a line. ttyd emits at least one character,
                // which usually is NBSP.
                // Note: don't use Characters.isWhitespace as it doesn't recognize NBSP as a
                // whitespace.
                return (info.getText()?.all { TextUtils.isWhitespace(it.code) }) == true
            }

            override fun createAccessibilityNodeInfo(id: Int): AccessibilityNodeInfo? {
                val info: AccessibilityNodeInfo? = getParent()?.createAccessibilityNodeInfo(id)
                if (info == null) {
                    return null
                }

                val className = info.className.toString()

                // By default all views except the cursor is not click-able. Other views are
                // read-only. This ensures that user is not navigated to non-clickable elements
                // when using switches.
                if ("android.widget.EditText" != className) {
                    info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                }

                when (className) {
                    "android.webkit.WebView" -> {
                        // There are two NodeInfo objects of class name WebView. The one is the
                        // real WebView whose ID is View.NO_ID as it's at the root of the
                        // virtual view hierarchy. The second one is a virtual view for the
                        // iframe. The latter one's text is set to the command that we give to
                        // ttyd, which is "login -f droid ...". This is an impl detail which
                        // doesn't have to be announced.  Replace the text with "Terminal
                        // display".
                        if (id != NO_ID) {
                            info.setText(null)
                            info.setContentDescription(getString(R.string.terminal_display))
                            // b/376827536
                            info.setHintText(getString(R.string.double_tap_to_edit_text))
                        }

                        // These two lines below are to prevent this WebView element from being
                        // focusable by the screen reader, while allowing any other element in
                        // the WebView to be focusable by the reader. In our case, the EditText
                        // is a117_focusable.
                        info.isScreenReaderFocusable = false
                        info.addAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS
                        )
                    }

                    "android.view.View" ->
                        // Empty line was announced as "space" (via the NBSP character).
                        // Localize the spoken text.
                        if (isEmptyLine(info)) {
                            info.setContentDescription(getString(R.string.empty_line))
                            // b/376827536
                            info.setHintText(getString(R.string.double_tap_to_edit_text))
                        }

                    "android.widget.TextView" -> {
                        // There are several TextViews in the terminal, and one of them is an
                        // invisible TextView which seems to be from the <div
                        // class="live-region"> tag. Interestingly, its text is often populated
                        // with the entire text on the screen. Silence this by forcibly setting
                        // the text to null. Note that this TextView is identified by having a
                        // zero width. This certainly is not elegant, but I couldn't find other
                        // options.
                        val rect = Rect()
                        info.getBoundsInScreen(rect)
                        if (rect.width() == 0) {
                            info.setText(null)
                            info.setContentDescription(getString(R.string.empty_line))
                        }
                        info.isScreenReaderFocusable = false
                    }

                    "android.widget.EditText" -> {
                        // This EditText is for the <textarea> accepting user input; the cursor.
                        // ttyd name it as "Terminal input" but it's not i18n'ed. Override it
                        // here for better i18n.
                        info.setText(null)
                        info.setHintText(getString(R.string.double_tap_to_edit_text))
                        info.setContentDescription(getString(R.string.terminal_input))
                        info.isScreenReaderFocusable = true
                        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS)
                    }
                }
                return info
            }

            override fun performAction(id: Int, action: Int, arguments: Bundle?): Boolean {
                return getParent()?.performAction(id, action, arguments) == true
            }

            override fun addExtraDataToAccessibilityNodeInfo(
                virtualViewId: Int,
                info: AccessibilityNodeInfo?,
                extraDataKey: String?,
                arguments: Bundle?,
            ) {
                getParent()
                    ?.addExtraDataToAccessibilityNodeInfo(
                        virtualViewId,
                        info,
                        extraDataKey,
                        arguments,
                    )
            }

            override fun findAccessibilityNodeInfosByText(
                text: String?,
                virtualViewId: Int,
            ): MutableList<AccessibilityNodeInfo?>? {
                return getParent()?.findAccessibilityNodeInfosByText(text, virtualViewId)
            }

            override fun findFocus(focus: Int): AccessibilityNodeInfo? {
                return getParent()?.findFocus(focus)
            }
        }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider? {
        val p = super.getAccessibilityNodeProvider()
        if (p != null && a11yManager.isEnabled) {
            return a11yNodeProvider
        }
        return p
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo?): InputConnection? {
        val inputConnection = super.onCreateInputConnection(outAttrs)
        if (outAttrs != null) {
            outAttrs.inputType =
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            outAttrs.imeOptions = EditorInfo.IME_FLAG_FORCE_ASCII
        }
        return inputConnection
    }

    companion object {
        // Maximum length of texts the talk back announcements can be. This value is somewhat
        // arbitrarily set. We may want to adjust this in the future.
        private const val TEXT_TOO_LONG_TO_ANNOUNCE = 200
    }
}
