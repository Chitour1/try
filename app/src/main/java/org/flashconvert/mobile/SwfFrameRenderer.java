package org.flashconvert.mobile;
import android.graphics.*;
import java.util.*;
final class SwfFrameRenderer {
 final SwfCore swf;
 final HashMap<Integer,Bitmap> cache=new HashMap<>();
 final HashMap<String,Display> displayCache=new HashMap<>();
 final Display root=new Display();
 final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
 int last=-1;
 static final class ObjectAt {
  int id,born;
  long instanceId;
  final Display nested=new Display();
  float[] matrix={1,0,0,1,0,0},cx={1,1,1,1,0,0,0,0};
 }
 static final class Display {
  final TreeMap<Integer,ObjectAt> objects=new TreeMap<>();
  int last=-1;
 }
 SwfFrameRenderer(SwfCore core){swf=core;}
 static void update(Display d,ArrayList<SwfCore.Event> events,int frame){
  for(SwfCore.Event e:events){
   if(e.remove){d.objects.remove(e.depth);continue;}
   ObjectAt obj=d.objects.get(e.depth);
   if(e.newId){obj=new ObjectAt();obj.id=e.id;obj.born=frame;d.objects.put(e.depth,obj);}
   if(obj==null)continue;
   if(e.hasMatrix)obj.matrix=e.mat;
   if(e.hasCx)obj.cx=e.cx;
  }
 }
 static void at(Display d,ArrayList<ArrayList<SwfCore.Event>> frames,int target){
  if(frames.isEmpty())return;
  target=Math.max(0,Math.min(frames.size()-1,target));
  if(target<d.last){d.objects.clear();d.last=-1;}
  for(int k=d.last+1;k<=target;k++)update(d,frames.get(k),k);
  d.last=target;
 }
 static Matrix matrix(float[] m){
  Matrix x=new Matrix();
  x.setValues(new float[]{m[0],m[2],m[4]/20f,m[1],m[3],m[5]/20f,0,0,1});
  return x;
 }
 static Path edges(List<SwfCore.Edge> list){
  Path path=new Path();
  HashMap<Long,ArrayList<Integer>> next=new HashMap<>();
  for(int i=0;i<list.size();i++){SwfCore.Edge e=list.get(i);next.computeIfAbsent(key(e.x,e.y),k->new ArrayList<>()).add(i);}
  boolean[] done=new boolean[list.size()];
  for(int i=0;i<list.size();i++){
   if(done[i])continue;
   SwfCore.Edge first=list.get(i);path.moveTo(first.x/20f,first.y/20f);
   int index=i,sx=first.x,sy=first.y;int max=0;
   while(index>=0&&!done[index]&&max++<=list.size()){
    done[index]=true;SwfCore.Edge e=list.get(index);
    if(e.curve)path.quadTo(e.mx/20f,e.my/20f,e.ex/20f,e.ey/20f);
    else path.lineTo(e.ex/20f,e.ey/20f);
    if(e.ex==sx&&e.ey==sy){path.close();break;}
    index=-1;
    for(int possible:next.getOrDefault(key(e.ex,e.ey),new ArrayList<>())){
     if(!done[possible]){index=possible;break;}
    }
   }
  }
  path.setFillType(Path.FillType.WINDING);
  return path;
 }
 static long key(int x,int y){return ((long)x<<32)|(y&0xffffffffL);}
 static Matrix fillMatrix(float[] m){
   Matrix result=new Matrix();
   result.setValues(new float[]{m[0]/20f,m[2]/20f,m[4]/20f,m[1]/20f,m[3]/20f,m[5]/20f,0,0,1});
   return result;
 }
 void applyStyle(Paint p,SwfCore.Style s){
  p.reset();p.setAntiAlias(true);p.setFilterBitmap(true);p.setStyle(Paint.Style.FILL);
  if(s.kind==0){p.setColor(s.color);return;}
  if(s.kind==1){
   if(s.positions==null||s.positions.length<2){p.setColor(Color.BLACK);return;}
   Shader shader;
   Shader.TileMode mode=s.spread==1?Shader.TileMode.MIRROR:s.spread==2?Shader.TileMode.REPEAT:Shader.TileMode.CLAMP;
   if(s.type==0x10)shader=new LinearGradient(-16384f,0,16384f,0,s.stops,s.positions,mode);
   else shader=new RadialGradient(0,0,16384f,s.stops,s.positions,mode);
   if(s.matrix!=null){shader.setLocalMatrix(fillMatrix(s.matrix));}
   p.setShader(shader);return;
  }
  Bitmap bmp=swf.images.get(s.imageId);
  if(bmp==null){p.setColor(Color.TRANSPARENT);return;}
  BitmapShader shader=new BitmapShader(bmp,(s.type==0x40||s.type==0x42)?Shader.TileMode.REPEAT:Shader.TileMode.CLAMP,(s.type==0x40||s.type==0x42)?Shader.TileMode.REPEAT:Shader.TileMode.CLAMP);
  if(s.matrix!=null)shader.setLocalMatrix(fillMatrix(s.matrix));
  p.setShader(shader);
 }
 void drawShape(Canvas c,SwfCore.Shape s,int alpha){
  for(Map.Entry<Integer,ArrayList<SwfCore.Edge>> e:s.byFill.entrySet()){
   if(e.getKey()<1||e.getKey()>s.fills.size())continue;
   applyStyle(paint,s.fills.get(e.getKey()-1));paint.setAlpha((paint.getAlpha()*alpha)/255);
   c.drawPath(edges(e.getValue()),paint);
  }
  for(Map.Entry<Integer,ArrayList<SwfCore.Edge>> e:s.byLine.entrySet()){
   if(e.getKey()<1||e.getKey()>s.lines.size())continue;
   SwfCore.LineStyle line=s.lines.get(e.getKey()-1);
   paint.reset();paint.setAntiAlias(true);paint.setColor(line.color);
   paint.setAlpha((paint.getAlpha()*alpha)/255);
   paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(line.thickness/20f);
   paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
   c.drawPath(edges(e.getValue()),paint);
  }
 }
 void character(Canvas c,int id,int frame,int alpha,int depth,Display nested){
  if(depth>30)return;
  SwfCore.Shape shape=swf.shapes.get(id);
  if(shape!=null){drawShape(c,shape,alpha);return;}
  Bitmap bmp=swf.images.get(id);
  if(bmp!=null){paint.reset();paint.setFilterBitmap(true);paint.setAlpha(alpha);c.drawBitmap(bmp,0,0,paint);return;}
  SwfCore.TextShape text=swf.texts.get(id);
  if(text!=null){
   c.save();c.concat(matrix(text.mat));
   for(SwfCore.GlyphChar ch:text.glyphs){
    SwfCore.Font font=swf.fonts.get(ch.fontId);
    if(font==null||ch.glyph<0||ch.glyph>=font.glyphs.length)continue;
    SwfCore.Shape glyph=font.glyphs[ch.glyph];if(glyph==null)continue;
    c.save();c.translate(ch.x/20f,ch.y/20f);
    c.scale(ch.height/1024f,ch.height/1024f);
    paint.reset();paint.setAntiAlias(true);paint.setColor(ch.color);paint.setAlpha((paint.getAlpha()*alpha)/255);
    ArrayList<SwfCore.Edge> all=new ArrayList<>();
    for(ArrayList<SwfCore.Edge> set:glyph.byFill.values())all.addAll(set);
    c.drawPath(edges(all),paint);c.restore();
   }
   c.restore();return;
  }
  SwfCore.Sprite sprite=swf.sprites.get(id);
  if(sprite!=null){
   at(nested,sprite.frames,frame);
   drawDisplay(c,nested,frame,alpha,depth+1);
  }
 }
 void drawDisplay(Canvas c,Display d,int frame,int parentAlpha,int depth){
  for(ObjectAt o:d.objects.values()){
   c.save();c.concat(matrix(o.matrix));
   int alpha=Math.max(0,Math.min(255,Math.round((o.cx[3]+o.cx[7]/255f)*parentAlpha)));
   int local=swf.sprites.containsKey(o.id)?Math.max(0,frame-o.born):0;
   character(c,o.id,local,alpha,depth,o.nested);
   c.restore();
  }
 }
 Bitmap render(int index){
  at(root,swf.rootFrames,index);
  Bitmap image=Bitmap.createBitmap(swf.width,swf.height,Bitmap.Config.ARGB_8888);
  Canvas canvas=new Canvas(image);
  canvas.drawColor(swf.background);
  canvas.translate(-swf.stageX/20f,-swf.stageY/20f);
  drawDisplay(canvas,root,index,255,0);
  return image;
 }
}