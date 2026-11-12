package com.independent.akwam.tv;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Message;
import android.net.Uri;
import android.net.http.SslError;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/* Independent, unofficial TV-friendly WebView browser. No films are bundled. */
public final class MainActivity extends Activity {
    private static final String NEW_URL = "https://akwam.ss/";
    private static final String OLD_URL = "https://old.akwam.ss/";
    private static final Set<String> AD_HOSTS = new HashSet<>(Arrays.asList(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adnxs.com", "adsterra.com", "popads.net", "popcash.net",
        "propellerads.com", "onclickads.net", "exoclick.com", "exosrv.com",
        "clickadu.com", "mgid.com", "taboola.com", "outbrain.com",
        "adskeeper.com", "hilltopads.net", "trafficjunky.net"
    ));
    private LinearLayout root, controls;
    private HorizontalScrollView controlScroll;
    private FrameLayout browserArea;
    private TextView status;
    private WebView web;
    private Pointer pointer;
    private Button adButton, pointerButton, scrollButton;
    private View fullscreenVideo;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private boolean blockAds = true;
    private boolean pointerOn = false;
    private boolean toolbarOn = true;
    private int blocked = 0;

    private int dp(float n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        blockAds = getPreferences(MODE_PRIVATE).getBoolean("ads", true);
        pointerOn = false;
        getPreferences(MODE_PRIVATE).edit().putBoolean("pointer", false).apply();
        makeUI();
        setupWeb();
        String last = getPreferences(MODE_PRIVATE).getString("site","new");
        load(last.equals("old") ? OLD_URL : NEW_URL);
    }

