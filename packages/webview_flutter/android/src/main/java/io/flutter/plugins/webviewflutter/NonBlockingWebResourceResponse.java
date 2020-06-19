package io.flutter.plugins.webviewflutter;

import android.webkit.WebResourceResponse;
import androidx.annotation.NonNull;
import java.util.concurrent.CountDownLatch;
import java.util.*;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;

import okhttp3.*;

class NonBlockingWebResourceResponse extends WebResourceResponse{
    CountDownLatch latch = new CountDownLatch(1);
    Response response;

    List<UserScript> scripts = Collections.emptyList();

    protected static void log(String method, Object msg) {
        System.out.println(method + ": "+msg.toString());
    }

    protected static void log(Exception msg) {
        msg.printStackTrace();
        log("error", msg.getMessage());
    }


    public NonBlockingWebResourceResponse(final Call call, List<UserScript> userScripts) {

        super("", "", null);

                
        response = new Response.Builder()
                .protocol(Protocol.HTTP_1_1)
                .request(call.request())
                .code(504)
                .message("")
                .body(ResponseBody
                        .create(MediaType.parse("text/html"),""))
                .build();

        if (userScripts != null){            
            this.scripts = userScripts;
        }

        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log("error", call.request().url().toString());
                log(e);
                latch.countDown();
            }

            @Override
            public void onResponse(Call call, Response resp) {
                response = resp;
                log("debug Response body:", resp.toString());
                log("debug Response headers:", resp.headers().toString());
                latch.countDown();
            }
        });
    }

    @Deprecated
    public NonBlockingWebResourceResponse(String mimeType, String encoding, InputStream data) {
        super(mimeType, encoding, data);
    }

    @Deprecated
    public NonBlockingWebResourceResponse(String mimeType, String encoding, int statusCode, @NonNull String reasonPhrase, Map<String, String> responseHeaders, InputStream data) {
        super(mimeType, encoding, statusCode, reasonPhrase, responseHeaders, data);
    }

    private void await() {
        try {
            latch.await();
        } catch (Exception e) {
            log(e);
        }
    }

    @Override
    public String getMimeType() {
        await();

        String mt = "";
        if (response.body() != null && response.body().contentType() != null) {
            MediaType m = response.body().contentType();
            mt = m.type() + "/" + m.subtype();
        }

        return mt;
    }

    @Override
    public String getEncoding() {
        await();

        String ct = "utf-8";
        if (response.body() != null && response.body().contentType() != null && response.body().contentType().charset() != null){
            ct = response.body().contentType().charset().toString().toLowerCase().trim();
        }
        return ct;
    }

    @Override
    public int getStatusCode() {
        await();
        return response.code();
    }

    @Override
    public String getReasonPhrase() {
        await();

        String reason =  response.message();
        if (reason == null) reason = "ok";
        return reason;
    }

    @Override
    public Map<String, String> getResponseHeaders() {
        Map<String, String> headers = new HashMap<>();
//            headers.put("Access-Control-Allow-Origin","*");
        await();

        for (Map.Entry<String, List<String>> entry : response.headers().toMultimap().entrySet()) {
            for (String value : entry.getValue())
                headers.put(entry.getKey(), value);
        }
//            log("debug", "getResponseHeaders: "+headers.toString());
        return headers;
    }

    @Override
    public InputStream getData() {
        InputStream stream = new ByteArrayInputStream(new byte[]{});
        await();
        try {

            stream = response.body().byteStream();
            
            if (getMimeType().contains("html")) {
                InputStream input = response.body().byteStream();
                Document doc = Jsoup.parse(input, null, "/");

                for (UserScript s : scripts) {    
                    
                    doc.head().appendElement("script")
                            .attr("type", "text/javascript")
                            .appendChild(new DataNode(s.source));                    
                }

                stream = new ByteArrayInputStream( doc.html().getBytes());
            }

        } catch (Exception e) {
            log(e);
        }
        return stream;
    }
}