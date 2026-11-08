package dev.questheld.host;

import android.content.res.AssetManager;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES31;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** EGL window-surface bridge for optional host FSR 1 scaling before encoding. */
final class FrameUpscaler implements AutoCloseable {
    private static final String GLSL = "#version 310 es\n";
    private static final String PRECISION = "precision highp float;\nprecision highp int;\n";
    private static final String VERTEX = GLSL + "out vec2 uv;\n"
            + "void main() { uv=vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);"
            + "gl_Position=vec4(uv*2.0-1.0,0,1); }\n";
    private static final String COPY = GLSL + "#extension GL_OES_EGL_image_external_essl3 : require\n"
            + PRECISION + "uniform samplerExternalOES inputImage; uniform mat4 transform;"
            + "in vec2 uv; layout(location=0) out vec4 color;"
            + "void main(){ color=texture(inputImage,(transform*vec4(uv,0,1)).xy); }\n";

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture camera;
    private Surface capture;
    private final int[] textures = new int[3];
    private final int[] targets = new int[2];
    private final int[] programs = new int[3];
    private final float[] transform = new float[16];
    private final AtomicBoolean frameAvailable = new AtomicBoolean();
    private long lastTimestamp;
    private final int inputWidth;
    private final int inputHeight;
    private final int outputWidth;
    private final int outputHeight;
    private final boolean fsrEnabled;
    private final int stripRows;
    private final int[] stripUniforms = new int[2];
    private int transformUniform;
    private static final int[] DISCARD_COLOR = {GLES31.GL_COLOR_ATTACHMENT0};
    private final FramePacer pacer;
    private final SessionDiagnostics diagnostics;
    private long submittedFrames;

    FrameUpscaler(AssetManager assets, Surface encoderInput, int inputWidth, int inputHeight,
                  int outputWidth, int outputHeight, boolean fsrEnabled, int fps,
                  SessionDiagnostics diagnostics) throws IOException {
        this(assets, encoderInput, inputWidth, inputHeight, outputWidth, outputHeight,
                fsrEnabled, fps, diagnostics, outputWidth >= 3840 ? 512 : 128);
    }

