package io.flutter.plugins.webviewflutter

import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.Result

class FlutterWebChromeClient(methodChannel: MethodChannel) : WebChromeClient() {
    private val methodChannel: MethodChannel

    init {
        this.methodChannel = methodChannel
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        System.out.println(
            consoleMessage.message().toString() + " -- From line " + consoleMessage.lineNumber() + " of " +
                consoleMessage.sourceId()
        )
        return super.onConsoleMessage(consoleMessage)
    }

    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
        val args = hashMapOf<String, Any>()
        args.put("message", message ?: "")
        methodChannel.invokeMethod(
            "onJsConfirm", args,
            object : Result {
                override fun success(response: Any?) {
                    val confirm = response as Boolean
                    if (confirm) {
                        result.confirm()
                    } else {
                        result.cancel()
                    }
                }

                override fun notImplemented() {
                    result.cancel()
                }

                override fun error(errorCode: String?, errorMsg: String?, details: Any?) {
                    result.cancel()
                }
            }
        )
        return true
    }

    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
        val args = hashMapOf<String, Any>()
        args.put("message", message ?: "")
        methodChannel.invokeMethod(
            "onJsAlert", args,
            object : Result {
                override fun success(response: Any?) {
                    result.confirm()
                }

                override fun notImplemented() {
                    result.confirm()
                }

                override fun error(errorCode: String?, errorMsg: String?, details: Any?) {
                    result.cancel()
                }
            }
        )
        return true
    }

    override fun onJsPrompt(
        view: WebView?,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult
    ): Boolean {
        val args = hashMapOf<String, Any>()
        args.put("message", message ?: "")
        args.put("default", defaultValue ?: "")
        methodChannel.invokeMethod(
            "onJsPrompt", args,
            object : Result {
                override fun success(response: Any?) {
                    result.confirm(response.toString())
                }

                override fun notImplemented() {
                    result.cancel()
                }

                override fun error(errorCode: String?, errorMsg: String?, details: Any?) {
                    result.cancel()
                }
            }
        )
        return true
    }
}
