package com.independent.faseltv;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Independent television web directory built from scratch.
 * Not a repackage of the FaselHD Android phone application.
 * Web sources are an editable directory, not embedded app APIs.
 */
public final class MainActivity extends Activity {
    private static final int BG = 0xff0d1020;
    private static final int PANEL = 0xff182036;
    private static final int HIGHLIGHT = 0xff2c6380;
    private static final int WHITE = 0xfff1f5ff;
    private static final String[] SOURCES = {
        "فاصل إعلاني — واجهة ويب", "الرابط المستخرج من التطبيق الأصلي",
        "وي سيما — مستخرج", "مشاهدة — مستخرج"
    };
    private static final String[] URLS = {
        "https://fslhd.co/", "https://www.faselhds.life/",
        "https://wecima.show/", "https://moshahda.net/"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<WebView> hiddenPopups = new ArrayList<>();
    private FrameLayout root;
    private LinearLayout home;
    private LinearLayout browserPage;
    private FrameLayout browserViewport;
    private FrameLayout fullScreen;
    private WebView web;
    private View pointer;
    private TextView pageStatus;
    private WebChromeClient.CustomViewCallback customCallback;
    private boolean inBrowser = false;
    private boolean pointerMode = false;
    private boolean inFullScreen = false;
    private float px = -1, py = -1;
    private int popupsCreated = 0;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);
        makeHome();
        makeBrowser();
        showHome();
    }

    private int dp(float n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }
    private GradientDrawable rounded(int color, int stroke) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        if (stroke != 0) bg.setStroke(dp(2), stroke);
        return bg;
    }
    private TextView text(String value, int size, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextColor(WHITE);
        tv.setTextSize(size);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setTextDirection(View.TEXT_DIRECTION_RTL);
        if (bold) tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return tv;
    }
    private Button button(String name, boolean large) {
        Button b = new Button(this);
        b.setText(name);
        b.setAllCaps(false);
        b.setTextSize(large ? 20 : 15);
        b.setTextColor(WHITE);
        b.setBackground(rounded(PANEL, 0xff334157));
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        b.setFocusable(true);
        b.setFocusableInTouchMode(false);
        b.setOnFocusChangeListener((v, hasFocus) -> {
            v.setBackground(rounded(hasFocus ? HIGHLIGHT : PANEL,
                hasFocus ? 0xff8addff : 0xff334157));
            v.setScaleX(hasFocus ? 1.035f : 1f);
            v.setScaleY(hasFocus ? 1.035f : 1f);
        });
        return b;
    }

    private void makeHome() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        home.setGravity(Gravity.CENTER_HORIZONTAL);
        home.setPadding(dp(58), dp(32), dp(58), dp(30));
        home.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(home);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        scroll.setTag("homeScroll");

        TextView title = text("مكتبة المشاهدة | التلفزيون", 31, true);
        title.setGravity(Gravity.CENTER);
        home.addView(title, new LinearLayout.LayoutParams(-1, dp(68)));
        TextView subtitle = text("تطبيق مستقل من الصفر • اختر مصدرًا، ثم استخدم الريموت أو قائمة روابط الصفحة.", 16, false);
        subtitle.setTextColor(0xffabbacd);
        subtitle.setGravity(Gravity.CENTER);
        home.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(48)));

        TextView note = text("المصادر الثلاثة الأخيرة مستخرجة من APK الأصلي. المصدر الأول موقع ويب منفصل متاح للزيارة، وليس جزءًا من التطبيق الأصلي.", 14, false);
        note.setTextColor(0xffd0bac3);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, dp(56));
        np.setMargins(0, dp(6), 0, dp(20));
        home.addView(note, np);

        for (int start = 0; start < 4; start += 2) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            line.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(92));
            lp.setMargins(0, 0, 0, dp(18));
            home.addView(line, lp);
            for (int j = start; j < start + 2; j++) {
                final int idx = j;
                Button b = button(SOURCES[j], true);
                b.setOnClickListener(v -> open(URLS[idx]));
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, -1, 1f);
                bp.setMargins(dp(10), dp(4), dp(10), dp(4));
                line.addView(b, bp);
                if (j == 0) b.requestFocus();
            }
        }
        Button enter = button("فتح رابط آخر", true);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(dp(340), dp(70));
        ep.setMargins(0, dp(8), 0, 0);
        home.addView(enter, ep);
        enter.setOnClickListener(v -> promptAddress());
    }

    private void makeBrowser() {
        browserPage = new LinearLayout(this);
        browserPage.setOrientation(LinearLayout.VERTICAL);
        browserPage.setBackgroundColor(BG);
        browserPage.setVisibility(View.GONE);
        browserPage.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.addView(browserPage, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        toolbar.setBackgroundColor(0xff111b31);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(10), dp(5), dp(10), dp(5));
        browserPage.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(62)));

        addTool(toolbar, "الرئيسية", () -> showHome());
        addTool(toolbar, "رجوع", () -> {
            if (web.canGoBack()) web.goBack(); else showHome();
        });
        addTool(toolbar, "تحديث", () -> web.reload());
        addTool(toolbar, "روابط الصفحة", this::showPageLinks);
        addTool(toolbar, "المؤشر / التمرير", this::togglePointer);
        addTool(toolbar, "رابط", this::promptAddress);

        browserViewport = new FrameLayout(this);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, 0, 1f);
        browserPage.addView(browserViewport, vp);
        web = new WebView(this);
        browserViewport.addView(web, new FrameLayout.LayoutParams(-1, -1));
        web.setBackgroundColor(0xff14151d);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                pageStatus.setText(url == null ? "" : url);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    Toast.makeText(MainActivity.this,
                        "تعذر تحميل الموقع. جرّب مصدرًا آخر أو تحديث الصفحة.",
                        Toast.LENGTH_LONG).show();
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, Message resultMsg) {
                // Every new tab stays outside the visible viewport.
                if (resultMsg == null) return false;
                if (hiddenPopups.size() >= 4) closePopup(hiddenPopups.get(0));
                WebView popup = new WebView(MainActivity.this);
                popup.setAlpha(0f);
                popup.setTranslationX(-10000f);
                popup.setFocusable(false);
                popup.getSettings().setJavaScriptEnabled(true);
                popup.getSettings().setDomStorageEnabled(true);
                popup.getSettings().setSupportMultipleWindows(true);
                popup.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);
                CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true);
                popup.setWebViewClient(new WebViewClient());
                popup.setWebChromeClient(this);
                browserViewport.addView(popup, new FrameLayout.LayoutParams(dp(1), dp(1)));
                hiddenPopups.add(popup);
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                handler.postDelayed(() -> closePopup(popup), 18000);
                return true;
            }
            @Override public void onCloseWindow(WebView window) {
                closePopup(window);
            }
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (inFullScreen) {
                    callback.onCustomViewHidden();
                    return;
                }
                inFullScreen = true;
                customCallback = callback;
                fullScreen.removeAllViews();
                if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
                fullScreen.addView(view, new FrameLayout.LayoutParams(-1, -1));
                browserPage.setVisibility(View.GONE);
                fullScreen.setVisibility(View.VISIBLE);
                fullScreen.requestFocus();
            }
            @Override public void onHideCustomView() {
                exitFullScreen();
            }
        });
        pointer = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xff66daf1);
        dot.setStroke(dp(3), Color.WHITE);
        pointer.setBackground(dot);
        pointer.setVisibility(View.GONE);
        browserViewport.addView(pointer, new FrameLayout.LayoutParams(dp(18), dp(18)));
        pageStatus = text("", 11, false);
        pageStatus.setTextDirection(View.TEXT_DIRECTION_LTR);
        pageStatus.setTextColor(0xff8d9baa);
        pageStatus.setSingleLine(true);
        pageStatus.setPadding(dp(15), 0, dp(15), 0);
        browserPage.addView(pageStatus, new LinearLayout.LayoutParams(-1, dp(27)));

        fullScreen = new FrameLayout(this);
        fullScreen.setBackgroundColor(Color.BLACK);
        fullScreen.setVisibility(View.GONE);
        root.addView(fullScreen, new FrameLayout.LayoutParams(-1, -1));
    }

    private void addTool(LinearLayout bar, String label, Runnable onClick) {
        Button b = button(label, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        bar.addView(b, lp);
        b.setOnClickListener(v -> {
            onClick.run();
            if (inBrowser && !inFullScreen && browserPage.getVisibility() == View.VISIBLE)
                web.requestFocus();
        });
    }

    private void open(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (!u.startsWith("https://") && !u.startsWith("http://")) {
            Toast.makeText(this, "يجب أن يبدأ العنوان بـ https://", Toast.LENGTH_SHORT).show();
            return;
        }
        inBrowser = true;
        for (int i = 0; i < root.getChildCount(); i++) {
            View v = root.getChildAt(i);
            if (v != browserPage && v != fullScreen) v.setVisibility(View.GONE);
        }
        browserPage.setVisibility(View.VISIBLE);
        fullScreen.setVisibility(View.GONE);
        web.loadUrl(u);
        pointerMode = false;
        pointer.setVisibility(View.GONE);
        web.requestFocus();
    }

    private void showHome() {
        if (inFullScreen) exitFullScreen();
        inBrowser = false;
        pointerMode = false;
        pointer.setVisibility(View.GONE);
        closeAllPopups();
        if (web != null) {
            web.stopLoading();
            web.loadUrl("about:blank");
            web.clearHistory();
        }
        browserPage.setVisibility(View.GONE);
        fullScreen.setVisibility(View.GONE);
        for (int i = 0; i < root.getChildCount(); i++) {
            View v = root.getChildAt(i);
            if (v != browserPage && v != fullScreen) v.setVisibility(View.VISIBLE);
        }
    }

    private void promptAddress() {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(web != null && inBrowser && web.getUrl() != null ? web.getUrl() : "https://");
        field.setSelectAllOnFocus(true);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_VARIATION_URI);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));
        box.addView(field, new FrameLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("فتح رابط")
            .setView(box).setNegativeButton("إلغاء", null)
            .setPositiveButton("فتح", (d, w) -> open(field.getText().toString())).show();
    }

    private void showPageLinks() {
        if (web == null || !inBrowser) return;
        String script = "(function(){try{let a=[],seen={}; document.querySelectorAll('a[href]')"+
            ".forEach(x=>{let u=x.href||'',t=(x.innerText||x.getAttribute('aria-label')||"+
            "(x.querySelector('img')||{}).alt||'').trim().replace(/\\s+/g,' ');"+
            "if(!u.startsWith('http')||!t||seen[u])return;seen[u]=1;a.push({t:t.slice(0,90),u:u});});"+
            "return JSON.stringify(a.slice(0,100));}catch(e){return '[]';}})();";
        web.evaluateJavascript(script, response -> {
            try {
                String json = String.valueOf(new JSONTokener(response).nextValue());
                JSONArray entries = new JSONArray(json);
                ArrayList<String> names = new ArrayList<>();
                ArrayList<String> links = new ArrayList<>();
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject item = entries.optJSONObject(i);
                    if (item == null) continue;
                    names.add(item.optString("t", "صفحة"));
                    links.add(item.optString("u", ""));
                }
                if (names.isEmpty()) {
                    Toast.makeText(this, "لم أجد روابط نصية في هذه الصفحة.", Toast.LENGTH_LONG).show();
                    return;
                }
                new AlertDialog.Builder(this)
                    .setTitle("روابط الصفحة — اختر بالريموت")
                    .setItems(names.toArray(new String[0]), (dialog, which) -> {
                        if (which >= 0 && which < links.size()) {
                            web.loadUrl(links.get(which));
                            web.requestFocus();
                        }
                    })
                    .setNegativeButton("إغلاق", null).show();
            } catch (Exception e) {
                Toast.makeText(this, "تعذرت قراءة روابط هذه الصفحة.", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void togglePointer() {
        if (!inBrowser) return;
        pointerMode = !pointerMode;
        pointer.setVisibility(pointerMode ? View.VISIBLE : View.GONE);
        if (pointerMode) {
            if (px < 0 || py < 0) {
                px = browserViewport.getWidth() / 2f;
                py = browserViewport.getHeight() / 2f;
            }
            updatePointer();
            Toast.makeText(this, "المؤشر: الأسهم للتحريك، OK للنقر، MENU لإخفائه.",
                Toast.LENGTH_LONG).show();
        }
        web.requestFocus();
    }
    private void updatePointer() {
        px = Math.max(dp(8), Math.min(browserViewport.getWidth() - dp(8), px));
        py = Math.max(dp(8), Math.min(browserViewport.getHeight() - dp(8), py));
        pointer.setX(px - dp(9));
        pointer.setY(py - dp(9));
    }
    private void clickAtPointer() {
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t,t, MotionEvent.ACTION_DOWN, px,py,0);
        MotionEvent up = MotionEvent.obtain(t,t+35, MotionEvent.ACTION_UP, px,py,0);
        web.dispatchTouchEvent(down);
        web.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (inBrowser && !inFullScreen && event.getAction() == KeyEvent.ACTION_DOWN) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_MENU) {
                togglePointer();
                return true;
            }
            if (pointerMode && getCurrentFocus() == web) {
                int amount = dp(28 + Math.min(event.getRepeatCount(), 4) * 15);
                switch (key) {
                    case KeyEvent.KEYCODE_DPAD_LEFT: px -= amount; break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT: px += amount; break;
                    case KeyEvent.KEYCODE_DPAD_UP: py -= amount; break;
                    case KeyEvent.KEYCODE_DPAD_DOWN: py += amount; break;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER: clickAtPointer(); return true;
                    default: return super.dispatchKeyEvent(event);
                }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN && py > browserViewport.getHeight()-dp(38)) {
                    web.scrollBy(0, dp(160));
                }
                if (key == KeyEvent.KEYCODE_DPAD_UP && py < dp(38)) {
                    web.scrollBy(0, -dp(160));
                }
                updatePointer();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void exitFullScreen() {
        if (!inFullScreen) return;
        inFullScreen = false;
        fullScreen.removeAllViews();
        fullScreen.setVisibility(View.GONE);
        if (inBrowser) browserPage.setVisibility(View.VISIBLE);
        if (customCallback != null) {
            WebChromeClient.CustomViewCallback cb = customCallback;
            customCallback = null;
            cb.onCustomViewHidden();
        }
    }
    private void closePopup(WebView popup) {
        if (popup == null || !hiddenPopups.remove(popup)) return;
        try {
            popup.stopLoading();
            popup.loadUrl("about:blank");
            if (popup.getParent() instanceof ViewGroup)
                ((ViewGroup) popup.getParent()).removeView(popup);
            popup.destroy();
        } catch (Exception ignored) {}
    }
    private void closeAllPopups() {
        for (WebView popup : new ArrayList<>(hiddenPopups)) closePopup(popup);
    }

    @Override public void onBackPressed() {
        if (inFullScreen) { exitFullScreen(); return; }
        if (inBrowser) {
            if (pointerMode) { togglePointer(); return; }
            if (web.canGoBack()) { web.goBack(); return; }
            showHome();
            return;
        }
        super.onBackPressed();
    }
    @Override protected void onStop() {
        closeAllPopups();
        super.onStop();
    }
    @Override protected void onDestroy() {
        closeAllPopups();
        if (web != null) {
            web.stopLoading();
            web.loadUrl("about:blank");
            web.clearHistory();
            if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
