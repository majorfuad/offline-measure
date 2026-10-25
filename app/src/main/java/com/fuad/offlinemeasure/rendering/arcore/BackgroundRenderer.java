package com.fuad.offlinemeasure.rendering.arcore;

import android.media.Image;
import android.opengl.GLES30;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.fuad.offlinemeasure.rendering.Framebuffer;
import com.fuad.offlinemeasure.rendering.Mesh;
import com.fuad.offlinemeasure.rendering.SampleRender;
import com.fuad.offlinemeasure.rendering.Shader;
import com.fuad.offlinemeasure.rendering.Texture;
import com.fuad.offlinemeasure.rendering.VertexBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;

public class BackgroundRenderer {
  private static final String TAG = BackgroundRenderer.class.getSimpleName();

  private static final int COORDS_BUFFER_SIZE = 2 * 4 * 4;

  private static final FloatBuffer NDC_QUAD_COORDS_BUFFER =
      ByteBuffer.allocateDirect(COORDS_BUFFER_SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer();

  private static final FloatBuffer VIRTUAL_SCENE_TEX_COORDS_BUFFER =
      ByteBuffer.allocateDirect(COORDS_BUFFER_SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer();

  static {
    NDC_QUAD_COORDS_BUFFER.put(
        new float[] {
          -1f, -1f, +1f, -1f, -1f, +1f, +1f, +1f,
        });
    VIRTUAL_SCENE_TEX_COORDS_BUFFER.put(
        new float[] {
          0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f,
        });
  }

  private final FloatBuffer cameraTexCoords =
      ByteBuffer.allocateDirect(COORDS_BUFFER_SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer();

  private final Mesh mesh;
  private final VertexBuffer cameraTexCoordsVertexBuffer;
  private Shader backgroundShader;
  private Shader occlusionShader;
  private final Texture cameraDepthTexture;
  private final Texture cameraColorTexture;
  private Texture depthColorPaletteTexture;

  private boolean useDepthVisualization;
  private boolean useOcclusion;
  private float aspectRatio;

  public BackgroundRenderer(SampleRender render) {
    cameraColorTexture =
        new Texture(
            render,
            Texture.Target.TEXTURE_EXTERNAL_OES,
            Texture.WrapMode.CLAMP_TO_EDGE,
            false);
    cameraDepthTexture =
        new Texture(
            render,
            Texture.Target.TEXTURE_2D,
            Texture.WrapMode.CLAMP_TO_EDGE,
            false);

    VertexBuffer screenCoordsVertexBuffer =
        new VertexBuffer(render, 2, NDC_QUAD_COORDS_BUFFER);
    cameraTexCoordsVertexBuffer =
        new VertexBuffer(render, 2, null);
    VertexBuffer virtualSceneTexCoordsVertexBuffer =
        new VertexBuffer(render, 2, VIRTUAL_SCENE_TEX_COORDS_BUFFER);
    VertexBuffer[] vertexBuffers = {
      screenCoordsVertexBuffer, cameraTexCoordsVertexBuffer, virtualSceneTexCoordsVertexBuffer,
    };
    mesh =
        new Mesh(render, Mesh.PrimitiveMode.TRIANGLE_STRIP, null, vertexBuffers);
  }

  public void setUseDepthVisualization(SampleRender render, boolean useDepthVisualization)
      throws IOException {
    if (backgroundShader != null) {
      if (this.useDepthVisualization == useDepthVisualization) {
        return;
      }
      backgroundShader.close();
      backgroundShader = null;
      this.useDepthVisualization = useDepthVisualization;
    }
    if (useDepthVisualization) {
     depthColorPaletteTexture =
        Texture.createFromAsset(
            render,
            "models/depth_color_palette.png",
            Texture.WrapMode.CLAMP_TO_EDGE,
            Texture.ColorFormat.LINEAR);
      backgroundShader =
          Shader.createFromAssets(
                  render,
                  "shaders/background_show_depth_color_visualization.vert",
                  "shaders/background_show_depth_color_visualization.frag",
                  null)
              .setTexture("u_CameraDepthTexture", cameraDepthTexture)
              .setTexture("u_ColorMap", depthColorPaletteTexture)
              .setDepthTest(false)
              .setDepthWrite(false);
    } else {
      backgroundShader =
          Shader.createFromAssets(
                  render,
                  "shaders/background_show_camera.vert",
                  "shaders/background_show_camera.frag",
                  null)
              .setTexture("u_CameraColorTexture", cameraColorTexture)
              .setDepthTest(false)
              .setDepthWrite(false);
    }
  }

  public void setUseOcclusion(SampleRender render, boolean useOcclusion) throws IOException {
    if (occlusionShader != null) {
      if (this.useOcclusion == useOcclusion) {
        return;
      }
      occlusionShader.close();
      occlusionShader = null;
      this.useOcclusion = useOcclusion;
    }
    HashMap<String, String> defines = new HashMap<>();
    defines.put("USE_OCCLUSION", useOcclusion ? "1" : "0");
    occlusionShader =
        Shader.createFromAssets(render, "shaders/occlusion.vert", "shaders/occlusion.frag", defines)
            .setDepthTest(false)
            .setDepthWrite(false)
            .setBlend(Shader.BlendFactor.SRC_ALPHA, Shader.BlendFactor.ONE_MINUS_SRC_ALPHA);
    if (useOcclusion) {
      occlusionShader
          .setTexture("u_CameraDepthTexture", cameraDepthTexture)
          .setFloat("u_DepthAspectRatio", aspectRatio);
    }
  }

  public void updateDisplayGeometry(Frame frame) {
    if (frame.hasDisplayGeometryChanged()) {
      frame.transformCoordinates2d(
          Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
          NDC_QUAD_COORDS_BUFFER,
          Coordinates2d.TEXTURE_NORMALIZED,
          cameraTexCoords);
      cameraTexCoordsVertexBuffer.set(cameraTexCoords);
    }
  }

  public void updateCameraDepthTexture(Image image) {
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraDepthTexture.getTextureId());
    GLES30.glTexImage2D(
        GLES30.GL_TEXTURE_2D,
        0,
        GLES30.GL_RG8,
        image.getWidth(),
        image.getHeight(),
        0,
        GLES30.GL_RG,
        GLES30.GL_UNSIGNED_BYTE,
        image.getPlanes()[0].getBuffer());
    if (useOcclusion) {
      aspectRatio = (float) image.getWidth() / (float) image.getHeight();
      occlusionShader.setFloat("u_DepthAspectRatio", aspectRatio);
    }
  }

  public void drawBackground(SampleRender render) {
    render.draw(mesh, backgroundShader);
  }

  public void drawVirtualScene(
      SampleRender render, Framebuffer virtualSceneFramebuffer, float zNear, float zFar) {
    occlusionShader.setTexture(
        "u_VirtualSceneColorTexture", virtualSceneFramebuffer.getColorTexture());
    if (useOcclusion) {
      occlusionShader
          .setTexture("u_VirtualSceneDepthTexture", virtualSceneFramebuffer.getDepthTexture())
          .setFloat("u_ZNear", zNear)
          .setFloat("u_ZFar", zFar);
    }
    render.draw(mesh, occlusionShader);
  }

  public Texture getCameraColorTexture() {
    return cameraColorTexture;
  }

  public Texture getCameraDepthTexture() {
    return cameraDepthTexture;
  }
}
