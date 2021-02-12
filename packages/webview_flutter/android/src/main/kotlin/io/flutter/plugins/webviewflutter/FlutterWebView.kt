package io.flutter.plugins.webviewflutter

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.os.Handler
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebStorage
import android.webkit.WebView
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.platform.PlatformView
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FlutterWebView internal constructor(
    context: Context,
    messenger: BinaryMessenger?,
    id: Int,
    params: Map<String, Any>
) : PlatformView, MethodCallHandler {

    val postData = ConcurrentHashMap<String, String>()
    private val methodChannel = MethodChannel(messenger, "plugins.flutter.io/webview_$id")
    private val flutterWebViewClient = FlutterWebViewClient(methodChannel, postData)
    private val platformThreadHandler = Handler(context.getMainLooper())

    private val webView = WebView(context)

    companion object {
        private const val JS_CHANNEL_NAMES_FIELD = "javascriptChannelNames"

        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/61.0.3163.100 Mobile Safari/537.36"

        private const val overrideJs =
            """
            let request_id_header = 'x-cense-request-id';                                 
            XMLHttpRequest.prototype.origSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.send = function(body) {                 
                if (body) {
                    let id = recorder.recordPayload(body);                                        
                    this.setRequestHeader(request_id_header, id);                    
                }
                return this.origSend(body);
            };            

            if (window.fetch) {                
                window.fetch = (                    
                    function (ogFetch) {
                        return function (...args) {                                                        
                            try {                               
                                var req = null;                                                            
                                if (args.length == 1 && (typeof args[0] === 'object')) {                                                                        
                                    req = args[0].clone();                                    
                                } else if (args.length > 1) {
                                    req = args[1];
                                } else {
                                    return;
                                }

                                let body = req.body;                                

                                if(body) {                                    
                                    if (!req.headers) {
                                        req.headers = {};
                                    }                                    
                                    req.headers[request_id_header] = recorder.recordPayload(body);                                                                                                         
                                }
                                if (args.length == 1 && (typeof args[0] === 'object')) {                                                                    
                                    return ogFetch(req)
                                } else if (args.length > 1) {
                                    return ogFetch(args[0], req)
                                }
                                
                            } catch (err) {                                
                                return ogFetch(...args);
                            }                       
                        };
                    }
                )(window.fetch);
            }
        """
    }

    init {
        webView.getSettings().setDomStorageEnabled(true)
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(true)
        webView.getSettings().setJavaScriptEnabled(true)
        webView.getSettings().setAllowFileAccess(false)
        methodChannel.setMethodCallHandler(this)

        if (params.containsKey(JS_CHANNEL_NAMES_FIELD)) {
            registerJavaScriptChannelNames(params[JS_CHANNEL_NAMES_FIELD] as List<String>?)
        }

        flutterWebViewClient.addUserScript(UserScript(overrideJs, null, null))
        (params["userScripts"] as List<Map<String, Any>>?)?.let {
            flutterWebViewClient.addUserScripts(it)
        }

        (params["autoMediaPlaybackPolicy"] as Int?)?.let {
            updateAutoMediaPlaybackPolicy(it)
        }

        updateUserAgent(USER_AGENT)
        (params["userAgent"] as String?)?.let { userAgent ->
            updateUserAgent(userAgent ?: "")
        }

        val client = FlutterWebChromeClient(methodChannel)
        webView.setWebChromeClient(client)

        (params["settings"] as Map<String, Any>?)?.let { applySettings(it) }

        val reporter = object {
            @JavascriptInterface
            public fun recordPayload(payload: String): String {
                val id = UUID.randomUUID().toString()
                postData.put(id, payload)
                return id
            }
        }
        webView.addJavascriptInterface(reporter, "recorder")

        var url = params["initialUrl"] as String? ?: ""
        url = if (url.isEmpty()) "https://localhost/blank" else url

        webView.loadUrl(url)
    }

    override fun getView(): View {
        return webView
    }

    override fun onFlutterViewAttached(view: View) {}
    override fun onFlutterViewDetached() {}
    override fun onInputConnectionLocked() {}
    override fun onInputConnectionUnlocked() {}

    override fun onMethodCall(methodCall: MethodCall, result: Result) {
        System.out.println("Method call: " + methodCall.method)
        when (methodCall.method) {
            "loadUrl" -> loadUrl(methodCall, result)
            "updateSettings" -> updateSettings(methodCall, result)
            "canGoBack" -> canGoBack(result)
            "canGoForward" -> canGoForward(result)
            "goBack" -> goBack(result)
            "goForward" -> goForward(result)
            "reload" -> reload(result)
            "stopLoading" -> stopLoading(result)
            "currentUrl" -> currentUrl(result)
            "evaluateJavascript" -> evaluateJavaScript(methodCall, result)
            "addJavascriptChannels" -> addJavaScriptChannels(methodCall, result)
            "removeJavascriptChannels" -> removeJavaScriptChannels(methodCall, result)
            "clearCache" -> clearCache(result)
            "getTitle" -> getTitle(result)
            "scrollTo" -> scrollTo(methodCall, result)
            "scrollBy" -> scrollBy(methodCall, result)
            "getScrollX" -> getScrollX(result)
            "getScrollY" -> getScrollY(result)
            "getCookies" -> {
                val args = methodCall.arguments as Map<String, String>
                var url = args["url"]
                if (url == null) {
                    url = ""
                }
                FlutterCookieManager.getCookies(url, result)
            }
            "setCookies" -> (methodCall.arguments as List<Map<String, Any>>?)?.let {
                FlutterCookieManager.setCookies(it, result)
            }
            "clearCookies" -> FlutterCookieManager.clearCookies(result)
            else -> result.notImplemented()
        }
    }

    @SuppressWarnings("unchecked")
    private fun loadUrl(methodCall: MethodCall, result: Result) {
        (methodCall.arguments as Map<String, Any>?)?.let { request ->
            val url = request["url"] as String? ?: ""
            var headers = request["headers"] as Map<String?, String?>? ?: hashMapOf()
            System.out.println("Loading url: $url")
            webView.loadUrl(url, headers)
        }

        result.success(null)
    }

    private fun canGoBack(result: Result) {
        result.success(webView.canGoBack())
    }

    private fun canGoForward(result: Result) {
        result.success(webView.canGoForward())
    }

    private fun goBack(result: Result) {
        if (webView.canGoBack()) {
            webView.goBack()
        }
        result.success(null)
    }

    private fun goForward(result: Result) {
        if (webView.canGoForward()) {
            webView.goForward()
        }
        result.success(null)
    }

    private fun reload(result: Result) {
        webView.reload()
        result.success(null)
    }

    private fun stopLoading(result: Result) {
        webView.stopLoading()
        result.success(null)
    }

    private fun currentUrl(result: Result) {
        result.success(webView.getUrl())
    }

    @SuppressWarnings("unchecked")
    private fun updateSettings(methodCall: MethodCall, result: Result) {
        applySettings(methodCall.arguments as Map<String, Any>)
        result.success(null)
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private fun evaluateJavaScript(methodCall: MethodCall, result: Result) {
        val jsString = methodCall.arguments as String
            ?: throw UnsupportedOperationException("JavaScript string cannot be null")
        webView.evaluateJavascript(jsString) { value ->
            result.success(value)
        }
    }

    @SuppressWarnings("unchecked")
    private fun addJavaScriptChannels(methodCall: MethodCall, result: Result) {
        val channelNames = methodCall.arguments as List<String>
        registerJavaScriptChannelNames(channelNames)
        result.success(null)
    }

    @SuppressWarnings("unchecked")
    private fun removeJavaScriptChannels(methodCall: MethodCall, result: Result) {
        val channelNames = methodCall.arguments as List<String>
        for (channelName in channelNames) {
            webView.removeJavascriptInterface(channelName)
        }
        result.success(null)
    }

    private fun clearCache(result: Result) {
        webView.clearCache(true)
        WebStorage.getInstance().deleteAllData()
        result.success(null)
    }

    private fun getTitle(result: Result) {
        result.success(webView.getTitle())
    }

    private fun scrollTo(methodCall: MethodCall, result: Result) {
        (methodCall.arguments as Map<String, Any>?)?.let { request ->
            val x = request["x"] as Int
            val y = request["y"] as Int
            webView.scrollTo(x, y)
        }
        result.success(null)
    }

    private fun scrollBy(methodCall: MethodCall, result: Result) {
        (methodCall.arguments as Map<String, Any>?)?.let { request ->
            val x = request["x"] as Int
            val y = request["y"] as Int
            webView.scrollBy(x, y)
        }
        result.success(null)
    }

    private fun getScrollX(result: Result) {
        result.success(webView.getScrollX())
    }

    private fun getScrollY(result: Result) {
        result.success(webView.getScrollY())
    }

    private fun applySettings(settings: Map<String, Any>) {
        for (key in settings.keys) {
            when (key) {
                "jsMode" -> { /*noop*/ }
                "hasNavigationDelegate" -> {
                    val hasNavigationDelegate = settings[key] as Boolean
                    val webViewClient = flutterWebViewClient.createWebViewClient(hasNavigationDelegate)
                    webView.setWebViewClient(webViewClient)
                }
                "debuggingEnabled" -> {
                    // val debuggingEnabled = settings[key] as Boolean
                    // webView.setWebContentsDebuggingEnabled(debuggingEnabled)
                }
                "gestureNavigationEnabled" -> {
                }
                "userAgent" -> updateUserAgent(settings!![key] as String?)
                else -> throw IllegalArgumentException("Unknown WebView setting: $key")
            }
        }
    }
    private fun updateAutoMediaPlaybackPolicy(mode: Int) {
        // This is the index of the AutoMediaPlaybackPolicy enum, index 1 is
        // always_allow, for all
        // other values we require a user gesture.
        val requireUserGesture = mode != 1
        webView.getSettings().setMediaPlaybackRequiresUserGesture(requireUserGesture)
    }

    private fun registerJavaScriptChannelNames(channelNames: List<String>?) {
        for (channelName in channelNames!!) {
            webView.addJavascriptInterface(
                JavaScriptChannel(methodChannel, channelName, platformThreadHandler),
                channelName
            )
        }
    }

    private fun updateUserAgent(userAgent: String?) {
        webView.getSettings().setUserAgentString(userAgent)
    }

    override fun dispose() {
        methodChannel.setMethodCallHandler(null)
        webView.destroy()
    }
}
