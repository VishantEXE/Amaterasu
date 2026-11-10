package dev.questheld.host;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import com.android.ide.common.rendering.api.*;
import com.android.ide.common.resources.*;
import com.android.ide.common.resources.configuration.FolderConfiguration;
import com.android.layoutlib.bridge.Bridge;
import com.android.resources.*;
import com.android.resources.aar.FrameworkResourceRepository;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import org.kxml2.io.KXmlParser;
import org.w3c.dom.*;
import org.xmlpull.v1.XmlPullParser;

/** Render the production HostUi in Android Studio's layoutlib. No APK/device installation. */
public final class UiRender {
    static Path extracted;
    static final Map<Integer,ResourceReference> ids=new HashMap<>();
    static final Map<ResourceReference,Integer> reverse=new HashMap<>();
    static final ResourceNamespace NS=ResourceNamespace.RES_AUTO;
    static HostUi view;
    static boolean expanded, directPreview;
    static int controlRuns;
    static final ILayoutLog LOG=new ILayoutLog(){
        public void error(String tag,String text,Object data,Object cookie){System.err.println(tag+": "+text);}
        public void error(String tag,String text,Throwable t,Object data,Object cookie){System.err.println(tag+": "+text);if(t!=null)t.printStackTrace();}
        public void warning(String tag,String text,Object data,Object cookie){System.err.println(tag+": "+text);}
    };
    public static void main(String[] args) throws Exception {
        Path data=Paths.get(args[0]), res=Paths.get(args[1]), output=Paths.get(args[2]);
        Files.createDirectories(output);
        extracted=Paths.get(args[3]);
        FrameworkResourceRepository framework=FrameworkResourceRepository.create(data.resolve("framework_res.jar"),Set.of("en"),null,false);
        Map<ResourceNamespace,Map<ResourceType,ResourceValueMap>> base=ResourceRepositoryUtil.getConfiguredResources(framework,FolderConfiguration.getConfigForQualifierString("en-rUS-xhdpi-v36")).rowMap();
        Map<String,Map<String,Integer>> enums=new HashMap<>();
        for(ResourceValue value:base.get(ResourceNamespace.ANDROID).get(ResourceType.ATTR).values())
            if(value instanceof AttrResourceValue) enums.put(value.getName(),((AttrResourceValue)value).getAttributeValues());
        for(Class<?> type:R.class.getClasses()) for(Field field:type.getFields()) {
            if(field.getType()!=int.class)continue;
            ResourceReference ref=new ResourceReference(NS,ResourceType.valueOf(type.getSimpleName().toUpperCase(Locale.ROOT)),field.getName());
            ids.put(field.getInt(null),ref); reverse.put(ref,field.getInt(null));
        }
        Bridge bridge=new Bridge();
        String[] keyboards=Files.list(data.resolve("keyboards")).map(Path::toString).toArray(String[]::new);
        boolean ok=bridge.init(Map.of("ro.build.version.sdk","36","ro.build.version.codename","REL",
            "ro.product.cpu.abilist","arm64-v8a","ro.product.cpu.abilist64","arm64-v8a",
            "ro.product.cpu.abilist32","armeabi-v7a","ro.build.version.release","16",
            "ro.build.version.sdk_full","36.0","ro.build.version.all_codenames","REL"),data.resolve("fonts").toFile(),
            data.resolve("win/lib64").toString(),data.resolve("icu/icudt78l.dat").toString(),data.resolve("hyphen-data").toString(),keyboards,enums,LOG);
        if(!ok)throw new AssertionError("layoutlib initialization failed");
        for(String mode:new String[]{"landscape-light","landscape-dark","portrait-light","portrait-dark","portrait-large-text","pairing","portrait-settings","landscape-sharing","portrait-offline","portrait-hotspot","portrait-power","portrait-power-granted","portrait-audio","landscape-120"}) {
            boolean wide=mode.startsWith("landscape"), dark=mode.endsWith("dark"); expanded=mode.equals("pairing"); directPreview=mode.equals("portrait-hotspot");
            Map<ResourceNamespace,Map<ResourceType,ResourceValueMap>> resources=new HashMap<>(base);
            Map<ResourceType,ResourceValueMap> app=new EnumMap<>(ResourceType.class);
            loadValues(res.resolve("values"),app);
            if(dark)loadValues(res.resolve("values-night"),app);
            for(String type:new String[]{"drawable","drawable-nodpi","mipmap-anydpi","mipmap-anydpi-v26"}) try(var files=Files.list(res.resolve(type))) {
                files.filter(p->p.toString().endsWith(".xml") || p.toString().endsWith(".png")).forEach(p->put(app,new ResourceValueImpl(NS,type.startsWith("mipmap")?ResourceType.MIPMAP:ResourceType.DRAWABLE,p.getFileName().toString().replace(".xml","").replace(".png",""),p.toAbsolutePath().toString())));
            }
            resources.put(NS,app);
            ResourceResolver resolver=ResourceResolver.create(resources,ResourceReference.style(NS,"Theme.Amaterasu"));
            HardwareConfig hw=new HardwareConfig(wide?1920:1080,wide?1080:2400,wide?Density.XHIGH:Density.XXHIGH,wide?320:480,wide?320:480,ScreenSize.NORMAL,wide?ScreenOrientation.LANDSCAPE:ScreenOrientation.PORTRAIT,ScreenRound.NOTROUND,false);
            Parser parser=new Parser(new StringReader("<view class=\"dev.questheld.host.UiRender$PreviewRoot\" xmlns:android=\"http://schemas.android.com/apk/res/android\" android:layout_width=\"match_parent\" android:layout_height=\"match_parent\"/>"));
            SessionParams params=new SessionParams(parser,SessionParams.RenderingMode.NORMAL,"Amaterasu",hw,resolver,new Callback(),29,34,LOG);
            params.setForceNoDecor(); params.setUiMode(dark?Configuration.UI_MODE_NIGHT_YES:Configuration.UI_MODE_NIGHT_NO);
            params.setFontScale(mode.equals("portrait-large-text")?1.3f:1f);
            params.setAssetRepository(new AssetRepository(){
                public boolean isSupported(){return true;}
                public InputStream openNonAsset(int cookie,String name,int access)throws IOException{return open(name);}
                public boolean isFileResource(String name){return true;}
            });
            RenderSession session=bridge.createSession(params);
            if(!session.getResult().isSuccess()){if(session.getResult().getException()!=null)session.getResult().getException().printStackTrace();throw new AssertionError(session.getResult());}
            RecyclableImage initial=session.getRecyclableImage(); if(initial!=null)initial.close();
            Result rendered=session.render(30000,true);
            if(!rendered.isSuccess())throw new AssertionError(rendered);
            RecyclableImage measured=session.getRecyclableImage(); if(measured!=null)measured.close();
            session.execute(()->{
                controlRuns++;
                checkControls();
                if(mode.equals("portrait-power-granted"))view.setDimmingPermission(true);
                if(mode.equals("landscape-sharing")) { view.setState(HostUi.State.SHARING,"192.168.1.42",null,view.settings()); view.mediaStatus(true); }
                if(mode.equals("landscape-120")) { android.widget.Switch fps=(android.widget.Switch)field("highRefresh"); fps.setChecked(true); fps.jumpDrawablesToCurrentState(); }
                if(mode.equals("portrait-hotspot")) {
                    view.connectionDetail("Android hotspot detected. Find the Wi-Fi name and password in Android settings.","SERVER READY");
                    view.connectionSetup("192.0.2.42","Paired device saved");
                }
                if(mode.equals("portrait-offline"))view.setState(HostUi.State.NO_NETWORK,null,null,null);
                view.measure(android.view.View.MeasureSpec.makeMeasureSpec(hw.getScreenWidth(),android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(hw.getScreenHeight(),android.view.View.MeasureSpec.EXACTLY));
                view.layout(0,0,hw.getScreenWidth(),hw.getScreenHeight());
                if(mode.equals("portrait-settings") || mode.equals("pairing") || mode.equals("portrait-hotspot") || mode.startsWith("portrait-power") || mode.equals("portrait-audio")) {
                    android.widget.ScrollView scroll=(android.widget.ScrollView)view.getChildAt(1);
                    if(mode.equals("portrait-settings"))scroll.scrollTo(0,scroll.getChildAt(0).getHeight());
                    else if(mode.startsWith("portrait-power") || mode.equals("portrait-audio")) {
                        android.view.ViewGroup content=(android.view.ViewGroup)scroll.getChildAt(0);
                        android.view.ViewGroup right=(android.view.ViewGroup)content.getChildAt(1);
                        scroll.scrollTo(0,right.getTop()+right.getChildAt(mode.startsWith("portrait-power")?3:2).getTop());
                    } else {
                        android.view.ViewGroup content=(android.view.ViewGroup)scroll.getChildAt(0);
                        android.view.ViewGroup left=(android.view.ViewGroup)content.getChildAt(0);
                        scroll.scrollTo(0,left.getChildAt(mode.equals("pairing")?2:1).getTop());
                    }
                }
                android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(hw.getScreenWidth(),hw.getScreenHeight(),android.graphics.Bitmap.Config.ARGB_8888);
                view.draw(new android.graphics.Canvas(bitmap));
                try(OutputStream file=Files.newOutputStream(output.resolve(mode+".png"))){
                    if(!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,file))throw new AssertionError("PNG encoding failed");
                }catch(IOException e){throw new RuntimeException(e);}finally{bitmap.recycle();}
                if(mode.equals("portrait-light")) {
                    // Layoutlib lacks the private config_icon_mask string. Preview a
                    // standard circular mask explicitly; APK resources are checked by aapt2.
                    android.graphics.drawable.Drawable launcher=view.getContext().getDrawable(R.drawable.amaterasu_art);
                    android.graphics.Bitmap icon=android.graphics.Bitmap.createBitmap(256,256,android.graphics.Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas iconCanvas=new android.graphics.Canvas(icon);
                    android.graphics.Path mask=new android.graphics.Path(); mask.addCircle(128,128,128,android.graphics.Path.Direction.CW);
                    iconCanvas.clipPath(mask); iconCanvas.drawColor(android.graphics.Color.WHITE);
                    launcher.setBounds(28,28,228,228); launcher.draw(iconCanvas);
                    try(OutputStream file=Files.newOutputStream(output.resolve("launcher-circle-preview.png"))) {
                        if(!icon.compress(android.graphics.Bitmap.CompressFormat.PNG,100,file))throw new AssertionError("Icon PNG failed");
                    } catch(IOException e) { throw new RuntimeException(e); } finally { icon.recycle(); }
                }
            });
            require(controlRuns>0,"Renderer did not execute control checks");
            System.out.println(mode+" checks="+controlRuns+" primary="+((android.widget.Button)field("primary")).getText());
            System.out.println("Rendered "+mode+" "+hw.getScreenWidth()+"x"+hw.getScreenHeight());
            session.dispose();
        }
        bridge.dispose();
    }
    static Object field(String name) {
        try{Field f=HostUi.class.getDeclaredField(name);f.setAccessible(true);return f.get(view);}catch(Exception e){throw new RuntimeException(e);}
    }
    static void require(boolean value,String error){if(!value)throw new AssertionError(error);}
    static void checkControls() {
        require(view.muteHost(),"Host mute must default on"); require(!view.dimHost(),"Dimming must default off");
        android.widget.Switch dim=(android.widget.Switch)field("dimHost");
        require(!dim.isEnabled(),"Dimming enabled without permission");
        view.setDimmingPermission(true); require(dim.isEnabled(),"Permission grant did not enable dimming control");
        dim.setChecked(true); require(view.dimHost(),"Permitted dim selection failed");
        view.setDimmingPermission(false); require(!view.dimHost() && !dim.isEnabled(),"Permission revocation left dimming active");
        view.setDimmingPermission(true); require(view.dimHost(),"Permission refresh lost the saved dim preference");
        dim.setChecked(false);
        android.widget.Switch fsr=(android.widget.Switch)field("fsr"), fps=(android.widget.Switch)field("highRefresh");
        fsr.setChecked(false); require(!view.settings().fsr && view.settings().outputWidth()==1920,"FSR off mode changed"); fsr.setChecked(true);
        fps.setChecked(true); require(view.settings().fps==120,"120 FPS toggle failed"); fps.setChecked(false);
        android.widget.Button avc=(android.widget.Button)field("h264"), hevc=(android.widget.Button)field("hevc");
        avc.setSoundEffectsEnabled(false);hevc.setSoundEffectsEnabled(false);
        avc.performClick();require(view.settings().codec==StreamSettings.H264,"H.264 selection failed"); hevc.performClick();require(view.settings().codec==StreamSettings.HEVC,"HEVC selection failed");
        android.widget.EditText pin=(android.widget.EditText)field("pin"); android.widget.Button pair=(android.widget.Button)field("pairButton");
        pin.setText("12");require(!pair.isEnabled(),"Incomplete PIN accepted"); pin.setText("1234");require(pair.isEnabled(),"Four-digit PIN rejected");
        view.pairFeedback("PIN submitted. Finish connecting in Moonlight.",true); require(pin.getText().length()==0,"PIN not cleared");
        ((android.widget.TextView)field("pairFeedback")).setVisibility(android.view.View.GONE);
        view.setState(HostUi.State.SHARING,"192.168.1.42",null,view.settings());
        require(!fsr.isEnabled() && !fps.isEnabled() && !avc.isEnabled(),"Active settings not locked");
        require(!((android.widget.Switch)field("muteHost")).isEnabled() && !((android.widget.Button)field("connectionMode")).isEnabled(),"Host/network controls changed during playback");
        require(!dim.isEnabled() && !((android.widget.Button)field("brightnessPermission")).isEnabled(),"Brightness controls unlocked during sharing");
        require(((android.widget.Button)field("primary")).getText().toString().equals("Stop sharing"),"Stop action missing");
        view.setState(HostUi.State.READY,"192.168.1.42",null,null);
        require(fsr.isEnabled() && avc.isEnabled(),"Ready settings not unlocked");
        require(view.settings().outputWidth()==2560 && view.settings().fps==60,"Settings changed during state transitions");
        view.setDimmingPermission(false);
    }
    static InputStream open(String name)throws IOException {
        int zip=name.indexOf("!/");
        if(zip>=0)return Files.newInputStream(extracted.resolve(name.substring(zip+2)));
        return Files.newInputStream(Paths.get(name));
    }
    static void put(Map<ResourceType,ResourceValueMap> map,ResourceValue value){map.computeIfAbsent(value.getResourceType(),t->ResourceValueMap.create()).put(value);}
    static void loadValues(Path dir,Map<ResourceType,ResourceValueMap> map)throws Exception {
        try(var files=Files.list(dir)) { for(Path file:files.toList()) {
            Element root=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile()).getDocumentElement();
            NodeList children=root.getChildNodes();
            for(int i=0;i<children.getLength();i++)if(children.item(i) instanceof Element) {
                Element element=(Element)children.item(i); String name=element.getAttribute("name");
                if(element.getTagName().equals("style")) {
                    StyleResourceValueImpl style=new StyleResourceValueImpl(NS,name,element.getAttribute("parent"),null);
                    style.setNamespaceResolver(prefix->prefix.equals("android")?"http://schemas.android.com/apk/res/android":null);
                    NodeList items=element.getElementsByTagName("item");
                    for(int j=0;j<items.getLength();j++) {
                        Element item=(Element)items.item(j); StyleItemResourceValueImpl value=new StyleItemResourceValueImpl(NS,item.getAttribute("name"),item.getTextContent(),null);
                        value.setNamespaceResolver(style.getNamespaceResolver()); style.addItem(value);
                    }
                    put(map,style);
                } else put(map,new ResourceValueImpl(NS,ResourceType.valueOf(element.getTagName().toUpperCase(Locale.ROOT)),name,element.getTextContent()));
            }
        }}
    }
    static final class Parser extends KXmlParser implements ILayoutPullParser {
        Parser(Reader reader)throws Exception{setFeature(FEATURE_PROCESS_NAMESPACES,true);setInput(reader);}
        public Object getViewCookie(){return null;}
        public ResourceNamespace getLayoutNamespace(){return NS;}
    }
    public static final class PreviewRoot extends android.widget.FrameLayout {
        public PreviewRoot(Context context,android.util.AttributeSet attrs) {
            super(context,attrs);
            view=new HostUi(context,preferences(),new HostUi.Actions(){public void start(){} public void stop(){} public void retry(){} public void pair(String pin){} public void copyAddress(){}});
            view.setState(HostUi.State.READY,"192.168.1.42",null,null);
            if(expanded)try {
                Field f=HostUi.class.getDeclaredField("pairChevron"); f.setAccessible(true);
                android.view.View heading=(android.view.View)((android.view.View)f.get(view)).getParent();
                heading.setSoundEffectsEnabled(false); heading.performClick();
            } catch(Exception e){throw new RuntimeException(e);}
            addView(view,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));
        }
    }
    static final class Callback extends LayoutlibCallback {
        public Object loadView(String name,Class[] types,Object[] args)throws Exception {
            if(name.equals("dev.questheld.host.HostUi")) {
                view=new HostUi((Context)args[0],preferences(),new HostUi.Actions(){public void start(){} public void stop(){} public void retry(){} public void pair(String pin){} public void copyAddress(){}});
                view.setState(HostUi.State.READY,"192.168.1.42",null,null);
                if(expanded){Field f=HostUi.class.getDeclaredField("pairingBody");f.setAccessible(true);((android.view.View)f.get(view)).setVisibility(android.view.View.VISIBLE);}
                return view;
            }
            return Class.forName(name).getConstructor(types).newInstance(args);
        }
        public ResourceReference resolveResourceId(int id){return ids.get(id);}
        public int getOrGenerateResourceId(ResourceReference ref){if(!reverse.containsKey(ref)){int id=0x7f700000+reverse.size();reverse.put(ref,id);ids.put(id,ref);}return reverse.get(ref);}
        public ILayoutPullParser getParser(ResourceValue value){try{return new Parser(new InputStreamReader(open(value.getValue())));}catch(Exception e){throw new RuntimeException(e);}}
        public AdapterBinding getAdapterBinding(Object view,Map<String,String> attrs){return null;}
        public ActionBarCallback getActionBarCallback(){return null;}
        public XmlPullParser createXmlParserForPsiFile(String name){return createXmlParserForFile(name);}
        public XmlPullParser createXmlParserForFile(String name){try{return new Parser(new InputStreamReader(open(name)));}catch(Exception e){throw new RuntimeException(e);}}
        public XmlPullParser createXmlParser(){return new KXmlParser();}
        public String getApplicationId(){return "dev.questheld.host.pacing";}
    }
    static SharedPreferences preferences(){
        Map<String,Object> values=new HashMap<>(Map.of("fsr",true,"fsrScale",2,"codec",1,"bitrateMbps",40,"directHotspot",directPreview));
        return (SharedPreferences)Proxy.newProxyInstance(UiRender.class.getClassLoader(),new Class[]{SharedPreferences.class},(proxy,m,args)->{
            if(m.getName().startsWith("get") && args!=null && args.length==2)return values.getOrDefault(args[0],args[1]);
            if(m.getName().equals("edit"))return Proxy.newProxyInstance(UiRender.class.getClassLoader(),new Class[]{SharedPreferences.Editor.class},(p,method,a)->{
                if(method.getName().startsWith("put")){values.put((String)a[0],a[1]);return p;}
                if(method.getName().equals("commit"))return true;
                return method.getReturnType()==SharedPreferences.Editor.class?p:null;
            });
            if(m.getName().equals("contains"))return values.containsKey(args[0]);
            if(m.getName().equals("getAll"))return values;
            return null;
        });
    }
}