    // The explicit strip size also permits reference-image checks against a full-height pass.
    FrameUpscaler(AssetManager assets, Surface encoderInput, int inputWidth, int inputHeight,
                  int outputWidth, int outputHeight, boolean fsrEnabled, int fps,
                  SessionDiagnostics diagnostics, int stripRows) throws IOException {
        this.inputWidth = inputWidth;
        this.inputHeight = inputHeight;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        this.fsrEnabled = fsrEnabled;
        this.stripRows = Math.max(1, Math.min(outputHeight, stripRows));
        pacer = new FramePacer(fps);
        this.diagnostics = diagnostics;
        try {
            initEgl(encoderInput);
            GLES31.glGenTextures(textures.length, textures, 0);
            GLES31.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
            setSampling(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
            programs[0] = program(COPY);
            transformUniform = GLES31.glGetUniformLocation(programs[0], "transform");
            if (fsrEnabled) {
                GLES31.glGenFramebuffers(targets.length, targets, 0);
                target(0, inputWidth, inputHeight);
                // RCAS needs only the adjacent rows. Keep a one-row halo around a reusable strip,
                // rather than retaining a full 4K EASU intermediate (31.6 MiB).
                target(1, outputWidth, this.stripRows + 2);
                String portability = source(assets, "ffx_a.h");
                // GLSL ES requires unsigned assignments where the desktop header uses signed zero.
                String fsr = source(assets, "ffx_fsr1.h")
                        .replace("con3[2]=con3[3]=0;", "con3[2]=con3[3]=AU1(0);")
                        .replace("con[2]=0;", "con[2]=AU1(0);")
                        .replace("con[3]=0;", "con[3]=AU1(0);");
                String prelude = GLSL + PRECISION + "#define A_GPU 1\n#define A_GLSL 1\n"
                        + "uniform highp sampler2D inputImage; uniform int stripOrigin;"
                        + "layout(location=0) out vec4 color;\n";
                programs[1] = program(prelude + "#define FSR_EASU_F 1\n" + portability + "\n" + fsr + "\n"
                        + "vec4 FsrEasuRF(vec2 p){return textureGather(inputImage,p,0);}\n"
                        + "vec4 FsrEasuGF(vec2 p){return textureGather(inputImage,p,1);}\n"
                        + "vec4 FsrEasuBF(vec2 p){return textureGather(inputImage,p,2);}\n"
                        + "void main(){uvec4 a,b,c,d; vec3 rgb;"
                        + "FsrEasuCon(a,b,c,d," + inputWidth + ".0," + inputHeight + ".0,"
                        + inputWidth + ".0," + inputHeight + ".0," + outputWidth + ".0," + outputHeight + ".0);"
                        + "ivec2 p=clamp(ivec2(gl_FragCoord.xy)+ivec2(0,stripOrigin),ivec2(0),ivec2("
                        + (outputWidth - 1) + "," + (outputHeight - 1) + "));"
                        + "FsrEasuF(rgb,uvec2(p),a,b,c,d);color=vec4(rgb,1);}\n");
                programs[2] = program(prelude + "#define FSR_RCAS_F 1\n" + portability + "\n" + fsr + "\n"
                        + "vec4 FsrRcasLoadF(ivec2 p){return texelFetch(inputImage,"
                        + "clamp(p,ivec2(0),ivec2(" + (outputWidth - 1) + "," + (outputHeight - 1)
                        + "))-ivec2(0,stripOrigin),0);}\n"
                        + "void FsrRcasInputF(inout float r,inout float g,inout float b){}\n"
                        + "void main(){uvec4 a;vec3 rgb;FsrRcasCon(a,0.8);"
                        + "FsrRcasF(rgb.r,rgb.g,rgb.b,uvec2(gl_FragCoord.xy),a);color=vec4(rgb,1);}\n");
                stripUniforms[0] = GLES31.glGetUniformLocation(programs[1], "stripOrigin");
                stripUniforms[1] = GLES31.glGetUniformLocation(programs[2], "stripOrigin");
            }
            camera = new SurfaceTexture(textures[0]);
            camera.setDefaultBufferSize(inputWidth, inputHeight);
            camera.setOnFrameAvailableListener(texture -> frameAvailable.set(true),
                    new Handler(Looper.getMainLooper()));
            capture = new Surface(camera);
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    Surface captureSurface() {
        return capture;
    }

    void renderPending() throws IOException {
        long now = System.nanoTime();
        if (!pacer.due(now)) return;
        if (!frameAvailable.getAndSet(false)) return;
        camera.updateTexImage();
        long timestamp = camera.getTimestamp();
        if (timestamp <= lastTimestamp) return;
        long presentationNs = System.nanoTime();
        diagnostics.frame("source_latched", ++submittedFrames, timestamp / 1000, 0, presentationNs);
        renderImage();
        if (!EGLExt.eglPresentationTimeANDROID(display, window, presentationNs)
                || !EGL14.eglSwapBuffers(display, window)) {
            throw new IOException("FSR encoder surface swap failed");
        }
        lastTimestamp = timestamp;
        diagnostics.frame("capture_submitted", submittedFrames, presentationNs / 1000, 0, timestamp);
        pacer.submitted(System.nanoTime());
    }

    private void renderImage() {
        camera.getTransformMatrix(transform);
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, fsrEnabled ? targets[0] : 0);
        if (fsrEnabled) GLES31.glInvalidateFramebuffer(GLES31.GL_FRAMEBUFFER, 1, DISCARD_COLOR, 0);
        GLES31.glViewport(0, 0, inputWidth, inputHeight);
        GLES31.glUseProgram(programs[0]);
        GLES31.glUniformMatrix4fv(transformUniform,
                1, false, transform, 0);
        GLES31.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
        GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3);

        if (fsrEnabled) {
            for (int y = 0; y < outputHeight; y += stripRows) {
                int rows = Math.min(stripRows, outputHeight - y);
                GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, targets[1]);
                GLES31.glInvalidateFramebuffer(GLES31.GL_FRAMEBUFFER, 1, DISCARD_COLOR, 0);
                GLES31.glViewport(0, 0, outputWidth, rows + 2);
                draw2d(programs[1], targets[1], textures[1], stripUniforms[0], y - 1);
                GLES31.glViewport(0, y, outputWidth, rows);
                draw2d(programs[2], 0, textures[2], stripUniforms[1], y - 1);
            }
        }
    }

