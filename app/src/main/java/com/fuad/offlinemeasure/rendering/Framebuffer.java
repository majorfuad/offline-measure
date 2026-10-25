package com.fuad.offlinemeasure.rendering;

import android.opengl.GLES30;
import android.util.Log;
import java.io.Closeable;

/** A framebuffer associated with a texture. */
public class Framebuffer implements Closeable {
  private static final String TAG = Framebuffer.class.getSimpleName();

  private final int[] framebufferId = {0};
  private final Texture colorTexture;
  private final Texture depthTexture;
  private int width = -1;
  private int height = -1;

  public Framebuffer(SampleRender render, int width, int height) {
    try {
      colorTexture =
          new Texture(
              render,
              Texture.Target.TEXTURE_2D,
              Texture.WrapMode.CLAMP_TO_EDGE,
              false);
      depthTexture =
          new Texture(
              render,
              Texture.Target.TEXTURE_2D,
              Texture.WrapMode.CLAMP_TO_EDGE,
              false);

      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTexture.getTextureId());
      GLError.maybeThrowGLException("Failed to bind depth texture", "glBindTexture");
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_NONE);
      GLError.maybeThrowGLException("Failed to set texture parameter", "glTexParameteri");
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
      GLError.maybeThrowGLException("Failed to set texture parameter", "glTexParameteri");
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
      GLError.maybeThrowGLException("Failed to set texture parameter", "glTexParameteri");

      resize(width, height);

      GLES30.glGenFramebuffers(1, framebufferId, 0);
      GLError.maybeThrowGLException("Framebuffer creation failed", "glGenFramebuffers");
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId[0]);
      GLError.maybeThrowGLException("Failed to bind framebuffer", "glBindFramebuffer");
      GLES30.glFramebufferTexture2D(
          GLES30.GL_FRAMEBUFFER,
          GLES30.GL_COLOR_ATTACHMENT0,
          GLES30.GL_TEXTURE_2D,
          colorTexture.getTextureId(),
          0);
      GLError.maybeThrowGLException(
          "Failed to bind color texture to framebuffer", "glFramebufferTexture2D");
      GLES30.glFramebufferTexture2D(
          GLES30.GL_FRAMEBUFFER,
          GLES30.GL_DEPTH_ATTACHMENT,
          GLES30.GL_TEXTURE_2D,
          depthTexture.getTextureId(),
          0);
      GLError.maybeThrowGLException(
          "Failed to bind depth texture to framebuffer", "glFramebufferTexture2D");

      int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
      if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
        throw new IllegalStateException("Framebuffer construction not complete: code " + status);
      }
    } catch (Throwable t) {
      close();
      throw t;
    }
  }

  @Override
  public void close() {
    if (framebufferId[0] != 0) {
      GLES30.glDeleteFramebuffers(1, framebufferId, 0);
      GLError.maybeLogGLError(Log.WARN, TAG, "Failed to free framebuffer", "glDeleteFramebuffers");
      framebufferId[0] = 0;
    }
    colorTexture.close();
    depthTexture.close();
  }

  public void resize(int width, int height) {
    if (this.width == width && this.height == height) {
      return;
    }
    this.width = width;
    this.height = height;

    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorTexture.getTextureId());
    GLError.maybeThrowGLException("Failed to bind color texture", "glBindTexture");
    GLES30.glTexImage2D(
        GLES30.GL_TEXTURE_2D,
        0,
        GLES30.GL_RGBA,
        width,
        height,
        0,
        GLES30.GL_RGBA,
        GLES30.GL_UNSIGNED_BYTE,
        null);
    GLError.maybeThrowGLException("Failed to specify color texture format", "glTexImage2D");

    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTexture.getTextureId());
    GLError.maybeThrowGLException("Failed to bind depth texture", "glBindTexture");
    GLES30.glTexImage2D(
        GLES30.GL_TEXTURE_2D,
        0,
        GLES30.GL_DEPTH_COMPONENT32F,
        width,
        height,
        0,
        GLES30.GL_DEPTH_COMPONENT,
        GLES30.GL_FLOAT,
        null);
    GLError.maybeThrowGLException("Failed to specify depth texture format", "glTexImage2D");
  }

  public Texture getColorTexture() {
    return colorTexture;
  }

  public Texture getDepthTexture() {
    return depthTexture;
  }

  public int getWidth() {
    return width;
  }

  public int getHeight() {
    return height;
  }

  int getFramebufferId() {
    return framebufferId[0];
  }
}
