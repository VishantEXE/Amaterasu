package dev.questheld.host;

import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.opengl.GLES31;
import android.os.Looper;
import android.view.Surface;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/** Standalone shell GPU check; does not install or modify the host application. */
public final class FsrMemoryProbe {
    public static void main(String[] args) {
        Looper.prepareMainLooper();
        new Thread(() -> {
            try {
                AssetManager assets=AssetManager.class.getDeclaredConstructor().newInstance();
                AssetManager.class.getMethod("addAssetPath",String.class).invoke(assets,args[0]);
                for(int[] size:new int[][]{{1920,1080},{2560,1440},{3840,2160}}) {
                    if(args.length>1 && size[0]!=3840) continue;
                    int inputW=size[0]==1920?1280:1920, inputH=size[0]==1920?720:1080;
                    ByteBuffer reference=render(assets,inputW,inputH,size[0],size[1],size[1]);
                    for(int rows:new int[]{128,256,512}) {
                    ByteBuffer strips=render(assets,inputW,inputH,size[0],size[1],rows);
                    int different=0,maxDelta=0;
                    for(int i=0;i<reference.capacity();i++) {
                        int delta=Math.abs((reference.get(i)&255)-(strips.get(i)&255));
                        if(delta!=0) different++;
                        maxDelta=Math.max(maxDelta,delta);
                    }
                    System.out.println("IMAGE "+size[0]+"x"+size[1]+" stripRows="+rows+" changedChannels="+different+" maxDelta="+maxDelta);
                    if(maxDelta>1) throw new AssertionError("Strip boundary or image mismatch");
                    }
                }
                System.out.println("FSR strip image and GPU checks passed");
                System.exit(0);
            } catch(Throwable e) { e.printStackTrace(); System.exit(1); }
        },"fsr-memory-probe").start();
        Looper.loop();
    }

    private static ByteBuffer render(AssetManager assets,int iw,int ih,int w,int h,int rows) throws Exception {
        MediaCodec codec=MediaCodec.createByCodecName("c2.qti.avc.encoder");
        Surface output=null;
        try {
            MediaFormat format=MediaFormat.createVideoFormat("video/avc",w,h);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE,20000000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE,60);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
            output=codec.createInputSurface(); codec.start();
            try(SessionDiagnostics diagnostics=new SessionDiagnostics(new File("/data/local/tmp/questheld-fsr-memory"),"FSR GPU image check");
                FrameUpscaler bridge=new FrameUpscaler(assets,output,iw,ih,w,h,true,60,diagnostics,rows)) {
                Canvas canvas=bridge.captureSurface().lockCanvas(null);
                try {
                    canvas.drawColor(Color.BLACK);
                    Paint paint=new Paint();
                    for(int y=0;y<ih;y+=17) for(int x=0;x<iw;x+=23) {
                        paint.setColor(Color.rgb((x*7+y*3)&255,(x*3+y*5)&255,(x*11+y*7)&255));
                        canvas.drawRect(x,y,x+23,y+17,paint);
                    }
                    paint.setColor(Color.WHITE); paint.setStrokeWidth(1);
                    canvas.drawLine(0,0,iw,ih,paint); canvas.drawLine(iw,0,0,ih,paint);
                } finally { bridge.captureSurface().unlockCanvasAndPost(canvas); }
                Field cameraField=FrameUpscaler.class.getDeclaredField("camera"); cameraField.setAccessible(true);
                ((SurfaceTexture)cameraField.get(bridge)).updateTexImage();
                Method draw=FrameUpscaler.class.getDeclaredMethod("renderImage"); draw.setAccessible(true);
                double totalMs=0;
                for(int frame=0;frame<10;frame++) {
                    long start=System.nanoTime(); draw.invoke(bridge); GLES31.glFinish();
                    if(frame>=3) totalMs+=(System.nanoTime()-start)/1e6;
                }
                ByteBuffer pixels=ByteBuffer.allocateDirect(w*h*4);
                GLES31.glReadPixels(0,0,w,h,GLES31.GL_RGBA,GLES31.GL_UNSIGNED_BYTE,pixels);
                int error=GLES31.glGetError();
                if(error!=GLES31.GL_NO_ERROR) throw new AssertionError("GL error "+error);
                System.out.println("GPU "+w+"x"+h+" stripRows="+rows+" meanDrawAndFinishMs="+(totalMs/7)
                        +" intermediateBytes="+(w*(Math.min(h,rows)+2)*4));
                return pixels;
            }
        } finally { if(output!=null) output.release(); codec.release(); }
    }
}
