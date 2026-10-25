#version 300 es
precision highp float;

// ARCore's detected plane boundary is the known usable area, not the
// whole real-world surface. A quiet cyan wash makes that distinction visible.
uniform sampler2D u_Texture;
uniform vec4 u_GridControl;
in vec3 v_TexCoordAlpha;
layout(location = 0) out vec4 o_FragColor;

void main() {
    float confidenceFade = clamp(v_TexCoordAlpha.z, 0.0, 1.0);
    // Keep a barely visible texture cue without restoring the bright grid.
    float detail = texture(u_Texture, v_TexCoordAlpha.xy).g;
    float opacity = (0.10 + 0.025 * detail + 0.005 * u_GridControl.x)
                    * confidenceFade;
    o_FragColor = vec4(0.12, 0.86, 0.78, opacity);
}
