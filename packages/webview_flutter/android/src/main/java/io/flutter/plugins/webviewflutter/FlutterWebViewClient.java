// Copyright 2019 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.webviewflutter;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.ConsoleMessage;
import android.webkit.JsResult;
import android.webkit.JsPromptResult;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import androidx.webkit.WebViewClientCompat;
import io.flutter.plugin.common.MethodChannel;
import java.util.*;

import okhttp3.*;

// We need to use WebViewClientCompat to get
// shouldOverrideUrlLoading(WebView view, WebResourceRequest request)
// invoked by the webview on older Android devices, without it pages that use iframes will
// be broken when a navigationDelegate is set on Android version earlier than N.
class FlutterWebViewClient {
  private static final String TAG = "FlutterWebViewClient";
  private final MethodChannel methodChannel;
  private boolean hasNavigationDelegate;
  private List<UserScript> userScripts = new ArrayList<UserScript>();

  FlutterWebViewClient(MethodChannel methodChannel) {
    this.methodChannel = methodChannel;
  }

  protected void addUserScripts(List<Map<String, Object>> scripts) {
      for (Map<String, Object> map : scripts) {
        userScripts.add(new UserScript(map));
      }
  }

  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  private boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
    if (!hasNavigationDelegate) {
      return false;
    }
    notifyOnNavigationRequest(
        request.getUrl().toString(), request.getRequestHeaders(), view, request.isForMainFrame());
    // We must make a synchronous decision here whether to allow the navigation or not,
    // if the Dart code has set a navigation delegate we want that delegate to decide whether
    // to navigate or not, and as we cannot get a response from the Dart delegate synchronously we
    // return true here to block the navigation, if the Dart delegate decides to allow the
    // navigation the plugin will later make an addition loadUrl call for this url.
    //
    // Since we cannot call loadUrl for a subframe, we currently only allow the delegate to stop
    // navigations that target the main frame, if the request is not for the main frame
    // we just return false to allow the navigation.
    //
    // For more details see: https://github.com/flutter/flutter/issues/25329#issuecomment-464863209
    return request.isForMainFrame();
  }

  private boolean shouldOverrideUrlLoading(WebView view, String url) {
    if (!hasNavigationDelegate) {
      return false;
    }
    // This version of shouldOverrideUrlLoading is only invoked by the webview on devices with
    // webview versions  earlier than 67(it is also invoked when hasNavigationDelegate is false).
    // On these devices we cannot tell whether the navigation is targeted to the main frame or not.
    // We proceed assuming that the navigation is targeted to the main frame. If the page had any
    // frames they will be loaded in the main frame instead.
    Log.w(
        TAG,
        "Using a navigationDelegate with an old webview implementation, pages with frames or iframes will not work");
    notifyOnNavigationRequest(url, null, view, true);
    return true;
  }

  private void onPageStarted(WebView view, String url) {
    Map<String, Object> args = new HashMap<>();
    args.put("url", url);
    methodChannel.invokeMethod("onPageStarted", args);
  }

  private void onPageFinished(WebView view, String url) {
    Map<String, Object> args = new HashMap<>();
    args.put("url", url);
    methodChannel.invokeMethod("onPageFinished", args);
  }

  private void notifyOnNavigationRequest(
      final String url, final Map<String, String> headers, final WebView webview, boolean isMainFrame) {
    HashMap<String, Object> args = new HashMap<>();
    args.put("url", url);
    args.put("isForMainFrame", isMainFrame);
    if (isMainFrame) {
      methodChannel.invokeMethod("navigationRequest", args, new Result("navigationRequest") {
            @Override
            public void success(Object response) {                
              if (!(Boolean)response) {
                return;
              }
                
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    webview.loadUrl(url, headers);
              } else {
                    webview.loadUrl(url);
              }
            }                                   
          });
    } else {
      methodChannel.invokeMethod("navigationRequest", args);
    }
  }

  WebChromeClient createWebViewChromeClient() {
    return new WebChromeClient() {
        @Override
        public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
            System.out.println(consoleMessage.message() + " -- From line "
                    + consoleMessage.lineNumber() + " of "
                    + consoleMessage.sourceId());
            return super.onConsoleMessage(consoleMessage);
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {

          HashMap<String, Object> args = new HashMap<>();
          args.put("message", message);

          methodChannel.invokeMethod(
            "onJsConfirm", args, new Result("onJsConfirm") {
                  @Override
                  public void success(Object response) {
                    Boolean confirm = (Boolean) response;
                    if (confirm) {
                      result.confirm();
                    } else {
                      result.cancel();
                    }
                  }
            });

          return true;
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {

          HashMap<String, Object> args = new HashMap<>();
          args.put("message", message);

          methodChannel.invokeMethod(
            "onJsAlert", args, new Result("onJsAlert") {
                  @Override
                  public void success(Object response) {
                    result.confirm();
                  }
            });

          return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, final JsPromptResult result) {

          HashMap<String, Object> args = new HashMap<>();
          args.put("message", message);
          args.put("default", defaultValue);

          methodChannel.invokeMethod(
            "onJsPrompt", args, new Result("onJsPrompt") {
                  @Override
                  public void success(Object response) {
                    result.confirm(response.toString());
                  }
            });

          return true;
        }
    };
  }

  // This method attempts to avoid using WebViewClientCompat due to bug
  // https://bugs.chromium.org/p/chromium/issues/detail?id=925887. Also, see
  // https://github.com/flutter/flutter/issues/29446.
  WebViewClient createWebViewClient(boolean hasNavigationDelegate) {
    this.hasNavigationDelegate = hasNavigationDelegate;

    if (!hasNavigationDelegate || android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      return internalCreateWebViewClient(userScripts);
    }

    return internalCreateWebViewClientCompat();
  }

  private WebViewClient internalCreateWebViewClient(final List<UserScript> scripts) {

    final MediaType jsonContentType = MediaType.parse("application/json; charset=UTF-8");

    return new WebViewClient() {      

    private final OkHttpClient okHttpClient = new OkHttpClient.Builder()
              .followRedirects(true)
              .followSslRedirects(true)
              .cookieJar(new CookieJar() {
                Map<String, Map<String,Cookie>> map = new HashMap<>();
                @Override
                public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                  for(Cookie c : cookies) {
                    Map<String, Cookie> cc = map.get(c.domain());
                    if (cc == null) {
                      cc = new HashMap<>();
                      map.put(c.domain(), cc);
                    }
                    cc.put(c.name(), c);
                  }
                }

                @Override
                public List<Cookie> loadForRequest(HttpUrl url) {
                  Map<String, Cookie> cc = map.get(url.topPrivateDomain());
                  List<Cookie> cookies = new ArrayList<>();
                  if (cc == null)
                    return cookies;

                  for (Cookie cookie: cc.values()) {
                    if (cookie.hostOnly() && !url.host().equals(cookie.domain())) continue;
                    else if (cookie.httpOnly() && !url.scheme().startsWith("http")) continue;
                    else if (cookie.secure() && !url.isHttps()) continue;
                    else if (cookie.expiresAt() < System.currentTimeMillis()) continue;
                    else if (!cookie.path().equals("/") && !cookie.path().equals(url.encodedPath()))continue;
                    else cookies.add(cookie);
                  }

                  return cookies;
                }
              })
              .build();
      protected void log(String pfx, String msg) {
        System.out.println(pfx +": "+msg);
      }

      protected void log(Exception msg) {
        msg.printStackTrace();
      }

      @TargetApi(Build.VERSION_CODES.LOLLIPOP)
      @Override
      public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        // return okhttpFetch(request);
        return null;
      }

      @TargetApi(Build.VERSION_CODES.N)
      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return FlutterWebViewClient.this.shouldOverrideUrlLoading(view, request);
      }

      @Override
      public void onPageStarted(WebView view, String url, Bitmap favicon) {
        FlutterWebViewClient.this.onPageStarted(view, url);
      }

      @Override
      public void onPageFinished(WebView view, String url) {       
        FlutterWebViewClient.this.onPageFinished(view, url);
      }

      private WebResourceResponse okhttpFetch(WebResourceRequest request) {

        MediaType contentType = jsonContentType;

        if (request == null || request.getUrl() == null || request.hasGesture()) {
          return null;
        }

        String url = request.getUrl().toString();
        String scheme = request.getUrl().getScheme().toLowerCase().trim();
        String method = request.getMethod() == null ? "GET" : request.getMethod();        

        if (!scheme.equals("http") && !scheme.equals("https")) {
          return null;
        }

        log("debug", "URL: "+url);
        log("debug", "METHOD: "+method);
        log("debug", "Headers: "+request.getRequestHeaders().toString());

        Request.Builder b = new Request.Builder().url(url);

        //Iterate headers. Extract request body if it exists        

        for (Map.Entry<String, String> entry : request.getRequestHeaders().entrySet()) {
          
          String key = entry.getKey().trim().toLowerCase();
          String value = entry.getValue().trim().toLowerCase();

          if (key.equals("accept") && !value.contains("html")) {
            return null;
          } else {
            b = b.addHeader(entry.getKey(), entry.getValue());
          }          
        }

        b.method(method, null);
        
        Request req = b.build();
        log("debug", "Request: "+req.toString());
        log("debug", "Request Headers: "+req.headers().toString());
        Call call = okHttpClient.newCall(req);
        return new NonBlockingWebResourceResponse(call, scripts);
      }

      @Override
      public void onUnhandledKeyEvent(WebView view, KeyEvent event) {
        // Deliberately empty. Occasionally the webview will mark events as having failed to be
        // handled even though they were handled. We don't want to propagate those as they're not
        // truly lost.
      }

    };
  }

  private WebViewClientCompat internalCreateWebViewClientCompat() {
    return new WebViewClientCompat() {
      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return FlutterWebViewClient.this.shouldOverrideUrlLoading(view, request);
      }

      @Override
      public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return FlutterWebViewClient.this.shouldOverrideUrlLoading(view, url);
      }

      @Override
      public void onPageStarted(WebView view, String url, Bitmap favicon) {
        FlutterWebViewClient.this.onPageStarted(view, url);
      }

      @Override
      public void onPageFinished(WebView view, String url) {
        FlutterWebViewClient.this.onPageFinished(view, url);
      }

      @Override
      public void onUnhandledKeyEvent(WebView view, KeyEvent event) {
        // Deliberately empty. Occasionally the webview will mark events as having failed to be
        // handled even though they were handled. We don't want to propagate those as they're not
        // truly lost.
      }
    };
  }

  public abstract class Result implements MethodChannel.Result {

    private final String methodName;
    private Result(String methodName) {
      this.methodName = methodName;
    }

    @Override
    public abstract void success(Object result);

    @Override
    public void error(String errorCode, String s1, Object o) {
      throw new IllegalStateException(methodName + " calls must succeed");
    }

    @Override
    public void notImplemented() {
      throw new IllegalStateException(
          methodName + " must be implemented by the webview method channel");
    }
  }  
}
