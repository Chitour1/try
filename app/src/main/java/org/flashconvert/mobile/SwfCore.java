package org.flashconvert.mobile;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.Log;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * SWF structure reader ported from the user's swf-preserve-mp4 Python skill
 * (swfbase.py + swf_shape.py + timeline in render_swf.py).
 *
 * No Flash runtime, web renderer, screen capture or internet usage.
 * Handles FWS/CWS timeline shapes/sprites/images/fonts/text/MP3 stream blocks.
 */
final class SwfCore {
    static final String TAG="NativeSWF";
    int width, height, frameCount, stageX, stageY;
    float fps;
    int audioSampleRate=11025, audioChannels=1;
    int audioSeekSamples=0;
    int audioBlockCount=0;
    byte[] mp3;
    int background=Color.WHITE;
    final HashMap<Integer,Shape> shapes=new HashMap<>();
    final HashMap<Integer,Bitmap> images=new HashMap<>();
    final HashMap<Integer,Font> fonts=new HashMap<>();
    final HashMap<Integer,TextShape> texts=new HashMap<>();
    final HashMap<Integer,Sprite> sprites=new HashMap<>();
    final ArrayList<ArrayList<Event>> rootFrames=new ArrayList<>();
    final LinkedHashSet<String> warnings=new LinkedHashSet<>();

