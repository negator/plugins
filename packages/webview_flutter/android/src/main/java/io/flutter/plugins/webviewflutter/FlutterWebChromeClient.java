package io.flutter.plugins.webviewflutter;

import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.ConsoleMessage;
import android.webkit.JsResult;
import android.webkit.JsPromptResult;
import java.util.HashMap;

import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.Result;

public class FlutterWebChromeClient extends WebChromeClient {

    private MethodChannel methodChannel;

    public FlutterWebChromeClient(MethodChannel methodChannel) {
        this.methodChannel = methodChannel;
    }

    @Override
    public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
        System.out.println(consoleMessage.message() + " -- From line " + consoleMessage.lineNumber() + " of "
                + consoleMessage.sourceId());
        return super.onConsoleMessage(consoleMessage);
    }

    @Override
    public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {

        HashMap<String, Object> args = new HashMap<>();
        args.put("message", message);

        methodChannel.invokeMethod("onJsConfirm", args, new Result() {
            @Override
            public void success(Object response) {
                Boolean confirm = (Boolean) response;
                if (confirm) {
                    result.confirm();
                } else {
                    result.cancel();
                }
            }

            @Override
            public void notImplemented() {
                result.cancel();
            }

            @Override
            public void error(String errorCode, String errorMsg, Object details) {
                result.cancel();
            }
        });

        return true;
    }

    @Override
    public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {

        HashMap<String, Object> args = new HashMap<>();
        args.put("message", message);

        methodChannel.invokeMethod("onJsAlert", args, new Result() {
            @Override
            public void success(Object response) {
                result.confirm();
            }

            @Override
            public void notImplemented() {
                result.confirm();
            }

            @Override
            public void error(String errorCode, String errorMsg, Object details) {
                result.cancel();
            }
        });

        return true;
    }

    @Override
    public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
            final JsPromptResult result) {

        HashMap<String, Object> args = new HashMap<>();
        args.put("message", message);
        args.put("default", defaultValue);

        methodChannel.invokeMethod("onJsPrompt", args, new Result() {
            @Override
            public void success(Object response) {
                result.confirm(response.toString());
            }

            @Override
            public void notImplemented() {
                result.cancel();
            }

            @Override
            public void error(String errorCode, String errorMsg, Object details) {
                result.cancel();
            }
        });

        return true;
    }
}