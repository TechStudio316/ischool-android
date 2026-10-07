package ng.com.schoolrms.ischool;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class MainActivity extends AppCompatActivity {
    private static final String HOME_URL = "https://i.schoolrms.com.ng";
    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private View errorView;
    private View splashView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final long MIN_SPLASH_DURATION_MS = 3000L;
    private String deviceId;
    private String deviceName;
    private final Handler splashHandler = new Handler(Looper.getMainLooper());
    private long splashShownAt;
    private boolean splashDismissScheduled = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Keep the WebView/UI clear of Android system bars (gesture bar / 3-button navigation).
        // Android 15 may lay app content edge-to-edge, so apply the real system-bar insets
        // to the root container instead of using a fixed bottom padding.
        View rootView = findViewById(R.id.rootView);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(rootView);

        splashShownAt = SystemClock.elapsedRealtime();

        webView = findViewById(R.id.webView);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        errorView = findViewById(R.id.errorView);
        splashView = findViewById(R.id.splashView);
        Button retryButton = findViewById(R.id.retryButton);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        // Persistent installation identity used by the iSchool licence server.
        SharedPreferences prefs = getSharedPreferences("ischool_installation", MODE_PRIVATE);
        deviceId = prefs.getString("device_uuid", "");
        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = UUID.randomUUID().toString();
            prefs.edit().putString("device_uuid", deviceId).apply();
        }
        deviceName = Build.MANUFACTURER + " " + Build.MODEL;
        // Cookies are the authoritative transport because login is a normal HTML POST.
        // Wait for WebView to confirm all three cookies before the first page is loaded.
        AtomicInteger licenceCookiesPending = new AtomicInteger(3);
        Runnable cookieReady = () -> {
            if (licenceCookiesPending.decrementAndGet() == 0) {
                cookies.flush();
                if (savedInstanceState == null) {
                    webView.loadUrl(HOME_URL, licenceHeaders());
                } else {
                    webView.restoreState(savedInstanceState);
                }
            }
        };
        setLicenceCookie(cookies, "ischool_device_id", deviceId, cookieReady);
        setLicenceCookie(cookies, "ischool_platform", "android", cookieReady);
        setLicenceCookie(cookies, "ischool_device_name", deviceName, cookieReady);

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost() == null ? "" : uri.getHost();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme();
                if (host.equals("i.schoolrms.com.ng") || host.endsWith(".schoolrms.com.ng")) return false;
                if (scheme.equals("http") || scheme.equals("https") || scheme.equals("mailto") || scheme.equals("tel") || scheme.equals("whatsapp")) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                    return true;
                }
                return false;
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                // Re-assert first-party licence cookies before every navigation.
                ensureLicenceCookies();
                errorView.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
            }
            @Override public void onPageFinished(WebView view, String url) {
                ensureLicenceCookies();
                // Extra fallback: expose the same first-party values through document.cookie.
                // This makes subsequent form POSTs carry the licence identity even on WebView
                // versions that delay CookieManager persistence.
                String js = "document.cookie='ischool_device_id=" + jsEscape(deviceId) + "; Path=/; Secure; SameSite=Lax';" +
                        "document.cookie='ischool_platform=android; Path=/; Secure; SameSite=Lax';" +
                        "document.cookie='ischool_device_name=" + jsEscape(deviceName) + "; Path=/; Secure; SameSite=Lax';";
                view.evaluateJavascript(js, null);
                swipeRefresh.setRefreshing(false);
                if (splashView != null && splashView.getVisibility() == View.VISIBLE && !splashDismissScheduled) {
                    splashDismissScheduled = true;
                    long elapsed = SystemClock.elapsedRealtime() - splashShownAt;
                    long remaining = Math.max(0L, MIN_SPLASH_DURATION_MS - elapsed);
                    splashHandler.postDelayed(() -> {
                        if (splashView != null && splashView.getVisibility() == View.VISIBLE) {
                            splashView.animate()
                                    .alpha(0f)
                                    .setDuration(400)
                                    .withEndAction(() -> splashView.setVisibility(View.GONE))
                                    .start();
                        }
                    }, remaining);
                }
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    swipeRefresh.setRefreshing(false);
                    webView.setVisibility(View.GONE);
                    if (splashView != null) splashView.setVisibility(View.GONE);
                    errorView.setVisibility(View.VISIBLE);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent intent = params.createIntent();
                try { startActivityForResult(intent, FILE_CHOOSER_REQUEST); }
                catch (Exception e) { filePathCallback = null; return false; }
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
        });

        swipeRefresh.setColorSchemeResources(R.color.jamb_green);
        swipeRefresh.setOnRefreshListener(webView::reload);
        swipeRefresh.setOnChildScrollUpCallback((parent, child) -> webView.getScrollY() > 0);
        retryButton.setOnClickListener(v -> { errorView.setVisibility(View.GONE); webView.setVisibility(View.VISIBLE); ensureLicenceCookies(); webView.loadUrl(HOME_URL, licenceHeaders()); });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack(); else finish();
            }
        });

        // Initial page load is intentionally started only after the licence-cookie callbacks above.
    }

    private void setLicenceCookie(CookieManager cookies, String name, String value, Runnable done) {
        String safe = Uri.encode(value == null ? "" : value);
        String cookie = name + "=" + safe + "; Domain=i.schoolrms.com.ng; Path=/; Max-Age=315360000; Secure; SameSite=Lax";
        cookies.setCookie("https://i.schoolrms.com.ng/", cookie, success -> done.run());
    }

    private void ensureLicenceCookies() {
        CookieManager cookies = CookieManager.getInstance();
        String id = Uri.encode(deviceId == null ? "" : deviceId);
        String name = Uri.encode(deviceName == null ? "" : deviceName);
        cookies.setCookie("https://i.schoolrms.com.ng/", "ischool_device_id=" + id + "; Domain=i.schoolrms.com.ng; Path=/; Max-Age=315360000; Secure; SameSite=Lax");
        cookies.setCookie("https://i.schoolrms.com.ng/", "ischool_platform=android; Domain=i.schoolrms.com.ng; Path=/; Max-Age=315360000; Secure; SameSite=Lax");
        cookies.setCookie("https://i.schoolrms.com.ng/", "ischool_device_name=" + name + "; Domain=i.schoolrms.com.ng; Path=/; Max-Age=315360000; Secure; SameSite=Lax");
        cookies.flush();
    }

    private java.util.Map<String, String> licenceHeaders() {
        java.util.HashMap<String, String> headers = new java.util.HashMap<>();
        headers.put("X-ISCHOOL-DEVICE-ID", deviceId == null ? "" : deviceId);
        headers.put("X-ISCHOOL-PLATFORM", "android");
        headers.put("X-ISCHOOL-DEVICE-NAME", deviceName == null ? "" : deviceName);
        return headers;
    }

    private String jsEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("'", "\\'").replace("\r", " ").replace("\n", " ");
    }

    @Override protected void onSaveInstanceState(Bundle outState) { webView.saveState(outState); super.onSaveInstanceState(outState); }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && filePathCallback != null) {
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount(); results = new Uri[count];
                    for (int i = 0; i < count; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) results = new Uri[]{data.getData()};
            }
            filePathCallback.onReceiveValue(results); filePathCallback = null;
        }
    }
}
