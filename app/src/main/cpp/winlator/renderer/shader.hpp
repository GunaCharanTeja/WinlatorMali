#pragma once

#include <string>
#include <unordered_map>
#include <GLES3/gl3.h>

class Shader {
    protected:
        std::unordered_map<std::string, int> uniformLocations;
        std::unordered_map<std::string, int> attributeLocations;
        int programId = 0;
        GLuint bufferId = 0;
        bool valid = false;
        float quads[8] = {
            0.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 0.0f,
            1.0f, 1.0f
        };

    public:
        Shader(const char *vertexShader, const char *fragmentShader);
        virtual ~Shader();
        void use();
        void disable();
        bool isValid() const { return valid; }
        int getUniformLoc(const std::string& name);
        int getAttributeLoc(const std::string& name);
};

class DrawableShader : public Shader {
    private:
        static constexpr const char *drawable_vert = R"GLSL(
            precision mediump float;
            uniform float xform[6];
            uniform vec2 viewSize;
            attribute vec2 position;
            varying vec2 vUV;

            vec2 applyXForm(vec2 p, float xform[6]) {
                return vec2(xform[0] * p.x + xform[2] * p.y + xform[4], xform[1] * p.x + xform[3] * p.y + xform[5]);
            }

            void main() {
                vUV = position;
                vec2 transformedPos = applyXForm(position, xform);
                gl_Position = vec4(2.0 * transformedPos.x / viewSize.x - 1.0, 1.0 - 2.0 * transformedPos.y / viewSize.y, 0.0, 1.0);
            }
        )GLSL";

        static constexpr const char *drawable_frag = R"GLSL(
            precision mediump float;

            uniform sampler2D texture;
            varying vec2 vUV;
            uniform int is_cursor;
            uniform int swap_colors;

            void main() {
                vec4 color = texture2D(texture, vUV);
                if (swap_colors == 1)
                    color = color.bgra;
                if (is_cursor == 0)
                    color.a = 1.0;
                gl_FragColor = color;
            }
        )GLSL";

    public:
        DrawableShader();
};
