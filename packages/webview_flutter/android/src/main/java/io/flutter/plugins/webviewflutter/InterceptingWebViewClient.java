
package io.flutter.plugins.webviewflutter;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

import android.annotation.TargetApi;
import android.os.Build;
import android.graphics.Bitmap;
import android.view.KeyEvent;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebResourceError;

import okhttp3.CookieJar;
import okhttp3.Cookie;
import okhttp3.OkHttpClient;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.HttpUrl;
import okhttp3.Call;


/** WebViewClient that intercepts requests and injects javascript to all frames **/
public class InterceptingWebViewClient extends WebViewClient {

    private FlutterWebViewClient delegate;
    static MediaType jsonContentType = MediaType.parse("application/json; charset=UTF-8");
    private List<UserScript> scripts;
    private OkHttpClient okHttpClient;
    private CookieJar cookies;

    public InterceptingWebViewClient(FlutterWebViewClient delegate, final List<UserScript> scripts) {
        this.delegate = delegate;
        this.scripts = scripts;
        this.cookies = new CookieJar() {
            Map<String, Map<String, Cookie>> map = new HashMap<>();

            @Override
            public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                for (Cookie c : cookies) {
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

                for (Cookie cookie : cc.values()) {
                    if (cookie.hostOnly() && !url.host().equals(cookie.domain()))
                        continue;
                    else if (cookie.httpOnly() && !url.scheme().startsWith("http"))
                        continue;
                    else if (cookie.secure() && !url.isHttps())
                        continue;
                    else if (cookie.expiresAt() < System.currentTimeMillis())
                        continue;
                    else if (!cookie.path().equals("/") && !cookie.path().equals(url.encodedPath()))
                        continue;
                    else
                        cookies.add(cookie);
                }

                return cookies;
            }
        };

        this.okHttpClient = new OkHttpClient.Builder().followRedirects(true).followSslRedirects(true)
                .cookieJar(this.cookies).build();
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        return okhttpFetch(request);
    }

    @TargetApi(Build.VERSION_CODES.N)
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return delegate.shouldOverrideUrlLoading(view, request);
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        delegate.onPageStarted(view, url);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        delegate.onPageFinished(view, url);
    }

    @TargetApi(Build.VERSION_CODES.M)
    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        delegate.onWebResourceError(error.getErrorCode(), error.getDescription().toString(),
                request.getUrl().toString());
    }

    @Override
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        delegate.onWebResourceError(errorCode, description, failingUrl);
    }

    @Override
    public void onUnhandledKeyEvent(WebView view, KeyEvent event) {
        // Deliberately empty. Occasionally the webview will mark events as having
        // failed to be
        // handled even though they were handled. We don't want to propagate those as
        // they're not
        // truly lost.
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

        Request.Builder b = new Request.Builder().url(url);

        // Iterate headers. Extract request body if it exists

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
        
        Call call = okHttpClient.newCall(req);
        return new NonBlockingWebResourceResponse(call, scripts);
    }
}