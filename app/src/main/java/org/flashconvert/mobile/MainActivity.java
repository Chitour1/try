package org.flashconvert.mobile;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import androidx.webkit.WebViewAssetLoader;
import android.widget.*;
import java.io.*;
import java.util.Locale;
public class MainActivity extends Activity {
  static final int PICK=101, CAPTURE=102, AUDIO=103;
  LinearLayout controls;
  TextView status;
  EditText secs;
  Button choose, export, stop;
  WebView web;
  File movie;
  boolean loaded=false, recording=false;
  MediaProjectionManager manager;
  Handler ui=new Handler(Looper.getMainLooper());
  int duration=60;
  @Override public void onCreate(Bundle b){super.onCreate(b);
    manager=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    LinearLayout root=new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(Color.rgb(17,21,27));
    controls=new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL);
    int p=(int)(12*getResources().getDisplayMetrics().density);
    controls.setPadding(p,p,p,p);
    TextView title=new TextView(this);title.setText("تحويل فلاش SWF إلى MP4");title.setTextColor(Color.WHITE);title.setTextSize(19);title.setGravity(Gravity.CENTER);
    controls.addView(title);
    choose=new Button(this);choose.setText("١. اختيار ملف SWF");
    controls.addView(choose);
    secs=new EditText(this);secs.setHint("المدة بالثواني (تُحسب تلقائيًا)");secs.setSingleLine(true);secs.setInputType(2);secs.setTextColor(Color.WHITE);secs.setText("60");
    controls.addView(secs);
    export=new Button(this);export.setText("٢. تحويل إلى MP4");export.setEnabled(false);controls.addView(export);
    stop=new Button(this);stop.setText("إيقاف التسجيل");controls.addView(stop);
    status=new TextView(this);status.setText("اختر ملفًا من هاتفك. لا يحتاج التطبيق إلى الإنترنت.");status.setTextColor(Color.WHITE);status.setTextSize(14);controls.addView(status);
    root.addView(controls);
    web=new WebView(this);root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
    setContentView(root);
    web.getSettings().setJavaScriptEnabled(true);
    web.getSettings().setDomStorageEnabled(true);
    web.getSettings().setMediaPlaybackRequiresUserGesture(false);
    web.addJavascriptInterface(new Bridge(),"AndroidBridge");
    WebViewAssetLoader loader=new WebViewAssetLoader.Builder()
      .addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(this))
      .addPathHandler("/files/",new WebViewAssetLoader.InternalStoragePathHandler(this,getFilesDir()))
      .build();
    web.setWebViewClient(new WebViewClient(){
      @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){return loader.shouldInterceptRequest(r.getUrl());}
      @Override public void onPageFinished(WebView v,String url){if(movie!=null)web.evaluateJavascript("initMovie()",null);}
    });
    choose.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK);});
    export.setOnClickListener(v->{if(loaded)beginCapture();});
    stop.setOnClickListener(v->finishCapture());
  }
  class Bridge {
    @JavascriptInterface public void ready(){runOnUiThread(()->{loaded=true;export.setEnabled(true);status.setText("جاهز. طول الفيلم التقريبي "+duration+" ثانية. اضغط تحويل.");});}
    @JavascriptInterface public void error(String err){runOnUiThread(()->status.setText("خطأ في الفلاش: "+err));}
  }
  private void beginCapture(){
    if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},AUDIO);return;}
    startActivityForResult(manager.createScreenCaptureIntent(),CAPTURE);
  }
  @Override public void onRequestPermissionsResult(int r,String[] p,int[] grants){super.onRequestPermissionsResult(r,p,grants);if(r==AUDIO)startActivityForResult(manager.createScreenCaptureIntent(),CAPTURE);}
  @Override protected void onActivityResult(int r,int code,Intent data){super.onActivityResult(r,code,data);
    if(r==PICK&&code==RESULT_OK&&data!=null){
      try(InputStream in=getContentResolver().openInputStream(data.getData())) {
        File f=new File(getFilesDir(),"movie.swf");
        try(OutputStream out=new FileOutputStream(f)){byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}
        movie=f;loaded=false;export.setEnabled(false);duration=(int)Math.ceil(SwfMetadata.duration(f));secs.setText(String.valueOf(duration));
        status.setText("تحميل الملف...");web.loadUrl("https://appassets.androidplatform.net/assets/player.html");
      }catch(Exception e){status.setText("تعذّر فتح الملف: "+e.getMessage());}
    }
    if(r==CAPTURE && code==RESULT_OK && data!=null){
      try{duration=Math.max(1,Math.min(3600,Integer.parseInt(secs.getText().toString())));}catch(Exception e){duration=60;}
      controls.setVisibility(View.GONE);
      getWindow().getDecorView().setSystemUiVisibility(5894|1024|512);
      web.evaluateJavascript("pauseMovie()",null);
      Intent job=new Intent(this,RecorderService.class);
      job.setAction("START");job.putExtra("projectionCode",code);job.putExtra("projectionData",data);
      startForegroundService(job);recording=true;
      ui.postDelayed(()->web.evaluateJavascript("playMovie()",null),1400);
      ui.postDelayed(()->finishCapture(),duration*1000L+1800);
    }
  }
  private void finishCapture(){
    if(!recording)return;
    recording=false;web.evaluateJavascript("pauseMovie()",null);
    Intent job=new Intent(this,RecorderService.class);job.setAction("STOP");startService(job);
    getWindow().getDecorView().setSystemUiVisibility(0);
    controls.setVisibility(View.VISIBLE);
    status.setText("يُحفظ الفيديو الآن في مجلد Movies / SWFtoMP4. قد يستغرق دمج الصوت قليلًا.");
  }
}
