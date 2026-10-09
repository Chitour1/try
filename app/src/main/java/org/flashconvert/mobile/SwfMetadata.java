package org.flashconvert.mobile;
import java.io.*;
import java.util.zip.InflaterInputStream;
public class SwfMetadata {
  public static double duration(File f) {
    try(FileInputStream in=new FileInputStream(f)) {
      byte[] head=new byte[8];if(in.read(head)!=8) return 60;
      InputStream rest=(head[0]=='C' && head[1]=='W' && head[2]=='S')?new InflaterInputStream(in):in;
      if(head[0]!='F' && head[0]!='C') return 60;
      byte[] b=new byte[32];int got=0;while(got<32){int n=rest.read(b,got,32-got);if(n<0)break;got+=n;}
      int bits=(b[0]&0xff)>>3;int rectBits=5+4*bits;int rectBytes=(rectBits+7)/8;
      if(got<rectBytes+4) return 60;
      int fps16=((b[rectBytes+1]&255)<<8)|(b[rectBytes]&255);
      double fps=fps16/256.0;
      int frames=(b[rectBytes+2]&255)|((b[rectBytes+3]&255)<<8);
      return fps>0&&frames>0 ? Math.max(1,Math.min(3600,frames/fps)) :60;
    }catch(Exception e){return 60;}
  }
}