    private Button addButton(String label, Runnable click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff25445c));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(48));
        lp.setMargins(dp(3), 0, dp(3), 0);
        controls.addView(b, lp);
        b.setOnClickListener(v -> click.run());
        return b;
    }

    private void makeUI() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(0xff0b1625);
        setContentView(root);

        controlScroll = new HorizontalScrollView(this);
        controlScroll.setHorizontalScrollBarEnabled(false);
        controlScroll.setBackgroundColor(0xff13283a);
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        controls.setPadding(dp(8),dp(6),dp(8),dp(6));
        controlScroll.addView(controls);
        addButton("أكوام الجديد", () -> load(NEW_URL));
        addButton("أكوام القديم", () -> load(OLD_URL));
        scrollButton = addButton("التمرير ✓", () -> setMode(false));
        pointerButton = addButton("المؤشر", () -> setMode(true));
        addButton("رجوع بالموقع", () -> { if (web.canGoBack()) web.goBack(); web.requestFocus(); });
        addButton("تقدم", () -> { if (web.canGoForward()) web.goForward(); web.requestFocus(); });
        addButton("أسفل الصفحة ↓", () -> scrollPage(1));
        addButton("أعلى الصفحة ↑", () -> scrollPage(-1));
        addButton("تحديث", () -> {web.reload();web.requestFocus();});
        adButton = addButton(blockAds ? "حجب الإعلانات: يعمل" : "حجب الإعلانات: متوقف", () -> {
            blockAds = !blockAds;
            getPreferences(MODE_PRIVATE).edit().putBoolean("ads",blockAds).apply();
            adButton.setText(blockAds ? "حجب الإعلانات: يعمل" : "حجب الإعلانات: متوقف");
            web.reload();
            web.requestFocus();
        });
        addButton("إخفاء الأزرار", this::toggleToolbar);
        addButton("إغلاق التطبيق", () -> finish());
        root.addView(controlScroll, new LinearLayout.LayoutParams(-1,dp(62)));

        status = new TextView(this);
        status.setTextColor(0xffd5e6f0);
        status.setTextSize(12);
        status.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        status.setPadding(dp(16),dp(3),dp(16),dp(3));
        status.setSingleLine(true);
        status.setText("التمرير: السهمان أعلى وأسفل | رجوع: قائمة الأزرار | المؤشر: لاختيار الروابط");
        root.addView(status,new LinearLayout.LayoutParams(-1,dp(26)));

        browserArea = new FrameLayout(this);
        root.addView(browserArea,new LinearLayout.LayoutParams(-1,0,1));
        web = new WebView(this);
        web.setBackgroundColor(0xff0b1625);
        web.setFocusableInTouchMode(true);
        browserArea.addView(web,new FrameLayout.LayoutParams(-1,-1));
        pointer = new Pointer();
        pointer.setClickable(false);
        pointer.setFocusable(false);
        browserArea.addView(pointer,new FrameLayout.LayoutParams(-1,-1));
        pointer.setVisibility(pointerOn ? View.VISIBLE : View.GONE);
        web.requestFocus();
    }

    private void updateStatus() {
        if (status == null) return;
        status.setText(pointerOn ?
            "وضع المؤشر: الأسهم تحرك الماوس | OK للاختيار | رجوع للتمرير" :
            "وضع التمرير: أعلى وأسفل لتحريك الصفحة | رجوع للقائمة | اختر المؤشر للضغط");
    }

    private void setMode(boolean mouse) {
        pointerOn = mouse;
        if (pointer != null) pointer.setVisibility(mouse ? View.VISIBLE : View.GONE);
        if (scrollButton != null) scrollButton.setText(mouse ? "التمرير" : "التمرير ✓");
        if (pointerButton != null) pointerButton.setText(mouse ? "المؤشر ✓" : "المؤشر");
        getPreferences(MODE_PRIVATE).edit().putBoolean("pointer",mouse).apply();
        updateStatus();
        if (web != null) web.requestFocus();
        Toast.makeText(this, mouse ? "المؤشر: الأسهم للتحريك وOK للضغط" :
            "التمرير: السهمان أعلى وأسفل لتحريك الصفحة", Toast.LENGTH_SHORT).show();
    }

    private void scrollPage(int direction) {
        if (web == null) return;
        int dy = direction * 360;
        String script = "(function(){var d="+dy+";" +
            "var root=document.scrollingElement||document.documentElement;" +
            "var before=root.scrollTop;" +
            "window.scrollBy(0,d);" +
            "if(root.scrollTop===before){" +
            "var a=document.querySelectorAll('*');" +
            "for(var i=0;i<a.length;i++){" +
            "var e=a[i],s=getComputedStyle(e);" +
            "if((s.overflowY==='auto'||s.overflowY==='scroll')&&e.scrollHeight>e.clientHeight+30){" +
            "e.scrollTop+=d;break;}}" +
            "}})();";
        web.evaluateJavascript(script, null);
    }

    private boolean isAdvertisement(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        for (String a : AD_HOSTS) if (h.equals(a) || h.endsWith("."+a)) return true;
        return false;
    }
    private void cleanPage() {
        String js = "(function(){try{" +
          "var s=document.getElementById('__tv_clean');if(!s){s=document.createElement('style');" +
          "s.id='__tv_clean';s.textContent='iframe[src*=\\\"doubleclick\\\"],iframe[src*=\\\"exoclick\\\"],iframe[src*=\\\"popads\\\"],div[id*=\\\"adsterra\\\"],div[class*=\\\"popunder\\\"]{display:none!important}';" +
          "document.documentElement.appendChild(s)};" +
          "document.querySelectorAll('a[target=\\\"_blank\\\"]').forEach(function(a){a.target='_self'});" +
          "window.open=function(){return null};" +
          "}catch(e){}})();";
        web.evaluateJavascript(js, null);
    }
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!"https".equalsIgnoreCase(u.getScheme())) return true;
                if (blockAds && isAdvertisement(u.getHost())) return true;
                return false;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                if (blockAds && isAdvertisement(r.getUrl().getHost())) {
                    blocked++;
                    return new WebResourceResponse("text/plain","utf-8",new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }
            @Override public void onPageFinished(WebView v, String url) {
                final String label = url.contains("old.akwam") ? "القديم" : "الجديد";
                runOnUiThread(() -> updateStatus());
                if (blockAds) cleanPage();
            }
            @Override public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
                handler.cancel();
                runOnUiThread(() -> Toast.makeText(MainActivity.this,"تم منع صفحة غير آمنة",Toast.LENGTH_LONG).show());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView w,boolean isDialog,boolean userGesture,Message msg) {
                return false;
            }
            @Override public void onPermissionRequest(PermissionRequest request) {request.deny();}
            @Override public void onShowCustomView(View view,CustomViewCallback cb) {
                if (fullscreenVideo != null) {cb.onCustomViewHidden();return;}
                fullscreenVideo = view;
                fullscreenCallback = cb;
                root.setVisibility(View.GONE);
                ((ViewGroup)findViewById(android.R.id.content)).addView(view,new ViewGroup.LayoutParams(-1,-1));
                view.requestFocus();
            }
            @Override public void onHideCustomView() {closeVideo();}
        });
        web.setDownloadListener((url,ua,disp,mime,length) ->
            Toast.makeText(this,"التطبيق مخصص للتصفح وتشغيل الفيديو، ولا يتضمن التحميل",Toast.LENGTH_LONG).show());
    }
    private void load(String url) {
        getPreferences(MODE_PRIVATE).edit().putString("site",url.equals(OLD_URL)?"old":"new").apply();
        blocked=0;web.loadUrl(url);web.requestFocus();
    }
    private void toggleToolbar() {
        toolbarOn = !toolbarOn;
        controlScroll.setVisibility(toolbarOn ? View.VISIBLE : View.GONE);
        status.setVisibility(toolbarOn ? View.VISIBLE : View.GONE);
        web.requestFocus();
    }
    private void focusMenu() {
        if (!toolbarOn) toggleToolbar();
        if (controls.getChildCount()>0) controls.getChildAt(0).requestFocus();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (fullscreenVideo != null && e.getAction()==KeyEvent.ACTION_DOWN && e.getKeyCode()==KeyEvent.KEYCODE_BACK) {
            closeVideo();return true;
        }
        if (e.getAction()==KeyEvent.ACTION_DOWN && e.getKeyCode()==KeyEvent.KEYCODE_MENU) {
            focusMenu();return true;
        }
        if (web != null && e.getAction()==KeyEvent.ACTION_DOWN &&
            (e.getKeyCode()==KeyEvent.KEYCODE_PAGE_DOWN || e.getKeyCode()==KeyEvent.KEYCODE_CHANNEL_DOWN)) {
            web.pageDown(false);return true;
        }
        if (web != null && e.getAction()==KeyEvent.ACTION_DOWN &&
            (e.getKeyCode()==KeyEvent.KEYCODE_PAGE_UP || e.getKeyCode()==KeyEvent.KEYCODE_CHANNEL_UP)) {
            web.pageUp(false);return true;
        }
        if (web != null && web.hasFocus() &&
            e.getKeyCode()==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE &&
            e.getAction()==KeyEvent.ACTION_DOWN) {
            setMode(!pointerOn);
            return true;
        }
        if (!pointerOn && web != null && web.hasFocus() &&
            (e.getKeyCode()==KeyEvent.KEYCODE_DPAD_DOWN ||
             e.getKeyCode()==KeyEvent.KEYCODE_DPAD_UP)) {
            if (e.getAction()==KeyEvent.ACTION_DOWN) {
                scrollPage(e.getKeyCode()==KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            }
            return true;
        }
        if (pointerOn && web != null && web.hasFocus()) {
            int key=e.getKeyCode();
            if (key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT||
                key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN||
                key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER) {
                if (e.getAction()==KeyEvent.ACTION_DOWN) {
                    int step=dp(e.getRepeatCount()>5?64:31);
                    if (key==KeyEvent.KEYCODE_DPAD_LEFT) pointer.move(-step,0);
                    else if (key==KeyEvent.KEYCODE_DPAD_RIGHT) pointer.move(step,0);
                    else if (key==KeyEvent.KEYCODE_DPAD_UP) pointer.move(0,-step);
                    else if (key==KeyEvent.KEYCODE_DPAD_DOWN) pointer.move(0,step);
                    else if (e.getRepeatCount()==0) pointer.click(web);
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }
    @Override public void onBackPressed() {
        if (fullscreenVideo != null) { closeVideo(); return; }
        if (web != null && web.hasFocus()) {
            if (pointerOn) { setMode(false); return; }
            focusMenu(); return;
        }
        if (web != null) web.requestFocus();
    }
    private void closeVideo(){
        if(fullscreenVideo==null)return;
        View v=fullscreenVideo;fullscreenVideo=null;
        ((ViewGroup)findViewById(android.R.id.content)).removeView(v);
        root.setVisibility(View.VISIBLE);
        if(fullscreenCallback!=null){fullscreenCallback.onCustomViewHidden();fullscreenCallback=null;}
        web.requestFocus();
    }
    @Override protected void onDestroy(){
        if(fullscreenVideo!=null)closeVideo();
        if(web!=null){web.stopLoading();web.destroy();}
        super.onDestroy();
    }
    private final class Pointer extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float x=-1,y=-1;
        Pointer(){super(MainActivity.this);setWillNotDraw(false);}
        void init(){if(x<0)x=getWidth()/2f;if(y<0)y=getHeight()/2f;}
        void move(float dx,float dy){
            init();x=Math.max(dp(12),Math.min(getWidth()-dp(12),x+dx));
            y=Math.max(dp(12),Math.min(getHeight()-dp(12),y+dy));invalidate();
        }
        void click(View v){
            init();long now=android.os.SystemClock.uptimeMillis();
            MotionEvent a=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0);
            MotionEvent b=MotionEvent.obtain(now,now+80,MotionEvent.ACTION_UP,x,y,0);
            v.dispatchTouchEvent(a);v.dispatchTouchEvent(b);a.recycle();b.recycle();
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);init();p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(3));
            p.setColor(Color.WHITE);c.drawCircle(x,y,dp(13),p);
            p.setColor(0xff26e0b7);p.setStyle(Paint.Style.FILL);c.drawCircle(x,y,dp(5),p);
        }
    }
}