    private void initEgl(Surface input) throws IOException {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, new int[2], 0, new int[2], 0)) {
            throw new IOException("EGL initialization failed");
        }
        int[] attributes = {EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                0x3142, 1, EGL14.EGL_NONE}; // EGL_RECORDABLE_ANDROID
        EGLConfig[] options = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attributes, 0, options, 0, 1, count, 0) || count[0] == 0) {
            throw new IOException("No recordable EGL config");
        }
        context = EGL14.eglCreateContext(display, options[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
        window = EGL14.eglCreateWindowSurface(display, options[0], input, new int[]{EGL14.EGL_NONE}, 0);
        if (context == EGL14.EGL_NO_CONTEXT || window == EGL14.EGL_NO_SURFACE
                || !EGL14.eglMakeCurrent(display, window, window, context)) {
            throw new IOException("Could not create the FSR rendering context");
        }
    }

    private void target(int index, int width, int height) throws IOException {
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, textures[index + 1]);
        GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES31.GL_RGBA8, width, height);
        setSampling(GLES31.GL_TEXTURE_2D);
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, targets[index]);
        GLES31.glFramebufferTexture2D(GLES31.GL_FRAMEBUFFER, GLES31.GL_COLOR_ATTACHMENT0,
                GLES31.GL_TEXTURE_2D, textures[index + 1], 0);
        if (GLES31.glCheckFramebufferStatus(GLES31.GL_FRAMEBUFFER) != GLES31.GL_FRAMEBUFFER_COMPLETE) {
            throw new IOException("FSR texture allocation failed");
        }
    }

    private static void setSampling(int target) {
        GLES31.glTexParameteri(target, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_LINEAR);
        GLES31.glTexParameteri(target, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_LINEAR);
        GLES31.glTexParameteri(target, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE);
        GLES31.glTexParameteri(target, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE);
    }

    private static void draw2d(int program, int target, int texture, int originUniform, int origin) {
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, target);
        GLES31.glUseProgram(program);
        GLES31.glUniform1i(originUniform, origin);
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, texture);
        GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3);
    }

    private static int program(String fragment) throws IOException {
        int vertex = shader(GLES31.GL_VERTEX_SHADER, VERTEX);
        int pixel = 0;
        try {
            pixel = shader(GLES31.GL_FRAGMENT_SHADER, fragment);
            int program = GLES31.glCreateProgram();
            GLES31.glAttachShader(program, vertex);
            GLES31.glAttachShader(program, pixel);
            GLES31.glLinkProgram(program);
            int[] linked = new int[1];
            GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, linked, 0);
            if (linked[0] != 0) return program;
            String log = GLES31.glGetProgramInfoLog(program);
            GLES31.glDeleteProgram(program);
            throw new IOException("FSR link: " + log);
        } finally {
            GLES31.glDeleteShader(vertex);
            if (pixel != 0) GLES31.glDeleteShader(pixel);
        }
    }

    private static int shader(int type, String source) throws IOException {
        int shader = GLES31.glCreateShader(type);
        GLES31.glShaderSource(shader, source);
        GLES31.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] != 0) return shader;
        String log = GLES31.glGetShaderInfoLog(shader);
        GLES31.glDeleteShader(shader);
        throw new IOException("FSR shader: " + log);
    }

    private static String source(AssetManager assets, String name) throws IOException {
        try (InputStream input = assets.open(name); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int length;
            while ((length = input.read(buffer)) != -1) bytes.write(buffer, 0, length);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Override public void close() {
        if (capture != null) capture.release();
        if (camera != null) camera.release();
        if (display == EGL14.EGL_NO_DISPLAY) return;
        if (context != EGL14.EGL_NO_CONTEXT && EGL14.eglGetCurrentContext().equals(context)) {
            for (int program : programs) if (program != 0) GLES31.glDeleteProgram(program);
            GLES31.glDeleteFramebuffers(targets.length, targets, 0);
            GLES31.glDeleteTextures(textures.length, textures, 0);
        }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window);
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
        EGL14.eglReleaseThread();
        EGL14.eglTerminate(display);
    }
}
