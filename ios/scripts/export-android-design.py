from pathlib import Path
import tempfile, subprocess, shutil, re, json, hashlib, xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'ios/DesignAssets'
OUT.mkdir(parents=True, exist_ok=True)
stubs = {
'android/content/Context.java': 'package android.content; public class Context {}',
'android/annotation/SuppressLint.java': 'package android.annotation; public @interface SuppressLint { String[] value(); }',
'android/view/View.java': '''package android.view;
public class View {
 public static final int IMPORTANT_FOR_ACCESSIBILITY_NO=2; public int width=24,height=24;
 public View(android.content.Context c) {} public void setImportantForAccessibility(int v) {} public void invalidate() {}
 public Resources getResources(){return new Resources();} public int getWidth(){return width;} public int getHeight(){return height;}
 protected void onDraw(android.graphics.Canvas c) {} public static class Resources { public Metrics getDisplayMetrics(){return new Metrics();} }
 public static class Metrics { public float density=1; }
}''',
'android/graphics/CornerPathEffect.java': 'package android.graphics; public class CornerPathEffect { public CornerPathEffect(float x) {} }',
'android/graphics/Shader.java': 'package android.graphics; public class Shader { public enum TileMode { CLAMP } }',
'android/graphics/LinearGradient.java': '''package android.graphics; public class LinearGradient extends Shader {
 public java.awt.Paint value; public LinearGradient(float x,float y,float a,float b,int c,int d,TileMode m) {
 value=new java.awt.GradientPaint(x,y,new java.awt.Color(c,true),a,b,new java.awt.Color(d,true)); }
}''',
'android/graphics/RectF.java': 'package android.graphics; public record RectF(float left,float top,float right,float bottom) {}',
'android/graphics/Paint.java': '''package android.graphics; public class Paint {
 public static final int ANTI_ALIAS_FLAG=1; public enum Style { STROKE,FILL } public enum Cap { ROUND } public enum Join { ROUND } public enum Align { CENTER }
 int color=0xff000000; float width=1,size=12; boolean bold=false; Style style=Style.FILL; Shader shader;
 public Paint(int flags) {} public void setColor(int c){color=c;} public int getColor(){return color;}
 public void setStrokeWidth(float w){width=w;} public void setStyle(Style s){style=s;} public void setStrokeCap(Cap x){} public void setStrokeJoin(Join x){}
 public void setPathEffect(CornerPathEffect p){} public void setTextAlign(Align x){} public void setTextSize(float s){size=s;}
 public void setFakeBoldText(boolean b){bold=b;} public void setShader(Shader s){shader=s;}
}''',
'android/graphics/Path.java': '''package android.graphics; public class Path {
 public java.awt.geom.Path2D.Float value=new java.awt.geom.Path2D.Float(); public void reset(){value.reset();}
 public void moveTo(float x,float y){value.moveTo(x,y);} public void lineTo(float x,float y){value.lineTo(x,y);}
 public void cubicTo(float a,float b,float c,float d,float e,float f){value.curveTo(a,b,c,d,e,f);}
 public void quadTo(float a,float b,float c,float d){value.quadTo(a,b,c,d);} public void close(){value.closePath();}
}''',
'android/graphics/Canvas.java': '''package android.graphics;
import java.awt.*; import java.awt.geom.*; import java.util.ArrayDeque;
public class Canvas {
 final Graphics2D g; final ArrayDeque<AffineTransform> stack=new ArrayDeque<>();
 public Canvas(Graphics2D g){this.g=g;g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);}
 public void save(){stack.push(g.getTransform());} public void restore(){g.setTransform(stack.pop());}
 public void translate(float x,float y){g.translate(x,y);} public void scale(float x,float y){g.scale(x,y);}
 public void rotate(float angle,float x,float y){g.rotate(Math.toRadians(angle),x,y);}
 void render(Shape s,Paint p){g.setPaint(p.shader instanceof LinearGradient l ? l.value : new Color(p.color,true));
 g.setStroke(new BasicStroke(p.width,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND)); if(p.style==Paint.Style.FILL)g.fill(s);else g.draw(s);}
 public void drawCircle(float x,float y,float r,Paint p){drawOval(x-r,y-r,x+r,y+r,p);}
 public void drawOval(float l,float t,float r,float b,Paint p){render(new Ellipse2D.Float(l,t,r-l,b-t),p);}
 public void drawRect(float l,float t,float r,float b,Paint p){render(new Rectangle2D.Float(l,t,r-l,b-t),p);}
 public void drawRoundRect(float l,float t,float r,float b,float rx,float ry,Paint p){render(new RoundRectangle2D.Float(l,t,r-l,b-t,rx*2,ry*2),p);}
 public void drawLine(float a,float b,float c,float d,Paint p){render(new Line2D.Float(a,b,c,d),p);}
 public void drawPoint(float x,float y,Paint p){StylePoint(x,y,p);}
 void StylePoint(float x,float y,Paint p){g.setColor(new Color(p.color,true));g.fill(new Ellipse2D.Float(x-p.width/2,y-p.width/2,p.width,p.width));}
 public void drawPath(Path path,Paint p){render(path.value,p);}
 public void drawArc(float l,float t,float r,float b,float start,float sweep,boolean center,Paint p){render(new Arc2D.Float(l,t,r-l,b-t,-start,-sweep,center?Arc2D.PIE:Arc2D.OPEN),p);}
 public void drawArc(RectF r,float a,float b,boolean c,Paint p){drawArc(r.left(),r.top(),r.right(),r.bottom(),a,b,c,p);}
 public void drawText(String s,float x,float y,Paint p){g.setColor(new Color(p.color,true));g.setFont(new Font("SansSerif",p.bold?Font.BOLD:Font.PLAIN,Math.round(p.size)));g.drawString(s,x-g.getFontMetrics().stringWidth(s)/2f,y);}
}''',
'app/soundshelf/ExportDesign.java': '''package app.soundshelf;
import java.awt.image.BufferedImage; import javax.imageio.ImageIO; import java.io.File;
public class ExportDesign {
 public static void main(String[] args)throws Exception {
 var context=new android.content.Context();
 for(var kind:UiIcon.Kind.values()) {
 var image=new BufferedImage(72,72,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();g.scale(3,3);
 new UiIcon(context,kind,24,0xffffffff).onDraw(new android.graphics.Canvas(g));g.dispose();
 ImageIO.write(image,"png",new File(args[0],"android-icon-"+kind.name().toLowerCase()+".png")); }
 for(var template:new HomeTemplate[]{HomeTemplate.GALLERY,HomeTemplate.RADIO})for(boolean books:new boolean[]{false,true})for(boolean dark:new boolean[]{false,true}) {
 var image=new BufferedImage(540,624,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();g.scale(3,3);
 var view=new HomeEntryArtwork(context,template,books,dark);view.width=180;view.height=208;view.onDraw(new android.graphics.Canvas(g));g.dispose();
 ImageIO.write(image,"png",new File(args[0],"android-home-"+template.name().toLowerCase()+"-"+(books?"books":"music")+"-"+(dark?"dark":"light")+".png")); }
 }
}'''
}
with tempfile.TemporaryDirectory(prefix='yinlang-design-') as temp:
    temp=Path(temp)
    for name, content in stubs.items():
        target=temp/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(content,encoding='utf-8')
    for name in ['UiIcon.java','HomeTemplate.java','HomeEntryArtwork.java']:
        shutil.copyfile(ROOT/'app/src/main/java/app/soundshelf'/name,temp/'app/soundshelf'/name)
    subprocess.run(['javac','-encoding','UTF-8','-d',str(temp/'classes')]+[str(p) for p in temp.rglob('*.java')],check=True)
    subprocess.run(['java','-Djava.awt.headless=true','-cp',str(temp/'classes'),'app.soundshelf.ExportDesign',str(OUT)],check=True)
