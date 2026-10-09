package org.flashconvert.mobile;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.database.Cursor;
import android.os.Environment;
import android.provider.Settings;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.nio.charset.StandardCharsets;
import java.util.*;
import android.os.*;
import android.view.*;
import android.webkit.*;
import androidx.webkit.WebViewAssetLoader;
import android.widget.*;
import java.io.*;
import java.util.Locale;
public class MainActivity extends Activity {
  static final int PICK=101, CAPTURE=102, AUDIO=103, FOLDER=104, STORAGE=105;
  LinearLayout controls;
  TextView status;
  EditText secs;
  Button choose, folder, export, stop;
  WebView web;
  File movie;
  boolean loaded=false, recording=false;
  MediaProjectionManager manager;
  Handler ui=new Handler(Looper.getMainLooper());
  int duration=60;
  private boolean pendingLocalExplorer=false;
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
    choose=new Button(this);choose.setText("١. اختيار SWF (متصفح الملفات داخل التطبيق)");
    controls.addView(choose);
    folder=new Button(this);folder.setText("طريقة بديلة: نافذة الملفات في أندرويد");controls.addView(folder);
    secs=new EditText(this);secs.setHint("المدة بالثواني (تُحسب تلقائيًا)");secs.setSingleLine(true);secs.setInputType(2);secs.setTextColor(Color.WHITE);secs.setText("60");
    controls.addView(secs);
    export=new Button(this);export.setText("٢. تحويل إلى MP4");export.setEnabled(false);controls.addView(export);
    stop=new Button(this);stop.setText("إيقاف التسجيل");controls.addView(stop);
    status=new TextView(this);status.setText("اختر ملفًا من هاتفك. لا يحتاج التطبيق إلى الإنترنت.");status.setTextColor(Color.WHITE);status.setTextSize(14);controls.addView(status);
    TextView help=new TextView(this);help.setText("الخيار الأول يعرض الملفات بأسمائها الفعلية، لا وفق تصنيف سامسونغ. ويمكن مشاركة SWF من تطبيق «ملفاتي» إلى هذا التطبيق.");
    help.setTextColor(Color.rgb(207,219,228));help.setTextSize(12);controls.addView(help);
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
    choose.setOnClickListener(v->openLocalExplorer());
    folder.setOnClickListener(v->openFilePicker());
    export.setOnClickListener(v->{if(loaded)beginCapture();});
    stop.setOnClickListener(v->finishCapture());
    handleIncomingFile(getIntent());
  }
  class Bridge {
    @JavascriptInterface public void ready(){runOnUiThread(()->{loaded=true;export.setEnabled(true);status.setText("جاهز. طول الفيلم التقريبي "+duration+" ثانية. اضغط تحويل.");});}
    @JavascriptInterface public void error(String err){runOnUiThread(()->status.setText("خطأ في الفلاش: "+err));}
  }
  private void beginCapture(){
    if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},AUDIO);return;}
    startActivityForResult(manager.createScreenCaptureIntent(),CAPTURE);
  }
  @Override public void onRequestPermissionsResult(int r,String[] p,int[] grants){super.onRequestPermissionsResult(r,p,grants);if(r==AUDIO)startActivityForResult(manager.createScreenCaptureIntent(),CAPTURE);
    if(r==STORAGE && grants.length>0 && grants[0]==PackageManager.PERMISSION_GRANTED) showLocalDirectory(Environment.getExternalStorageDirectory());}
  @Override protected void onActivityResult(int r,int code,Intent data){super.onActivityResult(r,code,data);
    if(r==PICK && code==RESULT_OK && data!=null && data.getData()!=null){
      loadSwf(data.getData());
    }
    if(r==FOLDER && code==RESULT_OK && data!=null && data.getData()!=null){
      try{
        Uri tree=data.getData();
        browseFolder(tree,DocumentsContract.getTreeDocumentId(tree),new ArrayList<>());
      }catch(Exception e){status.setText("تعذّر عرض هذا المجلد: "+e.getMessage());}
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

  // This is manual browsing, NOT automatic scanning. Android's DocumentsUI
  // may suppress unknown .swf MIME types under Samsung "Recent".
  // The explorer opens one user-selected directory at a time, lists actual
  // File names, and never searches the entire storage behind the user's back.
  private void openLocalExplorer(){
    if(Build.VERSION.SDK_INT>=30 && !Environment.isExternalStorageManager()){
      new AlertDialog.Builder(this)
        .setTitle("السماح بتصفح ملفات الهاتف")
        .setMessage("يعرض التطبيق محتويات المجلد الذي تفتحه بنفسك، بما فيها ملفات SWF التي يخفيها قسم «الأحدث». يحتاج إذن «الوصول إلى جميع الملفات» للعرض المباشر فقط. لا يوجد بحث تلقائي ولا إرسال لأي ملف إلى الإنترنت.")
        .setPositiveButton("السماح بالتصفح",(dialog,which)->{
          pendingLocalExplorer=true;
          try{
            Intent settings=new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            settings.setData(Uri.parse("package:"+getPackageName()));
            startActivity(settings);
          }catch(Exception e){
            try{startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));}
            catch(Exception ignored){pendingLocalExplorer=false;status.setText("تعذّر فتح أذونات الملفات، استعمل الطريقة البديلة.");}
          }
        })
        .setNegativeButton("الطريقة البديلة",(dialog,which)->openFilePicker()).show();
      return;
    }
    if(Build.VERSION.SDK_INT<30 &&
        checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){
      requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},STORAGE);
      return;
    }
    showLocalDirectory(Environment.getExternalStorageDirectory());
  }

  @Override protected void onResume(){
    super.onResume();
    if(pendingLocalExplorer){
      pendingLocalExplorer=false;
      if(Build.VERSION.SDK_INT<30 || Environment.isExternalStorageManager())
        showLocalDirectory(Environment.getExternalStorageDirectory());
      else status.setText("لم يُمنح إذن تصفح الملفات. يمكنك استخدام الطريقة البديلة.");
    }
  }

  // Keep browsing inside shared internal storage; no wildcard or MIME filter.
  private void showLocalDirectory(File selected){
    File root=Environment.getExternalStorageDirectory();
    final String base, current;
    try{
      base=root.getCanonicalPath();
      current=selected.getCanonicalPath();
      if(!current.equals(base) && !current.startsWith(base+File.separator)){
        status.setText("هذا المجلد خارج مساحة التخزين المتاحة.");return;
      }
    }catch(IOException e){status.setText("تعذّر فتح المجلد: "+e.getMessage());return;}
    File[] entries=selected.listFiles();
    if(entries==null){
      status.setText("لا أستطيع قراءة هذا المجلد. جرّب التنزيلات أو الطريقة البديلة.");return;
    }
    // Show directories first, then SWF files, then all other file types.
    // Non-SWF files are intentionally visible so the user can verify location.
    Arrays.sort(entries,(left,right)->{
      if(left.isDirectory()!=right.isDirectory())return left.isDirectory()?-1:1;
      boolean a=left.getName().toLowerCase(Locale.ROOT).endsWith(".swf");
      boolean b=right.getName().toLowerCase(Locale.ROOT).endsWith(".swf");
      if(a!=b)return a?-1:1;
      return left.getName().compareToIgnoreCase(right.getName());
    });
    int visible=Math.min(entries.length,2000);
    boolean hasParent=!current.equals(base);
    String[] names=new String[visible+(hasParent?1:0)];
    if(hasParent)names[0]="⬆ العودة للمجلد السابق";
    int swf=0;
    for(int i=0;i<visible;i++){
      File item=entries[i];
      boolean flash=item.getName().toLowerCase(Locale.ROOT).endsWith(".swf");
      if(flash)swf++;
      names[i+(hasParent?1:0)]=(item.isDirectory()?"📁 ":flash?"🎞️ ":"📄 ")+item.getName();
    }
    String relative=current.equals(base)?"الذاكرة الداخلية":current.substring(base.length()+1);
    status.setText("المجلد: "+relative+" | ملفات SWF الظاهرة: "+swf);
    new AlertDialog.Builder(this)
      .setTitle(relative+" — اختر ملف SWF")
      .setItems(names,(dialog,index)->{
        if(hasParent && index==0){showLocalDirectory(selected.getParentFile());return;}
        File item=entries[index-(hasParent?1:0)];
        if(item.isDirectory())showLocalDirectory(item);
        else loadSwf(Uri.fromFile(item));
      })
      .setPositiveButton("التنزيلات",(dialog,which)->{
        File downloads=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        showLocalDirectory(downloads.isDirectory()?downloads:root);
      })
      .setNeutralButton("الرئيسية",(dialog,which)->showLocalDirectory(root))
      .setNegativeButton("إغلاق",null)
      .show();
  }

  // The system file picker can hide unknown .swf MIME types on some Samsung
  // providers. Keep a broad first choice and add an extension-based SAF browser.
  private void openFilePicker(){
    Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);
    picker.addCategory(Intent.CATEGORY_OPENABLE);
    picker.setType("*/*");
    picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,false);
    try{startActivityForResult(picker,PICK);}
    catch(android.content.ActivityNotFoundException e){
      Intent fallback=new Intent(Intent.ACTION_GET_CONTENT);
      fallback.setType("*/*");fallback.addCategory(Intent.CATEGORY_OPENABLE);
      fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      startActivityForResult(fallback,PICK);
    }
  }

  private void openFolderPicker(){
    Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    try{startActivityForResult(picker,FOLDER);}
    catch(Exception e){status.setText("لا يدعم الهاتف تصفح المجلدات: "+e.getMessage());}
  }

  private static class Entry {
    final String id,name;final boolean directory;
    Entry(String id,String name,boolean directory){this.id=id;this.name=name;this.directory=directory;}
  }

  // List file *names*, not MIME types. Even unknown application/octet-stream
  // SWF documents are selectable. Directory traversal remains through SAF.
  private void browseFolder(Uri tree,String documentId,List<String> parents){
    status.setText("قراءة المجلد...");
    new Thread(()->{
      List<Entry> found=new ArrayList<>();
      String problem=null;
      try{
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,documentId);
        String[] columns={DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                         DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                         DocumentsContract.Document.COLUMN_MIME_TYPE};
        try(Cursor c=getContentResolver().query(children,columns,null,null,null)){
          if(c==null)throw new IOException("لا يمكن قراءة المجلد");
          int idCol=c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
          int nameCol=c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
          int typeCol=c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
          while(c.moveToNext() && found.size()<1500){
            String id=c.getString(idCol);
            String name=c.getString(nameCol);
            String mime=c.getString(typeCol);
            if(name==null||id==null)continue;
            boolean dir=DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
            if(dir||name.toLowerCase(Locale.ROOT).endsWith(".swf"))
              found.add(new Entry(id,name,dir));
          }
        }
      }catch(Exception e){problem=e.getMessage();}
      final String error=problem;
      found.sort((a,b)->{
        if(a.directory!=b.directory)return a.directory?-1:1;
        return a.name.compareToIgnoreCase(b.name);
      });
      runOnUiThread(()->{
        if(error!=null){status.setText("فشل عرض المجلد: "+error);return;}
        status.setText("المجلد يحتوي على "+found.size()+" ملف SWF / مجلد.");
        if(found.isEmpty()&&parents.isEmpty()){
          new AlertDialog.Builder(this).setMessage("لا توجد ملفات SWF في هذا المجلد. اختر مجلدًا آخر، أو شارك ملف SWF من تطبيق ملفاتي.").setPositiveButton("حسنًا",null).show();
          return;
        }
        ArrayList<String> labels=new ArrayList<>();
        if(!parents.isEmpty())labels.add("↩ رجوع إلى المجلد السابق");
        for(Entry entry:found)labels.add((entry.directory?"📁 ":"🎞 ")+entry.name);
        new AlertDialog.Builder(this).setTitle("اختيار SWF من المجلد")
          .setItems(labels.toArray(new String[0]),(dialog,index)->{
            if(!parents.isEmpty() && index==0){
              List<String> previous=new ArrayList<>(parents);
              String up=previous.remove(previous.size()-1);
              browseFolder(tree,up,previous);
              return;
            }
            int i=index-(parents.isEmpty()?0:1);
            Entry selected=found.get(i);
            if(selected.directory){
              List<String> next=new ArrayList<>(parents);next.add(documentId);
              browseFolder(tree,selected.id,next);
            }else{
              loadSwf(DocumentsContract.buildDocumentUriUsingTree(tree,selected.id));
            }
          }).setNegativeButton("إلغاء",null).show();
      });
    },"list-swf-folder").start();
  }

  private void loadSwf(Uri uri){
    if(uri==null)return;
    status.setText("قراءة ملف الفلاش SWF...");
    loaded=false;export.setEnabled(false);
    new Thread(()->{
      try{
        File input=new File(getFilesDir(),"movie.swf");
        try(InputStream in=getContentResolver().openInputStream(uri);
            OutputStream out=new FileOutputStream(input)){
          if(in==null)throw new IOException("الملف غير قابل للفتح");
          byte[] header=new byte[3];
          if(in.read(header)!=3)throw new IOException("الملف فارغ أو غير صالح");
          String signature=new String(header,StandardCharsets.US_ASCII);
          if(!signature.equals("FWS")&&!signature.equals("CWS")&&!signature.equals("ZWS"))
            throw new IOException("الملف المختار ليس SWF. اختر ملفًا ينتهي بـ .swf");
          out.write(header);
          byte[] buf=new byte[65536];int n;
          while((n=in.read(buf))!=-1)out.write(buf,0,n);
        }
        final int filmDuration=(int)Math.ceil(SwfMetadata.duration(input));
        runOnUiThread(()->{
          movie=input;duration=filmDuration;secs.setText(String.valueOf(duration));
          status.setText("جاري تشغيل SWF...");
          web.loadUrl("https://appassets.androidplatform.net/assets/player.html");
        });
      }catch(Exception e){runOnUiThread(()->status.setText("تعذّر فتح SWF: "+e.getMessage()));}
    },"import-swf").start();
  }

  private void handleIncomingFile(Intent intent){
    if(intent==null)return;
    String action=intent.getAction();
    Uri src=null;
    if(Intent.ACTION_SEND.equals(action))src=intent.getParcelableExtra(Intent.EXTRA_STREAM);
    else if(Intent.ACTION_VIEW.equals(action))src=intent.getData();
    if(src!=null)loadSwf(src);
  }

  @Override protected void onNewIntent(Intent intent){
    super.onNewIntent(intent);setIntent(intent);handleIncomingFile(intent);
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
