package org.flashconvert.mobile;

import android.graphics.Bitmap;
import android.media.*;
import android.util.Log;
import java.io.*;
import java.nio.*;
import java.util.*;

final class SwfMp4Encoder {
  interface Progress { void onProgress(int complete,int total); boolean cancelled(); }
  private static final String TAG="SWFEncoder";

  static int encodeVideo(SwfCore swf,File output, Progress cb) throws Exception {
    int w=swf.width,h=swf.height;
    // H.264 macroblocks do not require 16-aligned stage dimensions, but YUV420 needs even dimensions.
    int ew=(w+1)&~1,eh=(h+1)&~1;
    MediaCodec codec=MediaCodec.createEncoderByType("video/avc");
    MediaFormat format=MediaFormat.createVideoFormat("video/avc",ew,eh);
    MediaCodecInfo.CodecCapabilities caps=codec.getCodecInfo().getCapabilitiesForType("video/avc");
    int layout=-1;
    for(int v:caps.colorFormats)if(v==MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar){layout=v;break;}
    if(layout<0)for(int v:caps.colorFormats)if(v==MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar){layout=v;break;}
    if(layout<0)for(int v:caps.colorFormats)if(v==MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible){layout=v;break;}
    if(layout<0){codec.release();throw new IOException("No ByteBuffer YUV420 encoder available");}
    format.setInteger(MediaFormat.KEY_COLOR_FORMAT,layout);
    format.setInteger(MediaFormat.KEY_BIT_RATE,Math.max(400000,Math.min(7500000,w*h*12)));
    format.setInteger(MediaFormat.KEY_FRAME_RATE,Math.max(1,Math.round(swf.fps)));
    format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,2);
    MediaMuxer mux=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
    SwfFrameRenderer frames=new SwfFrameRenderer(swf);
    byte[] data=new byte[ew*eh*3/2];int[] pixels=new int[w*h];
    boolean started=false,eos=false;int track=-1,processed=0;
    long timeout=15000;
    try{
      codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
      codec.start();
      while(!eos){
        if(cb.cancelled())throw new InterruptedException("Conversion cancelled");
        if(processed<=swf.frameCount){
          int index=codec.dequeueInputBuffer(timeout);
          if(index>=0){
            ByteBuffer buffer=codec.getInputBuffer(index);
            if(buffer==null)throw new IOException("Video encoder buffer unavailable");
            buffer.clear();
            long pts=Math.round(processed*1000000.0/swf.fps);
            if(processed==swf.frameCount){
              codec.queueInputBuffer(index,0,0,pts,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              processed++;
            }else{
              Bitmap bitmap=frames.render(processed);
              bitmap.getPixels(pixels,0,w,0,0,w,h);
              bitmap.recycle();
              rgbToYuv(pixels,w,h,data,ew,eh,layout==MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
              if(buffer.remaining()<data.length)throw new IOException("Unexpected video encoder buffer size");
              buffer.put(data);codec.queueInputBuffer(index,0,data.length,pts,0);
              processed++;
              if(processed%12==0 || processed==swf.frameCount)cb.onProgress(processed,swf.frameCount);
            }
          }
        }
        while(true){
          int index=codec.dequeueOutputBuffer(info,1000);
          if(index==MediaCodec.INFO_TRY_AGAIN_LATER)break;
          if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
            if(started)throw new IOException("Encoder output format changed twice");
            track=mux.addTrack(codec.getOutputFormat());mux.start();started=true;
          }else if(index>=0){
            if(info.size>0&&started){
              ByteBuffer sample=codec.getOutputBuffer(index);
              if(sample==null)throw new IOException("Missing encoded packet");
              sample.position(info.offset);sample.limit(info.offset+info.size);
              mux.writeSampleData(track,sample,info);
            }
            eos=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
            codec.releaseOutputBuffer(index,false);
          }
        }
      }
      return processed-1;
    }finally{
      try{codec.stop();}catch(Exception ignored){}
      codec.release();
      if(started)try{mux.stop();}catch(Exception ignored){}
      mux.release();
    }
  }

