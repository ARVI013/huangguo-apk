package com.huangguo.shortdrama;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 黄果短剧 — WebView wrapper.
 *
 * What it adds on top of the website:
 *  - no browser chrome, dark shell matching the site (#080808 / #ffd21c)
 *  - ads removed: third-party trackers/ad domains are blocked at the network layer,
 *    on-site ad slots + promo links are stripped from the HTML before the page scripts run
 *  - native fullscreen video (WebChromeClient custom view)
 *  - automatic fallback to the site's mirror domains when a host is unreachable
 *  - off-site links leave the app (system browser) instead of drifting into ad landing pages
 */
public class MainActivity extends Activity {

    private static final String TAG = "HuangGuoApp";

    /** Entry points, tried in order when a host is unreachable. */
    private static final String[] ENTRY_URLS = {
            "https://huangguoai.com/",
            "https://hgai2.com/",
            "https://l4nliu.pqhybuxo.cc/",
            "https://huangguoai.pages.dev/"
    };

    /** Hosts that belong to the site: kept inside the app. */
    private static final String[] SITE_HOSTS = {
            "huangguoai.com", "hgai2.com", "huangguoai.ai", "huangguoai.pages.dev",
            "pqhybuxo.cc",          // rotating mirror root (e.g. l4nliu.pqhybuxo.cc)
            "wirqed.cn"             // image CDN (pic.wirqed.cn)
    };

    /** Analytics / ad networks / ad landing domains. */
    private static final String[] BLOCKED_HOSTS = {
            "googletagmanager.com", "google-analytics.com", "googleadservices.com",
            "googlesyndication.com", "doubleclick.net", "analytics.ahrefs.com",
            "ahrefs.com", "mc.yandex.ru", "cloudflareinsights.com",
            "ff5yf8n.top", "bmop45.com", "fyxybiblx.cc", "kke8jyk.top",
            "zsharex.com", "ikorimlt.cc"
    };

    private WebView mWeb;
    private ProgressBar mProgress;
    private FrameLayout mCustomContainer;
    private View mCustomView;
    private WebChromeClient.CustomViewCallback mCustomCallback;

    private String mInjectJs = "";
    private String mInjectCss = "";
    private int mEntryIndex = 0;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadInjectAssets();

        FrameLayout root = new FrameLayout(this);

        mWeb = new WebView(this);
        root.addView(mWeb, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mCustomContainer = new FrameLayout(this);
        mCustomContainer.setBackgroundColor(Color.BLACK);
        mCustomContainer.setVisibility(View.GONE);
        root.addView(mCustomContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mProgress.setMax(100);
        mProgress.setVisibility(View.GONE);
        root.addView(mProgress, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        setContentView(root);
        configureWebView();

        if (savedInstanceState != null) {
            mWeb.restoreState(savedInstanceState);
        } else {
            mWeb.loadUrl(ENTRY_URLS[0]);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mWeb != null) {
            mWeb.saveState(outState);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mWeb != null) {
            mWeb.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mWeb != null) {
            mWeb.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (mWeb != null) {
            mWeb.stopLoading();
            View parent = (View) mWeb.getParent();
            if (parent != null) {
                ((ViewGroup) parent).removeView(mWeb);
            }
            mWeb.destroy();
            mWeb = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && handleBack()) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * Android 13+ routes the back gesture here (not through onKeyDown), so both paths
     * must share the same order: leave fullscreen video first, then page history.
     */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (handleBack()) {
            return;
        }
        super.onBackPressed();
    }

    /** @return true when the back press was consumed inside the app. */
    private boolean handleBack() {
        if (mCustomView != null) {
            hideCustomView();
            return true;
        }
        if (mWeb != null && mWeb.canGoBack()) {
            mWeb.goBack();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ webview setup

    private void configureWebView() {
        WebSettings s = mWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setGeolocationEnabled(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // the site already ships a dark theme; do not let the WebView darken it twice.
            // reflective so this compiles against any android.jar (API 29+ method, no-op below)
            try {
                WebSettings.class.getMethod("setForceDarkAllowed", boolean.class).invoke(s, false);
            } catch (Exception ignored) {
            }
        }
        // look like a plain mobile browser instead of a WebView (some CDNs treat "wv" specially)
        String ua = s.getUserAgentString();
        if (ua != null && ua.contains(" wv")) {
            s.setUserAgentString(ua.replace(" wv", ""));
        }

        CookieManager.getInstance().setAcceptCookie(true);

        mWeb.setBackgroundColor(Color.parseColor("#080808"));
        mWeb.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mWeb.setDownloadListener(mDownloadListener);
        mWeb.setWebViewClient(mClient);
        mWeb.setWebChromeClient(mChrome);
    }

    // ------------------------------------------------------------------ ad blocking + document rewrite

    private final WebViewClient mClient = new WebViewClient() {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.US);
            if ("data".equals(scheme) || "about".equals(scheme)) {
                return false;
            }
            if ("http".equals(scheme) || "https".equals(scheme)) {
                String host = u.getHost();
                if (host != null && isSiteHost(host)) {
                    return false; // stay inside the app
                }
                openExternal(u.toString());
                return true;
            }
            if ("mailto".equals(scheme) || "tel".equals(scheme)) {
                openUri(u);
                return true;
            }
            return true; // unknown scheme: swallow it
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            // backup injection in case the document could not be rewritten server-side
            if (mWeb != null) {
                mWeb.postDelayed(mInjectRunnable, 250);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            injectViaJs();
            if (mProgress != null) {
                mProgress.setProgress(100);
                mProgress.setVisibility(View.GONE);
            }
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            String host = u.getHost();
            if (host == null) {
                return null;
            }
            if (isBlockedHost(host)) {
                return emptyResponse(u);
            }
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.US);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return null;
            }
            if (!request.isForMainFrame() || !"GET".equalsIgnoreCase(request.getMethod())) {
                return null;
            }
            if (!isSiteHost(host)) {
                return null;
            }
            return fetchAndRewrite(view, u.toString());
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request != null && request.isForMainFrame()) {
                fallbackToNextEntry(request.getUrl().toString());
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                       WebResourceResponse errorResponse) {
            if (request != null && request.isForMainFrame()) {
                int code = errorResponse == null ? 0 : errorResponse.getStatusCode();
                // Only a failing site *root* means "this mirror is down". A 404 on a normal page
                // is the site's own 404 and must not switch the app to another domain.
                if (code >= 400 && isSiteRoot(request.getUrl())) {
                    fallbackToNextEntry(request.getUrl().toString());
                }
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            String host = error.getUrl() == null ? null : Uri.parse(error.getUrl()).getHost();
            if (host != null && isSiteHost(host)) {
                handler.proceed();
            } else {
                handler.cancel();
            }
        }
    };

    private final Runnable mInjectRunnable = new Runnable() {
        @Override
        public void run() {
            injectViaJs();
        }
    };

    private final DownloadListener mDownloadListener = new DownloadListener() {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                   String mimetype, long contentLength) {
            try {
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                if (mimetype != null && mimetype.length() > 0) {
                    r.setMimeType(mimetype);
                }
                String name = Uri.parse(url).getLastPathSegment();
                if (name == null || name.length() == 0 || name.length() > 80) {
                    name = "huangguo-" + System.currentTimeMillis();
                }
                try {
                    r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                } catch (Exception e) {
                    r.setDestinationInExternalFilesDir(MainActivity.this,
                            Environment.DIRECTORY_DOWNLOADS, name);
                }
                r.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                dm.enqueue(r);
                toast(getString(R.string.download_started));
            } catch (Exception e) {
                openExternal(url);
            }
        }
    };

    private WebResourceResponse fetchAndRewrite(WebView view, String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", view.getSettings().getUserAgentString());
            c.setRequestProperty("Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            c.setRequestProperty("Accept-Encoding", "identity");
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && cookie.length() > 0) {
                c.setRequestProperty("Cookie", cookie);
            }
            int code = c.getResponseCode();
            if (code != 200) {
                return null;
            }
            String ct = c.getContentType();
            if (ct == null || !ct.toLowerCase(Locale.US).contains("text/html")) {
                return null;
            }
            String html = readAll(c.getInputStream(), charsetOf(ct));
            List<String> setCookies = c.getHeaderFields().get("Set-Cookie");
            if (setCookies != null) {
                for (String sc : setCookies) {
                    if (sc != null && sc.length() > 0) {
                        CookieManager.getInstance().setCookie(url, sc);
                    }
                }
            }
            String out = injectIntoHtml(html);
            WebResourceResponse resp = new WebResourceResponse("text/html", "utf-8",
                    new ByteArrayInputStream(out.getBytes("UTF-8")));
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Cache-Control", "no-cache");
            resp.setResponseHeaders(headers);
            return resp;
        } catch (Exception e) {
            Log.w(TAG, "document rewrite failed for " + url + ": " + e.getMessage());
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private String injectIntoHtml(String html) {        String scriptTag = mInjectJs.length() > 0 ? "<script>" + mInjectJs + "</script>" : "";
        String styleTag = mInjectCss.length() > 0
                ? "<style id=\"hg-app-inject\">" + mInjectCss + "</style>" : "";
        if (scriptTag.length() == 0 && styleTag.length() == 0) {
            return html;
        }
        int hs = findHeadTag(html.toLowerCase(Locale.US));
        if (hs >= 0) {
            int gt = html.indexOf('>', hs);
            if (gt > 0) {
                StringBuilder sb = new StringBuilder(
                        html.length() + scriptTag.length() + styleTag.length() + 64);
                sb.append(html, 0, gt + 1);
                sb.append(scriptTag);
                int he = html.toLowerCase(Locale.US).indexOf("</head", gt + 1);
                if (he > 0) {
                    sb.append(html, gt + 1, he);
                    sb.append(styleTag);
                    sb.append(html, he, html.length());
                } else {
                    sb.append(html, gt + 1, html.length());
                    sb.append(styleTag);
                }
                return sb.toString();
            }
        }
        return scriptTag + styleTag + html;
    }

    /**
     * Index of the real &lt;head&gt; tag. A plain indexOf("&lt;head") also matches &lt;header&gt;,
     * which would push the injection into the body of the page.
     */
    private static int findHeadTag(String lowerHtml) {
        int i = 0;
        while (true) {
            i = lowerHtml.indexOf("<head", i);
            if (i < 0) {
                return -1;
            }
            int after = i + 5;
            if (after >= lowerHtml.length()) {
                return -1;
            }
            char c = lowerHtml.charAt(after);
            if (c == '>' || c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '/') {
                return i;
            }
            i = after; // matched <header ... : keep looking
        }
    }

    /** Fallback injection when the document was loaded by the WebView itself. */
    private void injectViaJs() {
        if (mWeb == null) {
            return;
        }
        if (mInjectCss.length() > 0) {
            String css = "if(!document.getElementById('hg-app-inject')){"
                    + "var st=document.createElement('style');st.id='hg-app-inject';"
                    + "st.textContent=" + jsLiteral(mInjectCss) + ";"
                    + "(document.head||document.documentElement).appendChild(st);}";
            mWeb.evaluateJavascript(css, null);
        }
        if (mInjectJs.length() > 0) {
            mWeb.evaluateJavascript(mInjectJs, null);
        }
    }

    // ------------------------------------------------------------------ fullscreen video

    private final WebChromeClient mChrome = new WebChromeClient() {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (mProgress == null) {
                return;
            }
            if (newProgress >= 100) {
                mProgress.setProgress(100);
                mProgress.setVisibility(View.GONE);
            } else {
                if (mProgress.getVisibility() != View.VISIBLE) {
                    mProgress.setVisibility(View.VISIBLE);
                }
                mProgress.setProgress(newProgress);
            }
        }

        @Override
        public void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
            if (mCustomView != null) {
                callback.onCustomViewHidden();
                return;
            }
            mCustomView = view;
            mCustomCallback = callback;
            mWeb.setVisibility(View.GONE);
            mCustomContainer.setVisibility(View.VISIBLE);
            mCustomContainer.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        @Override
        public void onHideCustomView() {
            hideCustomView();
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        request.grant(request.getResources());
                    }
                });
            }
        }
    };

    private void hideCustomView() {
        if (mCustomView == null) {
            return;
        }
        mCustomView.setVisibility(View.GONE);
        mCustomContainer.removeView(mCustomView);
        mCustomView = null;
        mCustomContainer.setVisibility(View.GONE);
        if (mCustomCallback != null) {
            mCustomCallback.onCustomViewHidden();
            mCustomCallback = null;
        }
        if (mWeb != null) {
            mWeb.setVisibility(View.VISIBLE);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ------------------------------------------------------------------ mirror-domain fallback

    private void fallbackToNextEntry(String failedUrl) {
        int start = mEntryIndex;
        String host = Uri.parse(failedUrl).getHost();
        if (host != null) {
            for (int i = 0; i < ENTRY_URLS.length; i++) {
                if (host.equalsIgnoreCase(Uri.parse(ENTRY_URLS[i]).getHost())) {
                    start = i;
                    break;
                }
            }
        }
        int next = start + 1;
        if (next >= ENTRY_URLS.length) {
            toast(getString(R.string.no_connection));
            showOffline();
            return;
        }
        mEntryIndex = next;
        String url = ENTRY_URLS[next];
        toast(getString(R.string.switching_host, Uri.parse(url).getHost()));
        if (mWeb != null) {
            mWeb.loadUrl(url);
        }
    }

    private void showOffline() {
        if (mWeb == null) {
            return;
        }
        // the retry link points at the first entry again, so restart the fallback chain
        mEntryIndex = 0;
        String html = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,"
                + "maximum-scale=1,user-scalable=no,viewport-fit=cover\">"
                + "<style>body{background:#080808;color:#fff;"
                + "font:15px/1.6 -apple-system,system-ui,'PingFang SC','Microsoft YaHei',sans-serif;"
                + "padding:32px 24px;text-align:center}h2{font-size:17px;margin:16px 0 6px}"
                + "p{color:rgba(255,255,255,.55);margin:0}"
                + "a{display:inline-block;margin-top:22px;padding:11px 24px;border-radius:999px;"
                + "background:#ffd21c;color:#111;text-decoration:none;font-weight:600}"
                + "</style></head><body><h2>暂时无法连接</h2>"
                + "<p>黄果短剧站点当前不可访问，请检查网络后重试。</p>"
                + "<a href=\"" + ENTRY_URLS[0] + "\">重新连接</a></body></html>";
        mWeb.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    // ------------------------------------------------------------------ helpers

    /** True for a site root URL ("/"), i.e. the URL whose failure means "this mirror is down". */
    private static boolean isSiteRoot(Uri u) {
        String path = u == null ? null : u.getPath();
        return path == null || path.length() == 0 || "/".equals(path);
    }

    private static boolean isSiteHost(String host) {        String h = host.toLowerCase(Locale.US);
        for (String d : SITE_HOSTS) {
            if (h.equals(d) || h.endsWith("." + d)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlockedHost(String host) {
        String h = host.toLowerCase(Locale.US);
        for (String d : BLOCKED_HOSTS) {
            if (h.equals(d) || h.endsWith("." + d)) {
                return true;
            }
        }
        return false;
    }

    private static WebResourceResponse emptyResponse(Uri u) {
        String path = u.getPath() == null ? "" : u.getPath().toLowerCase(Locale.US);
        String mime = "text/plain";
        if (path.endsWith(".js") || path.endsWith(".mjs")) {
            mime = "application/javascript";
        } else if (path.endsWith(".css")) {
            mime = "text/css";
        } else if (path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg")
                || path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".svg")) {
            mime = "image/png";
        }
        return new WebResourceResponse(mime, "utf-8", new ByteArrayInputStream(new byte[0]));
    }

    private static String readAll(InputStream in, Charset cs) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(128 * 1024);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
        }
        in.close();
        return new String(bo.toByteArray(), cs);
    }

    private static Charset charsetOf(String contentType) {
        try {
            int i = contentType.toLowerCase(Locale.US).indexOf("charset=");
            if (i >= 0) {
                String cs = contentType.substring(i + 8).trim();
                int s = cs.indexOf(';');
                if (s > 0) {
                    cs = cs.substring(0, s);
                }
                cs = cs.replace("\"", "").replace("'", "").trim();
                if (cs.length() > 0) {
                    return Charset.forName(cs);
                }
            }
        } catch (Exception ignored) {
        }
        return Charset.forName("UTF-8");
    }

    private void loadInjectAssets() {
        mInjectJs = readAsset("hg-inject.js");
        mInjectCss = readAsset("hg-inject.css");
    }

    private String readAsset(String name) {
        InputStream in = null;
        try {
            in = getAssets().open(name);
            return readAll(in, Charset.forName("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "missing asset " + name);
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Wrap a string as a JS string literal for evaluateJavascript. */
    private static String jsLiteral(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 32);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private void openExternal(String url) {
        openUri(Uri.parse(url));
    }

    private void openUri(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            toast(getString(R.string.opened_external));
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.no_connection));
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(float value) {
        return (int) (getResources().getDisplayMetrics().density * value + 0.5f);
    }
}
