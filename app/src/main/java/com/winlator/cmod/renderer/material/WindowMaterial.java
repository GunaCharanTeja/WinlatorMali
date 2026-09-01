package com.winlator.cmod.renderer.material;

public class WindowMaterial extends ShaderMaterial {
    public int uXForm = -1;
    public int uViewSize = -1;
    public int uTexture = -1;

    public WindowMaterial() {
        setUniformNames("xform", "viewSize", "texture");
    }

    @Override
    public void use() {
        super.use();
        if (uXForm == -1) {
            uXForm = getUniformLocation("xform");
            uViewSize = getUniformLocation("viewSize");
            uTexture = getUniformLocation("texture");
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        uXForm = -1;
        uViewSize = -1;
        uTexture = -1;
    }

    @Override
    protected String getVertexShader() {
        return
            "uniform float xform[6];\n" +
            "uniform vec2 viewSize;\n" +
            "attribute vec2 position;\n" +
            "varying vec2 vUV;\n" +

            "void main() {\n" +
                "vUV = position;\n" +
                "vec2 transformedPos = applyXForm(position, xform);\n" +
                "gl_Position = vec4(2.0 * transformedPos.x / viewSize.x - 1.0, 1.0 - 2.0 * transformedPos.y / viewSize.y, 0.0, 1.0);\n" +
            "}"
        ;
    }

    @Override
    protected String getFragmentShader() {
        return
            "precision mediump float;\n" +

            "uniform sampler2D texture;\n" +
            "varying vec2 vUV;\n" +

            "void main() {\n" +
                "gl_FragColor = vec4(texture2D(texture, vUV).rgb, 1.0);\n" +
            "}"
        ;
    }
}