    static final class R {
        final byte[] a;
        int p,bit;
        R(byte[] input){this(input,0);}
        R(byte[] input,int at){a=input;p=at;}
        void align(){if(bit!=0){p++;bit=0;}}
        int u8(){align();if(p>=a.length)throw new IllegalStateException("Unexpected end of SWF");return a[p++]&255;}
        int u16(){align();if(p+2>a.length)throw new IllegalStateException("Unexpected end of SWF");int v=(a[p]&255)|((a[p+1]&255)<<8);p+=2;return v;}
        int s16(){int v=u16();return v>32767?v-65536:v;}
        long u32(){long lo=u16(),hi=u16();return lo | (hi<<16);}
        byte[] bytes(int n){align();if(n<0||p+n>a.length)throw new IllegalStateException("Invalid SWF record length");byte[] v=Arrays.copyOfRange(a,p,p+n);p+=n;return v;}
        String zstr(){align();int start=p;while(p<a.length&&a[p]!=0)p++;String s=new String(a,start,p-start,java.nio.charset.StandardCharsets.UTF_8);if(p<a.length)p++;return s;}
        int bits(int n){if(n<0||n>31)throw new IllegalStateException("Invalid bit width "+n);int v=0;for(int j=0;j<n;j++){if(p>=a.length)throw new IllegalStateException("Unexpected end of SWF bits");v=(v<<1)|((a[p]>>(7-bit))&1);if(++bit==8){bit=0;p++;}}return v;}
        int sbits(int n){if(n==0)return 0;int v=bits(n);return (v & (1<<(n-1)))!=0? v-(1<<n):v;}
        int[] rect(){int n=bits(5);int[] v={sbits(n),sbits(n),sbits(n),sbits(n)};align();return v;}
        float[] matrix(){
            align();float sx=1,sy=1,r0=0,r1=0;
            if(bits(1)!=0){int n=bits(5);sx=sbits(n)/65536f;sy=sbits(n)/65536f;}
            if(bits(1)!=0){int n=bits(5);r0=sbits(n)/65536f;r1=sbits(n)/65536f;}
            int n=bits(5);int tx=n==0?0:sbits(n);int ty=n==0?0:sbits(n);align();
            return new float[]{sx,r1,r0,sy,tx,ty};
        }
        float[] cx(){
            align();boolean add=bits(1)!=0,mult=bits(1)!=0;int n=bits(4);
            float[] c={1,1,1,1,0,0,0,0};
            if(mult)for(int i=0;i<4;i++)c[i]=sbits(n)/256f;
            if(add)for(int i=0;i<4;i++)c[4+i]=sbits(n);
            align();return c;
        }
    }
    static final class Tag {
        final int type;final byte[] bytes;
        Tag(int t,byte[] b){type=t;bytes=b;}
    }
    static ArrayList<Tag> tags(byte[] data,int start){
        ArrayList<Tag> out=new ArrayList<>();
        R r=new R(data,start);
        while(r.p+2<=data.length){
            int t=r.u16();int kind=t>>>6;int len=t&63;
            if(len==63){long l=r.u32();if(l>100000000L)throw new IllegalStateException("Huge SWF tag");len=(int)l;}
            byte[] body=r.bytes(len);
            out.add(new Tag(kind,body));
            if(kind==0)break;
        }
        return out;
    }
    static int rgb(R r,boolean alpha){
        int red=r.u8(),green=r.u8(),blue=r.u8(),a=alpha?r.u8():255;
        return Color.argb(a,red,green,blue);
    }
    static final class Style {
        int kind,color,imageId,spread,type;
        float[] matrix, positions;
        int[] stops;
        float focal;
    }
    static final class LineStyle {
        int thickness,color;
        LineStyle(int t,int c){thickness=t;color=c;}
    }
    static final class Edge {
        int x,y,mx,my,ex,ey;
        boolean curve;
        Edge(int x,int y,int ex,int ey){this.x=x;this.y=y;this.ex=ex;this.ey=ey;}
        Edge(int x,int y,int mx,int my,int ex,int ey){this(x,y,ex,ey);curve=true;this.mx=mx;this.my=my;}
        Edge reverse(){return curve?new Edge(ex,ey,mx,my,x,y):new Edge(ex,ey,x,y);}
    }
    static final class Shape {
        int id;
        int[] bounds;
        final ArrayList<Style> fills=new ArrayList<>();
        final ArrayList<LineStyle> lines=new ArrayList<>();
        final HashMap<Integer,ArrayList<Edge>> byFill=new HashMap<>();
        final HashMap<Integer,ArrayList<Edge>> byLine=new HashMap<>();
    }
    static final class Font {
        int id, em=1024;
        String name;
        Shape[] glyphs;
    }
    static final class GlyphChar {
        int fontId,glyph,height,x,y,color;
        GlyphChar(int f,int index,int h,int x,int y,int color){fontId=f;glyph=index;height=h;this.x=x;this.y=y;this.color=color;}
    }
    static final class TextShape {
        int id;
        float[] mat;
        ArrayList<GlyphChar> glyphs=new ArrayList<>();
    }
    static final class Event {
        boolean remove;
        int depth,id=-1,clip;
        boolean newId,hasMatrix,hasCx,hasClip;
        float[] mat,cx;
    }
    static final class Sprite {
        int id;
        ArrayList<ArrayList<Event>> frames;
    }
    private static ArrayList<Style> readFills(R r,int version){
        int count=r.u8();if(count==255&&version>=2)count=r.u16();
        ArrayList<Style> output=new ArrayList<>();
        for(int i=0;i<count;i++){
            Style s=new Style();int t=r.u8();s.type=t;
            if(t==0){s.kind=0;s.color=rgb(r,version>=3);}
            else if(t==0x10||t==0x12||t==0x13){
                s.kind=1;s.matrix=r.matrix();r.align();
                s.spread=r.bits(2);r.bits(2);int n=r.bits(4);
                s.positions=new float[n];s.stops=new int[n];
                for(int j=0;j<n;j++){s.positions[j]=r.u8()/255f;s.stops[j]=rgb(r,version>=3);}
                if(t==0x13)s.focal=r.s16()/256f;
            }else if(t>=0x40&&t<=0x43){
                s.kind=2;s.imageId=r.u16();s.matrix=r.matrix();
            }else throw new IllegalStateException("Unsupported SWF fill type "+t);
            output.add(s);
        }
        return output;
    }
    private static ArrayList<LineStyle> readLines(R r,int version){
        int count=r.u8();if(count==255&&version>=2)count=r.u16();
        ArrayList<LineStyle> lines=new ArrayList<>();
        for(int i=0;i<count;i++)lines.add(new LineStyle(r.u16(),rgb(r,version>=3)));
        return lines;
    }
    private static void putEdge(HashMap<Integer,ArrayList<Edge>> map,int style,Edge edge){
        if(style>0)map.computeIfAbsent(style,k->new ArrayList<>()).add(edge);
    }
    // Equivalent to swf_shape.parse_shape: resolve fill0 reverse + fill1 forward.
    private static Shape readRecords(R r,Shape result,int version,boolean glyph){
        int nFill=r.bits(4),nLine=r.bits(4);
        int x=0,y=0,f0=0,f1=0,line=0;
        int processed=0;
        while(true){
            if(++processed>150000)throw new IllegalStateException("SWF shape record limit exceeded");
            if(r.bits(1)!=0){
                boolean straight=r.bits(1)!=0;int n=r.bits(4)+2;
                Edge e;
                if(straight){
                    int dx=0,dy=0;
                    if(r.bits(1)!=0){dx=r.sbits(n);dy=r.sbits(n);}
                    else if(r.bits(1)!=0)dy=r.sbits(n);
                    else dx=r.sbits(n);
                    e=new Edge(x,y,x+dx,y+dy);
                }else{
                    int cx=x+r.sbits(n),cy=y+r.sbits(n);
                    int ex=cx+r.sbits(n),ey=cy+r.sbits(n);
                    e=new Edge(x,y,cx,cy,ex,ey);
                }
                putEdge(result.byFill,f1,e);
                putEdge(result.byFill,f0,e.reverse());
                putEdge(result.byLine,line,e);
                x=e.ex;y=e.ey;
            }else{
                int flags=r.bits(5);
                if(flags==0)break;
                if((flags&1)!=0){int n=r.bits(5);x=r.sbits(n);y=r.sbits(n);}
                if((flags&2)!=0)f0=r.bits(nFill);
                if((flags&4)!=0)f1=r.bits(nFill);
                if((flags&8)!=0)line=r.bits(nLine);
                if((flags&16)!=0){
                    if(glyph)throw new IllegalStateException("Unexpected glyph styles");
                    r.align();result.fills.addAll(readFills(r,version));result.lines.addAll(readLines(r,version));
                    nFill=r.bits(4);nLine=r.bits(4);
                }
            }
        }
        r.align();
        return result;
    }
    private Shape defineShape(int tag,byte[] bytes){
        R r=new R(bytes);
        Shape s=new Shape();s.id=r.u16();s.bounds=r.rect();
        int version=tag==2?1:(tag==22?2:3);
        s.fills.addAll(readFills(r,version));s.lines.addAll(readLines(r,version));
        return readRecords(r,s,version,false);
    }
    private Bitmap bitmap(int tag,byte[] bytes){
        R r=new R(bytes);r.u16();
        if(tag==21){
            byte[] jpg=r.bytes(bytes.length-2);
            int start=0;
            if(jpg.length>=2&&(jpg[0]&255)==255&&(jpg[1]&255)==217)start=2;
            if(jpg.length-start>=4 && (jpg[start]&255)==255&&(jpg[start+1]&255)==216
               &&(jpg[start+2]&255)==255&&(jpg[start+3]&255)==217)start+=4;
            Bitmap b=BitmapFactory.decodeByteArray(jpg,start,jpg.length-start);
            if(b==null)warnings.add("JPEG bitmap could not be decoded");
            return b;
        }
        int fmt=r.u8(),w=r.u16(),h=r.u16();
        if(w<1||h<1||((long)w)*h>10000000)throw new IllegalStateException("Unreasonable bitmap dimensions");
        int count=fmt==3?r.u8()+1:0;
        byte[] compressed=r.bytes(bytes.length-r.p);
        ByteArrayOutputStream sink=new ByteArrayOutputStream();
        try(InflaterInputStream inflate=new InflaterInputStream(new ByteArrayInputStream(compressed))){
            byte[] buf=new byte[16384];int n;
            while((n=inflate.read(buf))!=-1){
                sink.write(buf,0,n);
                if(sink.size()>70000000)throw new IllegalStateException("Huge bitmap expansion");
            }
        }catch(IOException e){throw new IllegalStateException("Bitmap inflate failed",e);}
        byte[] pix=sink.toByteArray();
        int[] pixels=new int[w*h];
        if(fmt==5){
            if(pix.length<w*h*4)throw new IllegalStateException("Truncated ARGB bitmap");
            for(int i=0;i<pixels.length;i++){
                int at=i*4;
                pixels[i]=Color.argb(pix[at]&255,pix[at+1]&255,pix[at+2]&255,pix[at+3]&255);
            }
        }else if(fmt==3){
            int[] colors=new int[count];
            if(pix.length<count*4)throw new IllegalStateException("Truncated palette");
            for(int i=0;i<count;i++){
                int p=i*4;colors[i]=Color.argb(pix[p]&255,pix[p+1]&255,pix[p+2]&255,pix[p+3]&255);
            }
            int pitch=(w+3)&~3,off=count*4;
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                int index=off+y*pitch+x;
                if(index>=pix.length)throw new IllegalStateException("Truncated indexed bitmap");
                pixels[y*w+x]=colors[(pix[index]&255)%colors.length];
            }
        }else throw new IllegalStateException("Unknown lossless bitmap format "+fmt);
        Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        b.setPixels(pixels,0,w,0,0,w,h);
        return b;
    }
    private void defineFont(byte[] bytes){
        R r=new R(bytes);
        Font f=new Font();f.id=r.u16();int flags=r.u8();r.u8();
        int nameLen=r.u8();f.name=new String(r.bytes(nameLen),java.nio.charset.StandardCharsets.UTF_8);
        int count=r.u16();if(count<0||count>10000)throw new IllegalStateException("Font glyph limit");
        f.glyphs=new Shape[count];
        int start=r.p;
        boolean wideOffset=(flags&8)!=0;
        int[] offsets=new int[count];
        for(int i=0;i<count;i++)offsets[i]=(int)(wideOffset?r.u32():r.u16());
        int codeOff=(int)(wideOffset?r.u32():r.u16());
        for(int i=0;i<count;i++){
            int begin=start+offsets[i],end=start+(i+1<count?offsets[i+1]:codeOff);
            if(begin<0||begin>=bytes.length||end>bytes.length||end<begin)continue;
            try{
                Shape glyph=new Shape();glyph.id=i;
                readRecords(new R(bytes,begin),glyph,3,true);
                f.glyphs[i]=glyph;
            }catch(Exception e){warnings.add("Some embedded font glyphs could not be read");}
        }
        fonts.put(f.id,f);
    }
    private void defineText(int tag,byte[] bytes){
        R r=new R(bytes);TextShape t=new TextShape();t.id=r.u16();r.rect();t.mat=r.matrix();
        int glyphBits=r.u8(),advanceBits=r.u8();
        int font=0,px=0,py=0,color=Color.BLACK,size=240;
        while(r.p<bytes.length){
            int flags=r.u8();
            if(flags==0)break;
            if((flags&128)==0)break;
            if((flags&8)!=0)font=r.u16();
            if((flags&4)!=0)color=rgb(r,tag==33);
            if((flags&1)!=0)px=r.s16();
            if((flags&2)!=0)py=r.s16();
            if((flags&8)!=0)size=r.u16();
            int count=r.u8();
            for(int i=0;i<count;i++){
                int glyph=r.bits(glyphBits),advance=r.sbits(advanceBits);
                t.glyphs.add(new GlyphChar(font,glyph,size,px,py,color));
                px+=advance;
            }
            r.align();
        }
        texts.put(t.id,t);
    }
    private static Event place(byte[] bytes){
        R r=new R(bytes);int flags=r.u8();
        Event e=new Event();e.depth=r.u16();
        if((flags&2)!=0){e.newId=true;e.id=r.u16();}
        if((flags&4)!=0){e.hasMatrix=true;e.mat=r.matrix();}
        if((flags&8)!=0){e.hasCx=true;e.cx=r.cx();}
        if((flags&16)!=0)r.u16();
        if((flags&32)!=0)r.zstr();
        if((flags&64)!=0){e.hasClip=true;e.clip=r.u16();}
        return e;
    }
    private ArrayList<ArrayList<Event>> timeline(List<Tag> data){
        ArrayList<ArrayList<Event>> frames=new ArrayList<>();
        ArrayList<Event> events=new ArrayList<>();
        for(Tag tag:data){
            if(tag.type==1){frames.add(events);events=new ArrayList<>();}
            else if(tag.type==26){
                try{events.add(place(tag.bytes));}
                catch(Exception e){warnings.add("A placement record was unsupported");}
            }else if(tag.type==28){
                Event ev=new Event();ev.remove=true;ev.depth=new R(tag.bytes).u16();events.add(ev);
            }else if(tag.type==4||tag.type==5||tag.type==70)warnings.add("Some non-PlaceObject2 placements are not supported");
        }
        return frames;
    }
    private static int parseRate(int index){
        int[] sr={5512,11025,22050,44100};return sr[Math.max(0,Math.min(3,index))];
    }
    private void parseAudio(List<Tag> all){
        ByteArrayOutputStream sound=new ByteArrayOutputStream();
        int format=0;
        for(Tag t:all){
            if(t.type==45||t.type==18){
                if(t.bytes.length>1){
                    format=(t.bytes[1]>>4)&15;
                    audioSampleRate=parseRate((t.bytes[1]>>2)&3);
                    audioChannels=(t.bytes[1]&1)==0?1:2;
                }
            }else if(t.type==19){
                // SoundStreamBlock MP3 = samples UI16 + signed seek UI16 + MP3 payload
                if(format==2&&t.bytes.length>4){
                    if(audioBlockCount==0) audioSeekSamples=(short)((t.bytes[2]&255)|((t.bytes[3]&255)<<8));
                    audioBlockCount++;
                    sound.write(t.bytes,4,t.bytes.length-4);
                }
            }else if(t.type==14&&sound.size()==0){
                if(t.bytes.length>10){
                    int info=t.bytes[2]&255;
                    int codec=(info>>4)&15;
                    if(codec==2){
                        audioSampleRate=parseRate((info>>2)&3);audioChannels=(info&1)==0?1:2;
                        sound.write(t.bytes,9,t.bytes.length-9);
                    }
                }
            }
        }
        if(sound.size()>0)mp3=sound.toByteArray();
        else if(format!=0)warnings.add("An unsupported SWF audio codec was encountered");
    }
    static SwfCore open(File f) throws IOException {
        byte[] header=new byte[8];
        try(FileInputStream in=new FileInputStream(f)){
            int count=in.read(header);
            if(count!=8)throw new IOException("Invalid SWF header");
        }
        String magic=new String(header,0,3,java.nio.charset.StandardCharsets.US_ASCII);
        if(!magic.equals("FWS")&&!magic.equals("CWS"))throw new IOException("Only FWS / CWS Flash movies are supported");
        long expected=(header[4]&255L)|((header[5]&255L)<<8)|((header[6]&255L)<<16)|((header[7]&255L)<<24);
        if(expected<12||expected>150000000)throw new IOException("Unsupported SWF decompressed size");
        ByteArrayOutputStream expanded=new ByteArrayOutputStream((int)Math.min(12000000,expected));
        try(FileInputStream file=new FileInputStream(f)){
            byte[] skip=new byte[8];if(file.read(skip)!=8)throw new IOException("Short file");
            InputStream input=magic.equals("CWS")?new InflaterInputStream(file):file;
            expanded.write(header,0,8);
            byte[] chunk=new byte[32768];int n;
            while((n=input.read(chunk))!=-1){
                expanded.write(chunk,0,n);
                if(expanded.size()>150000000)throw new IOException("SWF decompression limit exceeded");
            }
            if(input!=file)input.close();
        }
        byte[] decoded=expanded.toByteArray();
        R r=new R(decoded,8);
        SwfCore movie=new SwfCore();
        int[] rect=r.rect();movie.stageX=rect[0];movie.stageY=rect[2];
        movie.width=Math.round((rect[1]-rect[0])/20f);
        movie.height=Math.round((rect[3]-rect[2])/20f);
        movie.fps=r.u16()/256f;movie.frameCount=r.u16();
        if(movie.width<1||movie.height<1||movie.width>2048||movie.height>2048||
           movie.fps<=0||movie.fps>120||movie.frameCount<=0||movie.frameCount>30000)
            throw new IOException("Unsupported movie stage or frame rate");
        ArrayList<Tag> root=tags(decoded,r.p);
        for(Tag tag:root){
            try{
                if(tag.type==2||tag.type==22||tag.type==32){
                    Shape s=movie.defineShape(tag.type,tag.bytes);
                    movie.shapes.put(s.id,s);
                }else if(tag.type==21||tag.type==36){
                    int id=(tag.bytes[0]&255)|((tag.bytes[1]&255)<<8);
                    Bitmap b=movie.bitmap(tag.type,tag.bytes);if(b!=null)movie.images.put(id,b);
                }else if(tag.type==39){
                    R sub=new R(tag.bytes);
                    Sprite sprite=new Sprite();sprite.id=sub.u16();sub.u16();
                    sprite.frames=movie.timeline(tags(tag.bytes,sub.p));
                    movie.sprites.put(sprite.id,sprite);
                }else if(tag.type==48){
                    movie.defineFont(tag.bytes);
                }else if(tag.type==33||tag.type==11){
                    movie.defineText(tag.type,tag.bytes);
                }else if(tag.type==37){
                    movie.warnings.add("Editable SWF text has limited support");
                }else if(tag.type==9){
                    R color=new R(tag.bytes);movie.background=rgb(color,false);
                }else if(tag.type==12||tag.type==82)movie.warnings.add("ActionScript is not executed; dynamically controlled movies may differ");
                else if(tag.type==46||tag.type==84||tag.type==83||tag.type==60||tag.type==61){
                    movie.warnings.add("The movie contains advanced SWF effects not rendered by this converter");
                }
            }catch(Exception e){movie.warnings.add("SWF definition "+tag.type+" omitted: "+e.getMessage());Log.w(TAG,"SWF definition "+tag.type,e);}
        }
        movie.rootFrames.addAll(movie.timeline(root));
        // The renderer follows ShowFrame records, which are authoritative.
        if(movie.rootFrames.size()>0)movie.frameCount=movie.rootFrames.size();
        movie.parseAudio(root);
        return movie;
    }
    private SwfCore(){}
}