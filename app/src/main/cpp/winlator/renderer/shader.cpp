#include "shader.hpp"
#include "egl.hpp"

#define LOG_TAG "Shader"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static bool compileStage(GLuint shaderId, const char *source) {
    glShaderSource(shaderId, 1, &source, nullptr);
    glCompileShader(shaderId);

    GLint status = GL_FALSE;
    glGetShaderiv(shaderId, GL_COMPILE_STATUS, &status);
    if (status != GL_TRUE) {
        char log[1024] = {0};
        glGetShaderInfoLog(shaderId, sizeof(log) - 1, nullptr, log);
        LOGE("Shader compile failed: %s", log);
        return false;
    }
    return true;
}

Shader::Shader(const char *vertexShader, const char *fragmentShader) {
    programId = glCreateProgram();

    GLuint vertexShaderId = glCreateShader(GL_VERTEX_SHADER);
    GLuint fragmentShaderId = glCreateShader(GL_FRAGMENT_SHADER);
    bool compiled = compileStage(vertexShaderId, vertexShader) && compileStage(fragmentShaderId, fragmentShader);

    glAttachShader(programId, vertexShaderId);
    glAttachShader(programId, fragmentShaderId);
    glLinkProgram(programId);

    GLint linked = GL_FALSE;
    glGetProgramiv(programId, GL_LINK_STATUS, &linked);
    if (compiled && linked != GL_TRUE) {
        char log[1024] = {0};
        glGetProgramInfoLog(programId, sizeof(log) - 1, nullptr, log);
        LOGE("Program link failed: %s", log);
    }
    valid = compiled && linked == GL_TRUE;

    glDeleteShader(vertexShaderId);
    glDeleteShader(fragmentShaderId);

    glGenBuffers(1, &bufferId);
    glBindBuffer(GL_ARRAY_BUFFER, bufferId);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quads), quads, GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    attributeLocations["position"] = glGetAttribLocation(programId, "position");
}

Shader::~Shader() {
    glDeleteProgram(programId);
    glDeleteBuffers(1, &bufferId);
}

void Shader::use() {
    glUseProgram(programId);

    glBindBuffer(GL_ARRAY_BUFFER, bufferId);

    int position = attributeLocations["position"];

    glEnableVertexAttribArray(position);
    glVertexAttribPointer(position, 2, GL_FLOAT, false, 0, 0);
}

void Shader::disable() {
    glDisableVertexAttribArray(attributeLocations["position"]);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

int Shader::getAttributeLoc(const std::string& name) {
    return attributeLocations[name];
}

int Shader::getUniformLoc(const std::string& name) {
    auto it = uniformLocations.find(name);
    if (it != uniformLocations.end())
        return it->second;

    int location = glGetUniformLocation(programId, name.c_str());
    uniformLocations[name] = location;
    return location;
}

DrawableShader::DrawableShader() : Shader(drawable_vert, drawable_frag) {
    uniformLocations["viewSize"] = glGetUniformLocation(programId, "viewSize");
    uniformLocations["texture"] = glGetUniformLocation(programId, "texture");
    uniformLocations["xform"] = glGetUniformLocation(programId, "xform");
    uniformLocations["is_cursor"] = glGetUniformLocation(programId, "is_cursor");
    uniformLocations["swap_colors"] = glGetUniformLocation(programId, "swap_colors");
}