  static void rgbToYuv(int[] rgb,int width,int height,byte[] yuv,int ew,int eh,boolean semi){
    int ysize=ew*eh;Arrays.fill(yuv,0,ysize,(byte)16);Arrays.fill(yuv,ysize,yuv.length,(byte)128);
    int upos=ysize,vpos=ysize+(ew*eh/4);
    for(int y=0;y<height;y++)for(int x=0;x<width;x++){
      int c=rgb[y*width+x];
      int r=(c>>16)&255,g=(c>>8)&255,b=c&255;
      int yy=((66*r+129*g+25*b+128)>>8)+16;
      yuv[y*ew+x]=(byte)Math.max(16,Math.min(235,yy));
      if((x&1)==0&&(y&1)==0){
        int u=((-38*r-74*g+112*b+128)>>8)+128;
        int v=((112*r-94*g-18*b+128)>>8)+128;
        u=Math.max(0,Math.min(255,u));v=Math.max(0,Math.min(255,v));
        if(semi){int z=ysize+(y/2)*ew+x;yuv[z]=(byte)u;yuv[z+1]=(byte)v;}
        else{int z=(y/2)*(ew/2)+x/2;yuv[upos+z]=(byte)u;yuv[vpos+z]=(byte)v;}
      }
    }
  }

  static File audioToAac(byte[] mp3,File dir) throws Exception {
    if(mp3==null||mp3.length<100) return null;
    File raw=new File(dir,"original_stream.mp3"),dest=new File(dir,"native_sound.m4a");
    try(FileOutputStream out=new FileOutputStream(raw)){out.write(mp3);}
    MediaExtractor extract=new MediaExtractor();
    MediaCodec decoder=null,encoder=null;MediaMuxer mux=null;
    boolean muxStarted=false,decodeDone=false,encodeDone=false,sendEos=false;
    try{
      extract.setDataSource(raw.getAbsolutePath());
      int track=-1;
      for(int i=0;i<extract.getTrackCount();i++){
        if(extract.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("audio/")){track=i;break;}
      }
      if(track<0)return null;
      MediaFormat source=extract.getTrackFormat(track);extract.selectTrack(track);
      String mime=source.getString(MediaFormat.KEY_MIME);
      int rate=source.containsKey(MediaFormat.KEY_SAMPLE_RATE)?source.getInteger(MediaFormat.KEY_SAMPLE_RATE):44100;
      int channels=source.containsKey(MediaFormat.KEY_CHANNEL_COUNT)?source.getInteger(MediaFormat.KEY_CHANNEL_COUNT):1;
      decoder=MediaCodec.createDecoderByType(mime);decoder.configure(source,null,null,0);decoder.start();
      MediaFormat audio=MediaFormat.createAudioFormat("audio/mp4a-latm",rate,channels);
      audio.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);
      audio.setInteger(MediaFormat.KEY_BIT_RATE,128000);
      audio.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,65536);
      encoder=MediaCodec.createEncoderByType("audio/mp4a-latm");
      encoder.configure(audio,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start();
      mux=new MediaMuxer(dest.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
      MediaCodec.BufferInfo di=new MediaCodec.BufferInfo(),ei=new MediaCodec.BufferInfo();
      ArrayDeque<AudioPacket> queue=new ArrayDeque<>();
      int audioTrack=-1,iterations=0;
      boolean encoderEosQueued=false;
      while(!encodeDone){
        if(++iterations>300000)throw new IOException("Audio transcoding stalled");
        if(!sendEos){
          int in=decoder.dequeueInputBuffer(3000);
          if(in>=0){
            ByteBuffer b=decoder.getInputBuffer(in);
            int n=extract.readSampleData(b,0);
            if(n<0){decoder.queueInputBuffer(in,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);sendEos=true;}
            else{
              decoder.queueInputBuffer(in,0,n,extract.getSampleTime(),0);extract.advance();
            }
          }
        }
        if(!decodeDone){
          int out=decoder.dequeueOutputBuffer(di,3000);
          if(out>=0){
            if(di.size>0){
              ByteBuffer b=decoder.getOutputBuffer(out);
              b.position(di.offset);b.limit(di.offset+di.size);
              byte[] packet=new byte[di.size];b.get(packet);
              queue.add(new AudioPacket(packet,di.presentationTimeUs,false));
            }
            decodeDone=(di.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
            decoder.releaseOutputBuffer(out,false);
          }else if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
            MediaFormat f=decoder.getOutputFormat();
            int enc=f.getInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT);
            if(enc!=AudioFormat.ENCODING_PCM_16BIT)throw new IOException("Decoder PCM encoding unsupported: "+enc);
          }
        }
        // Decoder EOS is handled below; no duplicate empty audio packets.
        // Feed the AAC encoder without duplicating any PCM buffers.
        if(!queue.isEmpty()){
          AudioPacket packet=queue.peek();
          int in=encoder.dequeueInputBuffer(3000);
          if(in>=0){
            ByteBuffer b=encoder.getInputBuffer(in);b.clear();
            int n=Math.min(b.remaining(),packet.data.length-packet.offset);
            b.put(packet.data,packet.offset,n);
            encoder.queueInputBuffer(in,0,n,packet.pts+packet.offset*1000000L/(rate*channels*2),
                packet.eos?MediaCodec.BUFFER_FLAG_END_OF_STREAM:0);
            packet.offset+=n;
            if(packet.offset==packet.data.length)queue.remove();
          }
        }else if(decodeDone && !encoderEosQueued){
          int in=encoder.dequeueInputBuffer(3000);
          if(in>=0){
            encoder.queueInputBuffer(in,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            encoderEosQueued=true;
          }
        }
        while(true){
          int out=encoder.dequeueOutputBuffer(ei,1000);
          if(out==MediaCodec.INFO_TRY_AGAIN_LATER)break;
          if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
            audioTrack=mux.addTrack(encoder.getOutputFormat());mux.start();muxStarted=true;
          }else if(out>=0){
            if(ei.size>0&&muxStarted){
              ByteBuffer b=encoder.getOutputBuffer(out);
              b.position(ei.offset);b.limit(ei.offset+ei.size);
              mux.writeSampleData(audioTrack,b,ei);
            }
            encodeDone=(ei.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
            encoder.releaseOutputBuffer(out,false);
          }
        }
      }
      return dest;
    }finally{
      try{extract.release();}catch(Exception ignored){}
      if(decoder!=null){try{decoder.stop();}catch(Exception ignored){}decoder.release();}
      if(encoder!=null){try{encoder.stop();}catch(Exception ignored){}encoder.release();}
      if(mux!=null){if(muxStarted)try{mux.stop();}catch(Exception ignored){}mux.release();}
      raw.delete();
    }
  }
  private static final class AudioPacket{
    byte[] data;long pts;boolean eos;int offset=0;
    AudioPacket(byte[] d,long t,boolean end){data=d;pts=t;eos=end;}
  }

