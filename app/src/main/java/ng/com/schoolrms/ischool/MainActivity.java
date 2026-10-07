package ng.com.schoolrms.ischool;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.UUID;
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
    private final Handler splashHandler = new Handler(Looper.getMainLooper());
    private long splashShownAt;
    private boolean splashDismissScheduled = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
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
        String deviceName = Build.MANUFACTURER + " " + Build.MODEL;
        setLicenceCookie(cookies, "ischool_device_id", deviceId);
        setLicenceCookie(cookies, "ischool_platform", "android");
        setLicenceCookie(cookies, "ischool_device_name", deviceName);
        cookies.flush();

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
                errorView.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
            }
            @Override public void onPageFinished(WebView view, String url) {
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
        retryButton.setOnClickListener(v -> { errorView.setVisibility(View.GONE); webView.setVisibility(View.VISIBLE); webView.loadUrl(HOME_URL); });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack(); else finish();
            }
        });

        if (savedInstanceState == null) webView.loadUrl(HOME_URL); else webView.restoreState(savedInstanceState);
    }

    private void setLicenceCookie(CookieManager cookies, String name, String value) {
        String safe = Uri.encode(value == null ? "" : value);
        cookies.setCookie(HOME_URL, name + "=" + safe + "; Path=/; Secure; SameSite=Lax");
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
