package com.subhub.app.capture.export;

import android.graphics.Bitmap;
import android.opengl.*;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Owns the encoder EGL context; called only on the export worker. */
public final class CodecInputSurface implements AutoCloseable {
    private final Surface surface;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private int program;
    private int texture;
    private final FloatBuffer vertices = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    public CodecInputSurface(Surface surface) {
        this.surface = surface;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] versions = new int[2];
            if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) fail("EGL initialization");
            int[] attributes = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, 0x3142, 1, EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
                    || count[0] == 0) fail("Encoder EGL configuration");
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            window = EGL14.eglCreateWindowSurface(display, configs[0], surface,
                    new int[] {EGL14.EGL_NONE}, 0);
            if (!EGL14.eglMakeCurrent(display, window, window, context)) fail("Encoder EGL surface");
            int vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 position; attribute vec2 uv; varying vec2 tex; void main(){gl_Position=vec4(position,0.0,1.0);tex=uv;}");
            int fragment = shader(GLES20.GL_FRAGMENT_SHADER, "precision mediump float; varying vec2 tex; uniform sampler2D image; void main(){gl_FragColor=texture2D(image,tex);}");
            program = GLES20.glCreateProgram(); GLES20.glAttachShader(program, vertex);
            GLES20.glAttachShader(program, fragment); GLES20.glLinkProgram(program);
            GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment);
            int[] linked = new int[1]; GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) fail("Encoder shader linking");
            int[] textures = new int[1]; GLES20.glGenTextures(1, textures, 0); texture = textures[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            vertices.put(new float[] {-1,-1,0,1, 1,-1,1,1, -1,1,0,0, 1,1,1,0}).position(0);
        } catch (RuntimeException error) { close(); throw error; }
    }
    private static int shader(int type, String source) {
        int value = GLES20.glCreateShader(type); GLES20.glShaderSource(value, source);
        GLES20.glCompileShader(value); int[] ok = new int[1];
        GLES20.glGetShaderiv(value, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) { GLES20.glDeleteShader(value); fail("Encoder shader compilation"); }
        return value;
    }
    public void draw(Bitmap bitmap, long presentationTimeUs) {
        GLES20.glViewport(0, 0, bitmap.getWidth(), bitmap.getHeight());
        GLES20.glUseProgram(program); GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        int position = GLES20.glGetAttribLocation(program, "position");
        int uv = GLES20.glGetAttribLocation(program, "uv");
        vertices.position(0); GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices);
        vertices.position(2); GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices);
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(uv);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) fail("Encoder drawing");
        if (!EGLExt.eglPresentationTimeANDROID(display, window, presentationTimeUs * 1000L)
                || !EGL14.eglSwapBuffers(display, window)) fail("Encoder frame submission");
    }
    private static void fail(String stage) { throw new IllegalStateException(stage + " failed"); }
    @Override public void close() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (program != 0) GLES20.glDeleteProgram(program);
            if (texture != 0) GLES20.glDeleteTextures(1, new int[] {texture}, 0);
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread(); EGL14.eglTerminate(display);
        }
        display = EGL14.EGL_NO_DISPLAY; surface.release();
    }
}
