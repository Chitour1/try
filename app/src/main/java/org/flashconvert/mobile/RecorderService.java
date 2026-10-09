package org.flashconvert.mobile;
import android.app.*;
import android.content.*;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.*;
import java.io.*;
import java.nio.*;
import java.util.concurrent.atomic.AtomicBoolean;
public class RecorderService extends Service {
  MediaProjection projection;
  VirtualDisplay display;
  MediaRecorder recorder;
  AudioRecord capture;
  Thread audioThread;
  File videoFile, pcmFile, finalFile;
  volatile boolean running=false;
  AtomicBoolean busy=new AtomicBoolean(false);
  Handler main=new Handler(Looper.getMainLooper());
  @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("swf","SWF video conversion",NotificationManager.IMPORTANCE_LOW));}
  @Override public int onStartCommand(Intent i,int f,int id){
    if(i==null)return START_NOT_STICKY;
    if("STOP".equals(i.getAction())){new Thread(this::finish,"save-swf").start();return START_NOT_STICKY;}
    startForeground(7,new Notification.Builder(this,"swf").setContentTitle("SWF to MP4").setContentText("تحويل الفلاش إلى فيديو").setSmallIcon(android.R.drawable.ic_media_play).build());
    try{
      MediaProjectionManager pm=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
      Intent grant=i.getParcelableExtra("projectionData");
      projection=pm.getMediaProjection(i.getIntExtra("projectionCode",Activity.RESULT_CANCELED),grant);
      projection.registerCallback(new MediaProjection.Callback(){@Override public void onStop(){if(!busy.get())new Thread(RecorderService.this::finish).start();}},main);
      DisplayMetrics m=getResources().getDisplayMetrics();
      int origW=m.widthPixels,origH=m.heightPixels;
      double scale=Math.min(1,1080.0/Math.max(origW,origH));
      int w=Math.max(16,((int)(origW*scale)/16)*16),h=Math.max(16,((int)(origH*scale)/16)*16);
      videoFile=new File(getCacheDir(),"swfvid_"+System.currentTimeMillis()+".mp4");
      pcmFile=new File(getCacheDir(),"swfaud_"+System.currentTimeMillis()+".pcm");
      finalFile=new File(getCacheDir(),"swfout_"+System.currentTimeMillis()+".mp4");
      recorder=new MediaRecorder();
      recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
      recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
      recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
      recorder.setVideoSize(w,h);recorder.setVideoFrameRate(30);
      recorder.setVideoEncodingBitRate(4200000);
      recorder.setOutputFile(videoFile.getAbsolutePath());
      recorder.prepare();
      display=projection.createVirtualDisplay("SWFtoMP4",w,h,m.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,recorder.getSurface(),null,null);
      recorder.start();running=true;
      beginAudioCapture();
    }catch(Exception e){Log.e("SWFtoMP4","Start error",e);new Thread(this::finish).start();}
    return START_NOT_STICKY;
  }
  private void beginAudioCapture(){
    try{
      AudioPlaybackCaptureConfiguration config=new AudioPlaybackCaptureConfiguration.Builder(projection)
       .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).build();
      AudioFormat af=new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build();
      capture=new AudioRecord.Builder().setAudioFormat(af).setAudioPlaybackCaptureConfig(config).setBufferSizeInBytes(44100*4).build();
      if(capture.getState()!=AudioRecord.STATE_INITIALIZED){capture.release();capture=null;return;}
      capture.startRecording();
      audioThread=new Thread(()->{
        try(OutputStream out=new BufferedOutputStream(new FileOutputStream(pcmFile))){
          byte[] buf=new byte[8192];
          while(running && capture!=null){
            int n=capture.read(buf,0,buf.length);
            if(n>0)out.write(buf,0,n);
          }
        }catch(Exception e){Log.w("SWFtoMP4","Audio capture stopped",e);}
      },"record-swf-sound");audioThread.start();
    }catch(Exception e){Log.w("SWFtoMP4","No internal audio, recording video only",e);}
  }
  private void finish(){
    if(!busy.compareAndSet(false,true))return;
    running=false;
    try{if(capture!=null){capture.stop();capture.release();capture=null;}}catch(Exception ignored){}
    try{if(audioThread!=null){audioThread.join(1000);audioThread=null;}}catch(Exception ignored){}
    if(recorder!=null){
      try{recorder.stop();}catch(Exception e){Log.w("SWFtoMP4","Recorder stop",e);}
      try{recorder.reset();recorder.release();}catch(Exception ignored){}recorder=null;
    }
    if(display!=null){try{display.release();}catch(Exception ignored){}display=null;}
    if(projection!=null){MediaProjection p=projection;projection=null;try{p.stop();}catch(Exception ignored){}}
    try{
      if(videoFile!=null && videoFile.isFile() && videoFile.length()>1000){
        boolean joined=false;
        if(pcmFile!=null && pcmFile.isFile() && pcmFile.length()>4096)try{File audio=encodeAudio(pcmFile);if(audio!=null){mux(videoFile,audio,finalFile);joined=true;audio.delete();}}catch(Exception e){Log.e("SWFtoMP4","Audio mux failed",e);}
        if(!joined)copy(videoFile,finalFile);
        saveVideo(finalFile);
      }
    }catch(Exception e){Log.e("SWFtoMP4","Final video error",e);}
    for(File f:new File[]{videoFile,pcmFile,finalFile})if(f!=null)f.delete();
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }
  private File encodeAudio(File pcm) throws IOException{
    MediaFormat fmt=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,44100,2);
    fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);
    fmt.setInteger(MediaFormat.KEY_BIT_RATE,128000);
    MediaCodec encoder=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
    File path=new File(getCacheDir(),"swf_encoded_"+System.currentTimeMillis()+".m4a");
    MediaMuxer mux=new MediaMuxer(path.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    int track=-1;boolean muxStarted=false,endInput=false,endOutput=false;
    long framesSent=0;byte[] data=new byte[8192];
    MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
    try(InputStream in=new BufferedInputStream(new FileInputStream(pcm))){
      encoder.configure(fmt,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
      encoder.start();
      while(!endOutput){
        if(!endInput){
          int ix=encoder.dequeueInputBuffer(10000);
          if(ix>=0){
            ByteBuffer buf=encoder.getInputBuffer(ix);buf.clear();
            int n=in.read(data,0,Math.min(data.length,buf.remaining()));
            if(n<0){encoder.queueInputBuffer(ix,0,0,framesSent*1000000L/44100,MediaCodec.BUFFER_FLAG_END_OF_STREAM);endInput=true;}
            else{
              buf.put(data,0,n);
              encoder.queueInputBuffer(ix,0,n,framesSent*1000000L/44100,0);
              framesSent+=n/4;
            }
          }
        }
        int ix=encoder.dequeueOutputBuffer(info,10000);
        if(ix==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){track=mux.addTrack(encoder.getOutputFormat());mux.start();muxStarted=true;}
        else if(ix>=0){
          if(info.size>0 && muxStarted){
            ByteBuffer encoded=encoder.getOutputBuffer(ix);
            encoded.position(info.offset);encoded.limit(info.offset+info.size);
            mux.writeSampleData(track,encoded,info);
          }
          endOutput=(info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
          encoder.releaseOutputBuffer(ix,false);
        }
      }
    }finally{
      try{encoder.stop();}catch(Exception ignored){}encoder.release();
      if(muxStarted)try{mux.stop();}catch(Exception ignored){}
      mux.release();
    }
    return path;
  }
  private void mux(File video,File audio,File out) throws IOException{
    MediaExtractor v=new MediaExtractor(),a=new MediaExtractor();
    MediaMuxer m=null;
    try{
      v.setDataSource(video.getAbsolutePath());a.setDataSource(audio.getAbsolutePath());
      int vi=-1,ai=-1;
      for(int i=0;i<v.getTrackCount();i++)if(v.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("video/"))vi=i;
      for(int i=0;i<a.getTrackCount();i++)if(a.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("audio/"))ai=i;
      if(vi<0||ai<0)throw new IOException("Missing AV track");
      m=new MediaMuxer(out.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
      int vt=m.addTrack(v.getTrackFormat(vi)),at=m.addTrack(a.getTrackFormat(ai));m.start();
      copyTrack(v,vi,m,vt);copyTrack(a,ai,m,at);
      m.stop();
    }finally{v.release();a.release();if(m!=null)m.release();}
  }
  private void copyTrack(MediaExtractor ex,int source,MediaMuxer mux,int dest){
    ex.selectTrack(source);ByteBuffer b=ByteBuffer.allocateDirect(2*1024*1024);
    MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
    while(true){
      b.clear();int n=ex.readSampleData(b,0);if(n<0)break;
      info.set(0,n,ex.getSampleTime(),ex.getSampleFlags());
      mux.writeSampleData(dest,b,info);
      ex.advance();
    }
    ex.unselectTrack(source);
  }
  private void saveVideo(File f) throws IOException{
    ContentValues cv=new ContentValues();
    cv.put(MediaStore.Video.Media.DISPLAY_NAME,"Flash_"+System.currentTimeMillis()+".mp4");
    cv.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");
    cv.put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/SWFtoMP4");
    cv.put(MediaStore.Video.Media.IS_PENDING,1);
    Uri dest=getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,cv);
    if(dest==null)throw new IOException("MediaStore output failed");
    try(InputStream in=new FileInputStream(f);OutputStream out=getContentResolver().openOutputStream(dest)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
    cv.clear();cv.put(MediaStore.Video.Media.IS_PENDING,0);getContentResolver().update(dest,cv,null,null);
    getSystemService(NotificationManager.class).notify(8,new Notification.Builder(this,"swf").setContentTitle("تم تحويل ملف الفلاش").setContentText("الفيديو محفوظ في Movies/SWFtoMP4").setSmallIcon(android.R.drawable.ic_media_play).build());
  }
  private static void copy(File a,File b) throws IOException{try(InputStream in=new FileInputStream(a);OutputStream out=new FileOutputStream(b)){byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}}
  @Override public void onDestroy(){if(!busy.get())new Thread(this::finish).start();super.onDestroy();}
  @Override public IBinder onBind(Intent intent){return null;}
}
