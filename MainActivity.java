package dz.stream.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;

public class MainActivity extends Activity {
    private static final int REQ_FILE = 1;
    private static final int REQ_PLAY = 2;
    private int pendingZap = 0;
    private WebView web;
    private View customView;
    private WebChromeClient chrome;
    private ValueCallback<Uri[]> fileCb;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        chrome = new WebChromeClient() {
            @Override
            public void onShowCustomView(View v, CustomViewCallback cb) {
                customView = v;
                setContentView(v);
            }

            @Override
            public void onHideCustomView() {
                setContentView(web);
                customView = null;
            }

            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(Intent.createChooser(i, "M3U / TXT"), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    cb.onReceiveValue(null);
                    return true;
                }
                return true;
            }
        };
        web.setWebChromeClient(chrome);
        web.loadUrl("file:///android_asset/index.html");
        web.requestFocus();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PLAY) {
            if (res == RESULT_OK && data != null) pendingZap = data.getIntExtra("zap", 0);
            return;
        }
        if (req == REQ_FILE && fileCb != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null && data.getData() != null) {
                r = new Uri[]{data.getData()};
            }
            fileCb.onReceiveValue(r);
            fileCb = null;
        }
    }

    // طلبات HTTP مع ترويسات مخصصة (Cookie ...) لا يسمح بها المتصفح
    private class Bridge {
        @JavascriptInterface
        public void playNative(final String url, final String title) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                    i.putExtra("url", url);
                    i.putExtra("title", title);
                    startActivityForResult(i, REQ_PLAY);
                }
            });
        }

        @JavascriptInterface
        public void http(final String id, final String url, final String headersJson) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    int status = -1;
                    String body = "";
                    HttpURLConnection c = null;
                    try {
                        c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(30000);
                        c.setRequestProperty("Accept", "*/*");
                        JSONObject h = new JSONObject(headersJson);
                        Iterator<String> it = h.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            c.setRequestProperty(k, h.getString(k));
                        }
                        status = c.getResponseCode();
                        InputStream is = status >= 400 ? c.getErrorStream() : c.getInputStream();
                        if (is != null) {
                            ByteArrayOutputStream bo = new ByteArrayOutputStream();
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = is.read(buf)) != -1) bo.write(buf, 0, n);
                            body = bo.toString("UTF-8");
                            is.close();
                        }
                    } catch (Exception e) {
                        status = -1;
                        body = String.valueOf(e.getMessage());
                    } finally {
                        if (c != null) c.disconnect();
                    }
                    final String js = "window.__nat(" + JSONObject.quote(id) + "," + status + ","
                            + JSONObject.quote(body) + ")";
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            web.evaluateJavascript(js, null);
                        }
                    });
                }
            }).start();
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (customView != null) {
                chrome.onHideCustomView();
                return true;
            }
            finish();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        if (pendingZap != 0) {
            int z = pendingZap;
            pendingZap = 0;
            web.evaluateJavascript("window.__zap(" + z + ")", null);
        }
    }
}
