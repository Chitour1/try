package org.flashconvert.mobile;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.os.Environment;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Offline converter UI. No playback, preview, WebView or MediaProjection. */
public class MainActivity extends Activity {
  static final int CHOOSE_SWF=101;
  private TextView status,detail;
  private Button choose,convert,cancel;
  private ProgressBar progress;
  private Uri selected;
  private SwfFileChooser fileChooser;
  private String filename="flash";
  private boolean processing=false;
  private final AtomicBoolean cancelled=new AtomicBoolean(false);
  private final Handler ui=new Handler(Looper.getMainLooper());
  @Override public void onCreate(Bundle saved){
    super.onCreate(saved);
    LinearLayout layout=new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(dp(18),dp(24),dp(18),dp(20));
    layout.setBackgroundColor(Color.rgb(18,24,34));
    TextView title=new TextView(this);
    title.setText("محول SWF إلى MP4");title.setTextSize(25);title.setTextColor(Color.WHITE);
    title.setGravity(Gravity.CENTER);layout.addView(title);
    detail=new TextView(this);
    detail.setText("التحويل يجري داخل الهاتف، دون تسجيل الشاشة. اختَر ملف SWF من المجلدات مباشرة، وليس من قسم «الأحدث».");
    detail.setTextSize(15);detail.setTextColor(Color.rgb(214,226,239));detail.setPadding(0,dp(16),0,dp(18));
    layout.addView(detail);
    choose=new Button(this);choose.setText("١. تصفّح الملفات واختيار SWF");
    layout.addView(choose);
    convert=new Button(this);convert.setText("٢. تحويل إلى MP4");convert.setEnabled(false);
    layout.addView(convert);
    progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
    progress.setMax(1000);progress.setProgress(0);
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(14));lp.setMargins(0,dp(24),0,dp(12));layout.addView(progress,lp);
    status=new TextView(this);
    status.setText("اختر ملف SWF من ذاكرة الهاتف. النتيجة تُحفظ في Movies/SWFtoMP4.");
    status.setTextColor(Color.WHITE);status.setTextSize(15);
    layout.addView(status);
    cancel=new Button(this);cancel.setText("إلغاء التحويل");cancel.setEnabled(false);
    layout.addView(cancel);
    ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
    fileChooser=new SwfFileChooser(this, this::setSource, msg->status.setText(msg));
    choose.setOnClickListener(v->fileChooser.open());
    convert.setOnClickListener(v->startConversion());
    cancel.setOnClickListener(v->{cancelled.set(true);status.setText("جاري إلغاء التحويل...");});
    handleShare(getIntent());
  }
  private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
  @Override protected void onResume(){super.onResume();if(fileChooser!=null)fileChooser.onResume();}
  @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(fileChooser!=null)fileChooser.onRequestPermissionsResult(request,grants);}
  private void handleShare(Intent intent){
    if(intent==null)return;
    Uri u=null;
    if(Intent.ACTION_SEND.equals(intent.getAction()))
      u=intent.getParcelableExtra(Intent.EXTRA_STREAM);
    else if(Intent.ACTION_VIEW.equals(intent.getAction()))
      u=intent.getData();
    if(u!=null)setSource(u);
  }
  @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleShare(intent);}
  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);
    if(fileChooser!=null && fileChooser.onActivityResult(request,result,data))return;
    if(request==CHOOSE_SWF&&result==RESULT_OK&&data!=null&&data.getData()!=null)setSource(data.getData());
  }
  private void setSource(Uri uri){
    if(processing||uri==null)return;
    String name="flash";
    try{
      if("file".equals(uri.getScheme())&&uri.getPath()!=null){
        name=new File(uri.getPath()).getName();
      }else{
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
          if(c!=null&&c.moveToFirst()&&c.getString(0)!=null)name=c.getString(0);
        }
      }
      try(InputStream input=getContentResolver().openInputStream(uri)){
        if(input==null)throw new IOException("الملف غير قابل للقراءة");
        byte[] sig=new byte[3];
        if(input.read(sig)!=3 ||
          !((sig[0]=='F'||sig[0]=='C'||sig[0]=='Z')&&sig[1]=='W'&&sig[2]=='S')){
          status.setText("هذا ليس ملف فلاش SWF صالحًا. اختر ملفًا بامتداد .swf");
          Toast.makeText(this,"ملف غير صالح: يجب أن يكون SWF",Toast.LENGTH_LONG).show();
          return;
        }
      }
      selected=uri;filename=name;
      convert.setEnabled(true);
      status.setText("تم اختيار ملف SWF: "+filename+"\nجاهز للتحويل.");
    }catch(Exception e){
      status.setText("تعذّر قراءة ملف SWF: "+e.getMessage());
    }
  }
  private void setProgress(String text,float ratio){
    ui.post(()->{
      status.setText(text);
      progress.setProgress(Math.max(0,Math.min(1000,Math.round(ratio*1000))));
    });
  }
  private void busy(boolean b){
    processing=b;choose.setEnabled(!b);convert.setEnabled(!b&&selected!=null);cancel.setEnabled(b);
    if(b)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }
  private void startConversion(){
    if(processing||selected==null)return;
    Uri inputUri=selected;
    cancelled.set(false);busy(true);progress.setProgress(0);
    new Thread(()->{
      File input=null,video=null,audio=null,output=null;
      try{
        File dir=new File(getCacheDir(),"work_native");
        if(!dir.exists()&&!dir.mkdirs())throw new IOException("Cannot create temporary working directory");
        input=new File(dir,"source.swf");video=new File(dir,"video.mp4");
        output=new File(dir,"completed.mp4");
        setProgress("قراءة ملف SWF...",0.01f);
        try(InputStream in=getContentResolver().openInputStream(inputUri);
            OutputStream out=new FileOutputStream(input)){
          if(in==null)throw new IOException("Selected file not accessible");
          byte[] buf=new byte[65536];int n;
          while((n=in.read(buf))!=-1){
            if(cancelled.get())throw new InterruptedException("Cancelled");
            out.write(buf,0,n);
          }
        }
        SwfCore swf=SwfCore.open(input);
        if(cancelled.get())throw new InterruptedException("Cancelled");
        final String dimensions=swf.width+" × "+swf.height+" / "+swf.frameCount+" إطار / "+swf.fps+" FPS";
        setProgress("تحليل SWF مكتمل: "+dimensions+"\nالترميز إلى MP4...",0.05f);
        SwfMp4Encoder.encodeVideo(swf,video,new SwfMp4Encoder.Progress(){
          @Override public void onProgress(int complete,int total){
            setProgress("تحويل الإطارات: "+complete+" / "+total+"\n"+dimensions,0.05f+0.78f*complete/Math.max(1f,total));
          }
          @Override public boolean cancelled(){return cancelled.get();}
        });
        if(cancelled.get())throw new InterruptedException("Cancelled");
        setProgress("استخراج الصوت الأصلي وترميزه...",0.85f);
        if(swf.mp3!=null)audio=SwfMp4Encoder.audioToAac(swf.mp3,dir);
        if(cancelled.get())throw new InterruptedException("Cancelled");
        setProgress("دمج الصوت والفيديو...",0.94f);
        SwfMp4Encoder.merge(video,audio,output);
        if(!output.isFile()||output.length()<1024)throw new IOException("The MP4 encoder produced an invalid output");
        Uri saved=saveVideo(output);
        String notes=swf.warnings.isEmpty()?"":"\nملاحظة: يحتوي الفلاش على بعض الخصائص التي قد لا تُعرض مطابقة تمامًا للأصل.";
        setProgress("اكتمل التحويل.\nالملف محفوظ في Movies/SWFtoMP4"+notes,1f);
        ui.post(()->{
          new AlertDialog.Builder(this).setTitle("تم التحويل بنجاح")
            .setMessage("حُفظ ملف MP4 داخل مجلد Movies/SWFtoMP4."+notes)
            .setPositiveButton("فتح الفيديو",(d,w)->{
              Intent open=new Intent(Intent.ACTION_VIEW);
              open.setDataAndType(saved,"video/mp4");open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
              try{startActivity(open);}catch(Exception e){status.setText("تم الحفظ، لكن لا يوجد مشغل لفتح الفيديو.");}
            })
            .setNegativeButton("إغلاق",null).show();
        });
      }catch(InterruptedException e){setProgress("أُلغيت العملية ولم يُحفظ فيديو.",0);}
      catch(Exception e){setProgress("تعذّر التحويل: "+e.getMessage(),0);}
      finally{
        for(File f:new File[]{input,video,audio,output})if(f!=null)f.delete();
        ui.post(()->busy(false));
      }
    },"internal-swf-conversion").start();
  }
  private Uri saveVideo(File file) throws IOException{
    android.content.ContentValues v=new android.content.ContentValues();
    String name=filename.toLowerCase(Locale.ROOT).endsWith(".swf")?filename.substring(0,filename.length()-4):filename;
    name=name.replaceAll("[^A-Za-z0-9_\\-]","_");
    if(name.isEmpty())name="flash";
    v.put(MediaStore.Video.Media.DISPLAY_NAME,name+"_"+System.currentTimeMillis()+".mp4");
    v.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");
    v.put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/SWFtoMP4");
    v.put(MediaStore.Video.Media.IS_PENDING,1);
    Uri dest=getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,v);
    if(dest==null)throw new IOException("Failed creating MP4 in gallery");
    try(InputStream in=new FileInputStream(file);OutputStream out=getContentResolver().openOutputStream(dest)){
      if(out==null)throw new IOException("Cannot open output");
      byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
    }catch(Exception e){getContentResolver().delete(dest,null,null);throw e;}
    v.clear();v.put(MediaStore.Video.Media.IS_PENDING,0);
    getContentResolver().update(dest,v,null,null);
    return dest;
  }
}