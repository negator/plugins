package io.flutter.plugins.webviewflutter

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.os.Build
import android.view.KeyEvent
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

/** WebViewClient that intercepts requests and injects javascript to all frames  */
internal class InterceptingWebViewClient(val delegate: FlutterWebViewClient, val scripts: List<UserScript>, val postData: MutableMap<String, String>) : WebViewClient() {

    private val okHttpClient: OkHttpClient
    private val cookies = FlutterCookieManager

    companion object {
        var jsonContentType = MediaType.parse("application/json; charset=UTF-8")!!
    }

    init {
        okHttpClient = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true)
            .cookieJar(cookies).build()

        okHttpClient.dispatcher().setMaxRequestsPerHost(20)
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
        return okhttpFetch(request)
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        delegate.onPageStarted(view, url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        delegate.onPageFinished(view, url)
    }

    @TargetApi(Build.VERSION_CODES.M)
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        delegate.onWebResourceError(
            error.getErrorCode(), error.getDescription().toString(),
            request.getUrl().toString()
        )
    }

    override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
        delegate.onWebResourceError(errorCode, description, failingUrl)
    }

    override fun onUnhandledKeyEvent(view: WebView, event: KeyEvent?) {
        // Deliberately empty. Occasionally the webview will mark events as having
        // failed to be
        // handled even though they were handled. We don't want to propagate those as
        // they're not
        // truly lost.
    }

    private fun okhttpFetch(request: WebResourceRequest?): WebResourceResponse? {

        // System.out.println("OkHTTP intercepted: " + request?.getUrl() ?: "")
        // System.out.println("OkHTTP intercepted method: " + request?.getMethod() ?: "")

        if (request == null || request.getUrl() == null || request.hasGesture()) {
            return null
        }

        // System.out.println("OkHTTP: " + request.getUrl().toString())
        // System.out.println("OkHTTP method: " + request.getMethod())
        val url: String = request.getUrl().toString()
        val scheme: String = request.getUrl().getScheme().toLowerCase().trim()
        val method = (request.getMethod()?.toUpperCase() ?: "GET").trim()

        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null
        }
        var builder = Request.Builder().url(url)

        var contentType = jsonContentType
        var content: String? = null
        var request_id: String? = null

        // Iterate headers. Extract request body if it exists
        for (entry in request.getRequestHeaders().entries) {
            val key = entry.key.trim().toLowerCase()
            val value = entry.value.trim().toLowerCase()

            when (key) {
                "content-type" -> {
                    contentType = MediaType.parse(value) ?: contentType
                    builder.addHeader(entry.key, entry.value)
                }
                "x-cense-request-id" -> {

                    content = postData.get(value)
                    postData.remove(key)
                }
                else -> builder.addHeader(entry.key, entry.value)
            }
        }

        when {
            method == "POST" -> builder.method(method, RequestBody.create(contentType, content ?: "{}"))
            else -> when (content) {
                null -> builder.method(method, null)
                else -> builder.method(method, RequestBody.create(contentType, content))
            }
        }

        val req = builder.build()
        val call = okHttpClient.newCall(req)

        // System.out.println("Intercepting: " + request.getUrl().toString() + " post data: " + content + ". method: " + method)
        return NonBlockingWebResourceResponse(request, call, scripts, postData)
    }
}
