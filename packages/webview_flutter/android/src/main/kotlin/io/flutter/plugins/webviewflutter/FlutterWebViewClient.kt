package io.flutter.plugins.webviewflutter

import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import io.flutter.plugin.common.MethodChannel
import java.util.Locale

// We need to use WebViewClientCompat to get
// shouldOverrideUrlLoading(WebView view, WebResourceRequest request)
// invoked by the webview on older Android devices, without it pages that use iframes will
// be broken when a navigationDelegate is set on Android version earlier than N.
internal class FlutterWebViewClient constructor (val methodChannel: MethodChannel, val postData: MutableMap<String, String>) {
    private var hasNavigationDelegate = false
    private val userScripts = mutableListOf<UserScript>()

    fun addUserScripts(scripts: List<Map<String, Any>>) {
        scripts.forEach({ addUserScript(UserScript(it)) })
    }

    fun addUserScript(script: UserScript) {
        userScripts.add(script)
    }

    internal fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        if (!hasNavigationDelegate) {
            return false
        }
        // This version of shouldOverrideUrlLoading is only invoked by the webview on devices with
        // webview versions  earlier than 67(it is also invoked when hasNavigationDelegate is false).
        // On these devices we cannot tell whether the navigation is targeted to the main frame or not.
        // We proceed assuming that the navigation is targeted to the main frame. If the page had any
        // frames they will be loaded in the main frame instead.
        Log.w(
            TAG,
            "Using a navigationDelegate with an old webview implementation, pages with frames or iframes will not work"
        )
        notifyOnNavigationRequest(url, null, view, true)
        return true
    }

    internal fun onPageStarted(view: WebView, url: String) {
        val args = hashMapOf<String, Any>()
        args.put("url", url)
        methodChannel.invokeMethod("onPageStarted", args)
    }

    internal fun onPageFinished(view: WebView, url: String) {
        val args = hashMapOf<String, Any>()
        args.put("url", url)
        methodChannel.invokeMethod("onPageFinished", args)
    }

    internal fun onWebResourceError(
        errorCode: Int,
        description: String?,
        failingUrl: String?
    ) {
        val args = hashMapOf<String, Any>()
        args.put("errorCode", errorCode)
        args.put("description", description ?: "")
        args.put("errorType", errorCodeToString(errorCode))
        args.put("failingUrl", failingUrl ?: "")
        methodChannel.invokeMethod("onWebResourceError", args)
    }

    internal fun notifyOnNavigationRequest(
        url: String?,
        headers: Map<String, String>?,
        webview: WebView,
        isMainFrame: Boolean
    ) {
        val args = hashMapOf<String, Any>()
        args.put("url", url ?: "")
        args.put("isForMainFrame", isMainFrame)
        if (isMainFrame) {
            methodChannel.invokeMethod(
                "navigationRequest", args, OnNavigationRequestResult(url, headers, webview)
            )
        } else {
            methodChannel.invokeMethod("navigationRequest", args)
        }
    }

    // This method attempts to avoid using WebViewClientCompat due to bug
    // https://bugs.chromium.org/p/chromium/issues/detail?id=925887. Also, see
    // https://github.com/flutter/flutter/issues/29446.
    fun createWebViewClient(hasNavigationDelegate: Boolean): WebViewClient {
        this.hasNavigationDelegate = hasNavigationDelegate
        return InterceptingWebViewClient(this, userScripts.toList(), postData)
    }

    internal class OnNavigationRequestResult constructor(private val url: String?, private val headers: Map<String, String>?, webView: WebView) : MethodChannel.Result {
        private val webView: WebView

        override fun success(shouldLoad: Any?) {
            val typedShouldLoad = shouldLoad as Boolean
            if (typedShouldLoad) {
                loadUrl()
            }
        }

        override fun error(errorCode: String?, s1: String?, o: Any?) {
            throw IllegalStateException("navigationRequest calls must succeed")
        }

        override fun notImplemented() {
            throw IllegalStateException(
                "navigationRequest must be implemented by the webview method channel"
            )
        }

        private fun loadUrl() {
            webView.loadUrl(url, headers)
        }

        init {
            this.webView = webView
        }
    }

    companion object {
        private const val TAG = "FlutterWebViewClient"
        private fun errorCodeToString(errorCode: Int): String {
            when (errorCode) {
                WebViewClient.ERROR_AUTHENTICATION -> return "authentication"
                WebViewClient.ERROR_BAD_URL -> return "badUrl"
                WebViewClient.ERROR_CONNECT -> return "connect"
                WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> return "failedSslHandshake"
                WebViewClient.ERROR_FILE -> return "file"
                WebViewClient.ERROR_FILE_NOT_FOUND -> return "fileNotFound"
                WebViewClient.ERROR_HOST_LOOKUP -> return "hostLookup"
                WebViewClient.ERROR_IO -> return "io"
                WebViewClient.ERROR_PROXY_AUTHENTICATION -> return "proxyAuthentication"
                WebViewClient.ERROR_REDIRECT_LOOP -> return "redirectLoop"
                WebViewClient.ERROR_TIMEOUT -> return "timeout"
                WebViewClient.ERROR_TOO_MANY_REQUESTS -> return "tooManyRequests"
                WebViewClient.ERROR_UNKNOWN -> return "unknown"
                WebViewClient.ERROR_UNSAFE_RESOURCE -> return "unsafeResource"
                WebViewClient.ERROR_UNSUPPORTED_AUTH_SCHEME -> return "unsupportedAuthScheme"
                WebViewClient.ERROR_UNSUPPORTED_SCHEME -> return "unsupportedScheme"
            }
            val message: String = String.format(Locale.getDefault(), "Could not find a string for errorCode: %d", errorCode)
            throw IllegalArgumentException(message)
        }
    }
}