ns='{http://schemas.android.com/apk/res/android}'
for name in ['hero_books','hero_music','library_discovery_record','discovery_grooves','ic_settings_cupertino']:
    tree=ET.parse(ROOT/f'app/src/main/res/drawable/{name}.xml').getroot()
    svg=ET.Element('svg',xmlns='http://www.w3.org/2000/svg',viewBox=f'0 0 {tree.attrib[ns+"viewportWidth"]} {tree.attrib[ns+"viewportHeight"]}')
    for path in tree.findall('path'):
        attrs={'d':path.attrib[ns+'pathData'],'fill':'none'}
        for source,dest in [('fillColor','fill'),('strokeColor','stroke'),('strokeWidth','stroke-width'),('strokeLineCap','stroke-linecap'),('strokeLineJoin','stroke-linejoin')]:
            value=path.attrib.get(ns+source)
            if value is None:continue
            if source.endswith('Color') and len(value)==9:
                attrs[dest+'-opacity']=str(int(value[1:3],16)/255);value='#'+value[3:]
            attrs[dest]=value
        ET.SubElement(svg,'path',attrs)
    ET.ElementTree(svg).write(OUT/f'android-{name}.svg',encoding='utf-8',xml_declaration=True)
files=['app/src/main/java/app/soundshelf/'+n for n in ['UiIcon.java','HomeTemplate.java','HomeEntryArtwork.java']]+[f'app/src/main/res/drawable/{n}.xml' for n in ['hero_books','hero_music','library_discovery_record','discovery_grooves','ic_settings_cupertino']]
(OUT/'sources.json').write_text(json.dumps({p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in files},indent=2)+'\n',encoding='utf-8')
print(f'Exported original Android icons, template artwork and vector resources to {OUT}')