  static void merge(File video,File audio,File target) throws Exception {
    if(audio==null){copy(video,target);return;}
    MediaExtractor v=new MediaExtractor(),a=new MediaExtractor();MediaMuxer mux=null;
    try{
      v.setDataSource(video.getAbsolutePath());a.setDataSource(audio.getAbsolutePath());
      mux=new MediaMuxer(target.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
      int vi=-1,ai=-1;
      for(int i=0;i<v.getTrackCount();i++)if(v.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("video/"))vi=i;
      for(int i=0;i<a.getTrackCount();i++)if(a.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("audio/"))ai=i;
      if(vi<0||ai<0)throw new IOException("Video or audio stream missing");
      int vt=mux.addTrack(v.getTrackFormat(vi)),at=mux.addTrack(a.getTrackFormat(ai));mux.start();
      transfer(v,vi,mux,vt);transfer(a,ai,mux,at);mux.stop();
    }finally{v.release();a.release();if(mux!=null)mux.release();}
  }
  private static void transfer(MediaExtractor ex,int source,MediaMuxer mux,int dest) {
    ex.selectTrack(source);ByteBuffer b=ByteBuffer.allocateDirect(4*1024*1024);
    MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
    while(true){
      b.clear();int n=ex.readSampleData(b,0);if(n<0)break;
      info.set(0,n,ex.getSampleTime(),ex.getSampleFlags());mux.writeSampleData(dest,b,info);
      ex.advance();
    }
    ex.unselectTrack(source);
  }
  private static void copy(File from,File to) throws IOException {
    try(InputStream in=new FileInputStream(from);OutputStream out=new FileOutputStream(to)){
      byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
    }
  }
}