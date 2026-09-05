#include "arb_program.h"
#include "gl_context.h"

static GLuint maxProgramId = 1;

enum Inst {INST_NONE, INST_ABS, INST_ADD, INST_ARL, INST_CMP, INST_COS, INST_DP3, INST_DP4, INST_DPH, INST_DST, INST_EX2, INST_EXP, INST_FLR, INST_FRC, INST_KIL, INST_LG2, INST_LIT, INST_LOG, INST_LRP, INST_MAD, INST_MAX, INST_MIN, INST_MOV, INST_MUL, INST_POW, INST_RCP, INST_RSQ, INST_SCS, INST_SGE, INST_SIN, INST_SLT, INST_SUB, INST_SWZ, INST_TEX, INST_TXB, INST_TXP, INST_XPD};

#define TYPE_QUALIFIER_ADDRESS 1
#define TYPE_QUALIFIER_ATTRIB 2
#define TYPE_QUALIFIER_PARAM 3
#define TYPE_QUALIFIER_TEMP 4
#define TYPE_QUALIFIER_ALIAS 5
#define TYPE_QUALIFIER_OUTPUT 6

struct ReservedGLName {
    char* name;
    char* replace;
    bool suffixUnit;
    GLenum type;
};

typedef struct ASMSource {
    char* string;
    ArrayMap variables;
    IntArray genericAttribs;
    GLenum type;
    char samplerTypes[MAX_TEXTURES];
    uint8_t maxTexCoords;
} ASMSource;

static struct ReservedGLName reservedGLNames[] = {
    {"program.env", "gd_ProgramEnv0", false, GL_VERTEX_PROGRAM_ARB},
    {"program.local", "gd_ProgramLocal0", false, GL_VERTEX_PROGRAM_ARB},
    {"program.env", "gd_ProgramEnv1", false, GL_FRAGMENT_PROGRAM_ARB},
    {"program.local", "gd_ProgramLocal1", false, GL_FRAGMENT_PROGRAM_ARB},
    {"vertex.position", "gd_Vertex", false, GL_VERTEX_PROGRAM_ARB},
    {"vertex.normal", "vec4(gd_Normal, 1.0)", false, GL_VERTEX_PROGRAM_ARB},
    {"vertex.color.primary", "gd_Color", false, GL_VERTEX_PROGRAM_ARB},
    {"vertex.color", "gd_Color", false, GL_VERTEX_PROGRAM_ARB},
    {"vertex.texcoord", "gd_MultiTexCoord", true, GL_VERTEX_PROGRAM_ARB},
    {"fragment.texcoord", "gd_TexCoord", true, GL_FRAGMENT_PROGRAM_ARB},
    {"texture", "gd_Texture", true, GL_FRAGMENT_PROGRAM_ARB},
    {"result.position", "gl_Position", false, GL_VERTEX_PROGRAM_ARB},
    {"result.color.primary", "gd_FrontColor", false, GL_VERTEX_PROGRAM_ARB},
    {"result.color", "gd_FrontColor", false, GL_VERTEX_PROGRAM_ARB},
    {"result.color", "gd_FragColor", false, GL_FRAGMENT_PROGRAM_ARB},
    {"result.texcoord", "gd_TexCoord", true, GL_VERTEX_PROGRAM_ARB},
    {"fragment.color.primary", "gd_FrontColor", false, GL_FRAGMENT_PROGRAM_ARB},
    {"fragment.color", "gd_FrontColor", false, GL_FRAGMENT_PROGRAM_ARB},
    {"vertex.attrib", "gd_GenericAttrib", true, GL_VERTEX_PROGRAM_ARB}
};

static void getInstructionMap(ArrayMap* instructionMap) {
    static const char* instructions[] = {"ABS", "ADD", "ARL", "CMP", "COS", "DP3", "DP4", "DPH", "DST", "EX2", "EXP", "FLR", "FRC", "KIL", "LG2", "LIT", "LOG", "LRP", "MAD", "MAX", "MIN", "MOV", "MUL", "POW", "RCP", "RSQ", "SCS", "SGE", "SIN", "SLT", "SUB", "SWZ", "TEX", "TXB", "TXP", "XPD"};
    for (int i = 0; i < ARRAY_SIZE(instructions); i++) {
        ArrayMap_put(instructionMap, instructions[i], (void*)(uint64_t)(i + 1));
    }
}

static ARBUniform* parseARBUniform(char* string) {
    ARBUniform* uniform = calloc(1, sizeof(ARBUniform));
    uniform->location = -1;
    if (strstr(string, "vec4")) {
        uniform->type = ARB_UNIFORM_TYPE_CONST;
        sscanf(string, "vec4(%f, %f, %f, %f)", &uniform->value[0], &uniform->value[1], &uniform->value[2], &uniform->value[3]);
    }
    else {
        int index;
        if (sscanf(string, "gd_ProgramLocal0[%d]", &index) ||
            sscanf(string, "gd_ProgramLocal1[%d]", &index)) {
            uniform->type = ARB_UNIFORM_TYPE_PROGRAM_LOCAL;
        }
        else if (sscanf(string, "gd_ProgramEnv0[%d]", &index) ||
                 sscanf(string, "gd_ProgramEnv1[%d]", &index)) {
            uniform->type = ARB_UNIFORM_TYPE_PROGRAM_ENV;
        }
        uniform->index = index;
    }
    free(string);
    return uniform;
}

static void extractInstOperands(char* line, ArrayList* result) {
    char* chr = line;
    int groupCount = 0;
    int nameStart = -1;

    while (1) {
        if (*chr == '{') {
            groupCount++;
            if (groupCount > 0 && nameStart == -1) nameStart = chr - line;
        }
        else if (*chr == '}') {
            groupCount--;
        }
        else if (groupCount == 0 && nameStart == -1 && !isspace(*chr)) {
            nameStart = chr - line;
        }

        if (((*chr == ',' || *chr == '\0') && groupCount == 0) && nameStart != -1) {
            int nameEnd = chr - line;
            while (isspace(line[nameEnd-1])) nameEnd--;
            ArrayList_add(result, substr(line, nameStart, nameEnd - nameStart));
            nameStart = -1;
        }

        if (*chr == '\0') break;
        chr++;
    }
}

static void extractArrayVariableParams(char* line, ArrayList* result) {
    char* chr = line;
    int groupCount = 0;
    int nameStart = -1;

    while (*chr) {
        if (*chr == '{') {
            groupCount++;
            if (groupCount > 1 && nameStart == -1) nameStart = chr - line;
        }
        else if (*chr == '}') {
            groupCount--;
        }
        else if (groupCount == 1 && nameStart == -1 && !isspace(*chr)) {
            nameStart = chr - line;
        }

        if (((*chr == ',' && groupCount == 1) || (*chr == '}' && groupCount == 0)) && nameStart != -1) {
            int nameEnd = chr - line;
            while (isspace(line[nameEnd-1])) nameEnd--;
            ArrayList_add(result, substr(line, nameStart, nameEnd - nameStart));
            nameStart = -1;
        }

        if (*chr == '}' && groupCount == 0) break;
        chr++;
    }
}

static bool extractVariableArrayRangeIndex(char* operand, int* range) {
    char string[64];
    substrv(operand, '[', ']', string);
    char* value = trim(string);
    char* dots = strstr(string, "..");
    range[0] = -1;
    range[1] = -1;
    if (dots) {
        dots[0] = '\0';
        if (is_int(value) && is_int(dots+2)) {
            range[0] = strtol(value, NULL, 10);
            range[1] = strtol(dots+2, NULL, 10);
        }
        dots[0] = '.';
    }
    return range[0] >= 0 && range[1] >= 0;
}

static void parseInstOperand(char* operand, ASMSource* asmSource, ArrayBuffer* result) {
    if (operand[0] == '{') {
        ArrayList params = {0};
        extractArrayVariableParams(operand, &params);

        ArrayBuffer_putString(result, "vec4(");
        for (int i = 0; i < params.size; i++) {
            char* elementValue = params.elements[i];
            if (i > 0) ArrayBuffer_putString(result, ", ");
            parseInstOperand(elementValue, asmSource, result);
        }

        if (params.size == 1) {
            ArrayBuffer_putString(result, ", 0.0, 0.0, 1.0)");
        }
        else if (params.size == 2) {
            ArrayBuffer_putString(result, ", 0.0, 1.0)");
        }
        else if (params.size == 3) {
            ArrayBuffer_putString(result, ", 1.0)");
        }
        else ArrayBuffer_put(result, ')');
        ArrayList_free(&params, true);
    }
    else {
        if (is_int(operand)) {
            ArrayBuffer_putString(result, "%s.0", operand);
        }
        else if (is_float(operand)) {
            ArrayBuffer_putString(result, operand);
        }
        else if (strcmp(operand, "1D") == 0 ||
                 strcmp(operand, "2D") == 0 ||
                 strcmp(operand, "3D") == 0 ||
                 strcmp(operand, "CUBE") == 0) {
            ArrayBuffer_putString(result, operand);
        }
        else {
            MARK_VARIABLE_NAME(operand);
            ARBVariable* variable = ArrayMap_get(&asmSource->variables, operand);
            UNMARK_VARIABLE_NAME(operand);

            if (variable) {
                if (variable->type == TYPE_QUALIFIER_PARAM) {
                    char newName[64];
                    sprintf(newName, "%s%u", variable->name, indexOfGLTarget(asmSource->type));
                    operand = strwrd_replace(variable->name, newName, strdup(operand));
                    ArrayBuffer_putString(result, operand);
                    free(operand);
                }
                else if (variable->type == TYPE_QUALIFIER_OUTPUT ||
                         variable->type == TYPE_QUALIFIER_ALIAS) {
                    operand = strwrd_replace(variable->name, variable->value, strdup(operand));
                    ArrayBuffer_putString(result, operand);
                    free(operand);
                }
                else ArrayBuffer_putString(result, operand);
            }
            else {
                for (int i = 0; i < ARRAY_SIZE(reservedGLNames); i++) {
                    if (reservedGLNames[i].type != asmSource->type) continue;
                    char* name = operand;
                    bool isSigned = name[0] == '-';
                    if (isSigned) name++;
                    if (cstartswith(reservedGLNames[i].name, name)) {
                        const char* sign = isSigned ? "-" : "";
                        if (cstartswith("program.", name)) {
                            int range[2];
                            if (extractVariableArrayRangeIndex(operand, range)) {
                                for (int j = range[0]; j <= range[1]; j++) {
                                    if (j > range[0]) ArrayBuffer_putString(result, ", ");
                                    ArrayBuffer_putString(result, "%s%s[%d]", sign, reservedGLNames[i].replace, j);
                                }
                            }
                            else ArrayBuffer_putString(result, "%s%s%s", sign, reservedGLNames[i].replace, name + strlen(reservedGLNames[i].name));
                        }
                        else if (reservedGLNames[i].suffixUnit) {
                            int index = MAX(extractVariableArrayIndex(operand), 0);
                            if (strstr(reservedGLNames[i].name, "vertex.attrib")) {
                                index += GENERIC_VERTEX_ARRAY_INDEX;
                                if (IntArray_indexOf(&asmSource->genericAttribs, index) == -1) IntArray_add(&asmSource->genericAttribs, index);
                            }

                            ArrayBuffer_putString(result, "%s%s%d", sign, reservedGLNames[i].replace, index);
                            if (strstr(reservedGLNames[i].name, "texcoord")) {
                                asmSource->maxTexCoords = MAX(asmSource->maxTexCoords, index + 1);
                            }

                            char* bracket = strchr(operand, '[');
                            if (bracket) {
                                char* dot = strchr(bracket, '.');
                                if (dot) ArrayBuffer_putString(result, "%s", dot);
                            }
                        }
                        else ArrayBuffer_putString(result, "%s%s%s", sign, reservedGLNames[i].replace, name + strlen(reservedGLNames[i].name));
                        break;
                    }
                }
            }
        }
    }
}

static void parseDataTypeQualifier(int type, char* line, ASMSource* asmSource, ArrayBuffer* result) {
    char* wordEnd = NULL;
    char* wordStart = strwrd(line, NULL, &wordEnd);

    ARBVariable* variable = NULL;
    if (wordStart) {
        char oldChar = *wordEnd;
        *wordEnd = '\0';
        if (result) ArrayBuffer_putString(result, "%s %s", type == TYPE_QUALIFIER_ADDRESS ? "ivec4" : "vec4", wordStart);
        variable = calloc(1, sizeof(ARBVariable));
        variable->name = strdup(wordStart);
        variable->type = type;
        *wordEnd = oldChar;
    }
    else return;

    if (wordEnd) wordEnd = ltrim(wordEnd);
    int arraySize = wordEnd && wordEnd[0] == '[' ? extractVariableArrayIndex(line) : 0;
    char* operand = strchr(line, '=');
    if (operand && ++operand) {
        operand = ltrim(operand);
        if (arraySize > 0 && operand[0] == '{') {
            ArrayList params = {0};
            extractArrayVariableParams(operand, &params);
            if (arraySize == INT32_MAX) arraySize = params.size;
            variable->arraySize = arraySize;

            if (result) ArrayBuffer_putString(result, "[%d] = vec4[%d](", arraySize, arraySize);
            for (int i = 0; i < params.size; i++) {
                char* elementValue = params.elements[i];
                if (type == TYPE_QUALIFIER_PARAM) {
                    if (strstr(elementValue, "..")) {
                        ArrayBuffer string = {0};
                        parseInstOperand(elementValue, asmSource, &string);
                        ArrayBuffer_put(&string, '\0');
                        ArrayList strings = {0};
                        extractInstOperands(string.buffer, &strings);

                        ArrayBuffer_free(&string);
                        for (int j = 0; j < strings.size; j++) ArrayList_add(&variable->uniforms, parseARBUniform(strings.elements[j]));
                        ArrayList_free(&strings, false);
                    }
                    else {
                        ArrayBuffer string = {0};
                        parseInstOperand(elementValue, asmSource, &string);
                        ArrayBuffer_put(&string, '\0');
                        ArrayList_add(&variable->uniforms, parseARBUniform(string.buffer));
                    }
                }
                else {
                    if (i > 0) ArrayBuffer_putString(result, ", ");
                    parseInstOperand(elementValue, asmSource, result);
                }
            }
            if (result) ArrayBuffer_put(result, ')');
            ArrayList_free(&params, true);
        }
        else {
            if (type == TYPE_QUALIFIER_PARAM) {
                ArrayBuffer string = {0};
                ArrayBuffer_putString(&string, "vec4(");
                parseInstOperand(operand, asmSource, &string);
                ArrayBuffer_put(&string, ')');
                ArrayBuffer_put(&string, '\0');
                ArrayList_add(&variable->uniforms, parseARBUniform(string.buffer));
            }
            else if (type == TYPE_QUALIFIER_OUTPUT || type == TYPE_QUALIFIER_ALIAS) {
                ArrayBuffer string = {0};
                parseInstOperand(operand, asmSource, &string);
                ArrayBuffer_put(&string, '\0');
                variable->value = string.buffer;
            }
            else {
                ArrayBuffer_putString(result, " = vec4(");
                parseInstOperand(operand, asmSource, result);
                ArrayBuffer_put(result, ')');
            }
        }
    }

    if (result) ArrayBuffer_putString(result, ";\n");
    if (wordEnd && wordEnd[0] == ',') parseDataTypeQualifier(type, wordEnd + 1, asmSource, result);
    ArrayMap_put(&asmSource->variables, variable->name, variable);
}

static char* getOperandTypeInfo(char* operand, char* mask, int* componentStart) {
    for (int i = 0; i < ARRAY_SIZE(reservedGLNames); i++) {
        if (cstartswith(reservedGLNames[i].name, operand) && strchr(reservedGLNames[i].name, '.')) {
            operand = strchr(operand, '.') + 1;
            break;
        }
    }

    char* dot = strrchr(operand, '.');
    if (!dot) goto error;
    char* component = dot + 1;

    int componentCount = 0;
    int i = 0;
    while (*component && i++ < 4) {
        switch (*component) {
            case 'x':
            case 'y':
            case 'z':
            case 'w':
            case 'r':
            case 'g':
            case 'b':
            case 'a':
            case 's':
            case 't':
            case 'p':
            case 'q':
                if (mask) mask[componentCount] = *component;
                componentCount++;
                break;
            default:
                goto error;
        }
        component++;
    }

    char* componentType = NULL;
    switch (componentCount) {
        case 1:
            componentType = "float";
            break;
        case 2:
            componentType = "vec2";
            break;
        case 3:
            componentType = "vec3";
            break;
        case 4:
            componentType = "vec4";
            break;
        default:
            goto error;
    }

    if (componentStart) *componentStart = dot - operand;
    if (mask) mask[componentCount] = '\0';
    return componentType;
error:
    if (mask) mask[0] = '\0';
    if (componentStart) *componentStart = -1;
    return NULL;
}

static void checkSourceSwizzle(ArrayList* operands) {
    for (int i = 1; i < operands->size; i++) {
        int componentStart;
        char* operand = operands->elements[i];
        char srcMask[6] = {0};
        char* srcType = getOperandTypeInfo(operand, srcMask, &componentStart);
        if (!srcType) continue;
        operand[componentStart] = '\0';

        uint8_t srcComponentCount = strlen(srcMask);
        char lastComponent = srcMask[srcComponentCount-1];
        for (int j = srcComponentCount; j < 4; j++) srcMask[j] = lastComponent;
        srcMask[4] = '\0';

        char* string;
        asprintf(&string, "%s.%s", operand, srcMask);
        free(operands->elements[i]);
        operands->elements[i] = string;
    }
}

static void iterateASMCodeLines(ArrayMap* instructionMap, ASMSource* asmSource, ArrayBuffer* shaderCode) {
    if (!cstartswith("!!ARBvp1.0", asmSource->string) &&
        !cstartswith("!!ARBfp1.0", asmSource->string)) return;

    char* asmString = strdup(asmSource->string + 10);
    char* chr = asmString;
    int wordStart = -1;

    while (1) {
        if (*chr == '#') {
            while (*chr && *chr != '\n') chr++;
        }
        if (*chr == '\n') *chr = '\t';

        if (isupper(*chr) || (wordStart != -1 && isdigit(*chr))) {
            if (wordStart == -1) wordStart = chr - asmString;
        }
        else if (wordStart != -1) {
            char* word = asmString + wordStart;

            char oldChar = *chr;
            *chr = '\0';

            MARK_END_LINE(chr + 1);

            if (strcmp(word, "ADDRESS") == 0) {
                parseDataTypeQualifier(TYPE_QUALIFIER_ADDRESS, chr + 1, asmSource, shaderCode);
            }
            else if (strcmp(word, "TEMP") == 0) {
                parseDataTypeQualifier(TYPE_QUALIFIER_TEMP, chr + 1, asmSource, shaderCode);
            }
            else if (strcmp(word, "ATTRIB") == 0) {
                parseDataTypeQualifier(TYPE_QUALIFIER_ATTRIB, chr + 1, asmSource, shaderCode);
            }
            else if (strcmp(word, "PARAM") == 0) {
                parseDataTypeQualifier(TYPE_QUALIFIER_PARAM, chr + 1, asmSource, NULL);
            }
            else if (strcmp(word, "OUTPUT") == 0) {
                parseDataTypeQualifier(TYPE_QUALIFIER_OUTPUT, chr + 1, asmSource, NULL);
            }
            else if (strcmp(word, "ALIAS") == 0) {
                parseDataTypeQualifier(TYPE_QUALIFIER_ALIAS, chr + 1, asmSource, NULL);
            }
            else if (strcmp(word, "END") == 0) {
                UNMARK_END_LINE(chr + 1);
                *chr = oldChar;
                break;
            }
            else if (strcmp(word, "OPTION") == 0) {
                println("gladio: unimplemented statement OPTION: %s", chr + 1);
            }
            else {
                void* value = ArrayMap_get(instructionMap, word);
                uint64_t inst = (uint64_t)value;
                *chr = oldChar;

                ArrayList operands = {0};
                extractInstOperands(chr + 1, &operands);
                if (operands.size == 0) goto freeoperands;

                for (int i = 0; i < operands.size; i++) {
                    ArrayBuffer string = {0};
                    char* old;
                    parseInstOperand(old = operands.elements[i], asmSource, &string);
                    ArrayBuffer_put(&string, '\0');
                    operands.elements[i] = string.buffer;
                    free(old);
                }

                char* dst = operands.elements[0];
                char** src = (char**)(operands.elements + (operands.size > 1 ? 1 : 0));

                char dstMask[6] = {0};
                char* dstType = getOperandTypeInfo(dst, dstMask + 1, NULL);
                if (dstType) {
                    dstMask[0] = '.';
                }
                else dstType = "";

                checkSourceSwizzle(&operands);

#define APPEND_SCALAR_OP(op) ArrayBuffer_putString(shaderCode, "%s = %s(%s%s);\n", dst, op, src[0], dstMask)
#define APPEND_ARITH_OP(op) ArrayBuffer_putString(shaderCode, "%s = (%s %c %s)%s;\n", dst, src[0], op, src[1], dstMask)

                switch (inst) {
                    case INST_ABS:
                        APPEND_SCALAR_OP("abs");
                        break;
                    case INST_ADD:
                        APPEND_ARITH_OP('+');
                        break;
                    case INST_ARL:
                        ArrayBuffer_putString(shaderCode, "%s = ivec4(floor(%s))%s;\n", dst, src[0], dstMask);
                        break;
                    case INST_CMP:
                        ArrayBuffer_putString(shaderCode, "%s = vec4(mix(%s, %s, lessThan(%s, vec4(0.0))))%s;\n", dst, src[2], src[1], src[0], dstMask);
                        break;
                    case INST_COS:
                        APPEND_SCALAR_OP("cos");
                        break;
                    case INST_DP3:
                        ArrayBuffer_putString(shaderCode, "%s = %s(dot(vec3(%s), vec3(%s)));\n", dst, dstType, src[0], src[1]);
                        break;
                    case INST_DP4:
                        ArrayBuffer_putString(shaderCode, "%s = %s(dot(%s, %s));\n", dst, dstType, src[0], src[1]);
                        break;
                    case INST_DPH:
                        ArrayBuffer_putString(shaderCode, "%s = %s(dot(vec4(vec3(%s), 1.0), %s));\n", dst, dstType, src[0], src[1]);
                        break;
                    case INST_DST:
                        ArrayBuffer_putString(shaderCode, "%s = vec4(1.0, %s.y * %s.y, %s.z, %s.w);\n", dst, src[0], src[1], src[0], src[1]);
                        break;
                    case INST_EX2:
                        APPEND_SCALAR_OP("exp2");
                        break;
                    case INST_EXP:
                        ArrayBuffer_putString(shaderCode, "%s = vec4(exp2(floor(%s.x)), fract(%s.x), exp2(%s.x), 1.0);\n", dst, src[0], src[1], src[0]);
                        break;
                    case INST_FLR:
                        APPEND_SCALAR_OP("floor");
                        break;
                    case INST_FRC:
                        APPEND_SCALAR_OP("fract");
                        break;
                    case INST_KIL:
                        ArrayBuffer_putString(shaderCode, "if (any(lessThan(%s, vec4(0.0)))) discard;\n", src[0]);
                        break;
                    case INST_LG2:
                        APPEND_SCALAR_OP("log2");
                        break;
                    case INST_LIT:
                        ArrayBuffer_putString(shaderCode, "%s = vec4(1.0, max(%s.x, 0.0), step(0.0, %s.x) * pow(max(%s.y, 0.0), clamp(%s.w, -128.0, 128.0)), 1.0);\n", dst, src[0], src[0], src[0], src[0]);
                        break;
                    case INST_LOG:
                        ArrayBuffer_putString(shaderCode, "%s = vec4(pow(2.0, floor(log2(abs(%s.x)))), fract(log2(abs(%s.x))), log2(abs(%s.x)), 1.0);\n", dst, src[0], src[0], src[0]);
                        break;
                    case INST_LRP:
                        ArrayBuffer_putString(shaderCode, "%s = mix(%s, %s, %s)%s;\n", dst, src[0], src[1], src[2], dstMask);
                        break;
                    case INST_MAD:
                        ArrayBuffer_putString(shaderCode, "%s = (%s * %s + %s)%s;\n", dst, src[0], src[1], src[2], dstMask);
                        break;
                    case INST_MAX:
                        ArrayBuffer_putString(shaderCode, "%s = max(%s, %s)%s;\n", dst, src[0], src[1], dstMask);
                        break;
                    case INST_MIN:
                        ArrayBuffer_putString(shaderCode, "%s = min(%s, %s)%s;\n", dst, src[0], src[1], dstMask);
                        break;
                    case INST_MOV:
                        ArrayBuffer_putString(shaderCode, "%s = %s%s;\n", dst, src[0], dstMask);
                        break;
                    case INST_MUL:
                        APPEND_ARITH_OP('*');
                        break;
                    case INST_POW:
                        ArrayBuffer_putString(shaderCode, "%s = %s(pow(%s, %s));\n", dst, dstType, src[0], src[1]);
                        break;
                    case INST_RCP:
                        ArrayBuffer_putString(shaderCode, "%s = %s(1.0 / %s);\n", dst, dstType, src[0]);
                        break;
                    case INST_RSQ:
                        ArrayBuffer_putString(shaderCode, "%s = %s(inversesqrt(%s));\n", dst, dstType, src[0]);
                        break;
                    case INST_SCS:
                        ArrayBuffer_putString(shaderCode, "%s = vec4(cos(%s.x), sin(%s.x), 0.0, 0.0);\n", dst, src[0], src[0]);
                        break;
                    case INST_SGE:
                        ArrayBuffer_putString(shaderCode, "%s = vec4(greaterThanEqual(%s, %s))%s;\n", dst, src[0], src[0], dstMask);
                        break;
                    case INST_SIN:
                        APPEND_SCALAR_OP("sin");
                        break;
                    case INST_SLT:
                        ArrayBuffer_putString(shaderCode, "%s = vec4(lessThan(%s, %s))%s;\n", dst, src[0], src[0], dstMask);
                        break;
                    case INST_SUB:
                        APPEND_ARITH_OP('-');
                        break;
                    case INST_TEX:
                    case INST_TXP: {
                        int slot = 0;
                        sscanf(src[1], "gd_Texture%d", &slot);
                        asmSource->samplerTypes[slot] = 0;

                        if (strcmp(src[2], "1D") == 0 ||
                            strcmp(src[2], "2D") == 0) {
                            asmSource->samplerTypes[slot] = 2;
                        }
                        else if (strcmp(src[2], "3D") == 0) {
                            asmSource->samplerTypes[slot] = 3;
                        }
                        else if (strcmp(src[2], "CUBE") == 0) {
                            asmSource->samplerTypes[slot] = 4;
                        }

                        if (inst == INST_TXP) {
                            ArrayBuffer_putString(shaderCode, "%s = textureProj(%s, %s, gd_TexEnv%d.lodBias)%s;\n", dst, src[1], src[0], slot, dstMask);
                        }
                        else {
                            char* components = asmSource->samplerTypes[slot] == 2 ? "xy" : "xyz";
                            ArrayBuffer_putString(shaderCode, "%s = texture(%s, %s.%s, gd_TexEnv%d.lodBias)%s;\n", dst, src[1], src[0], components, slot, dstMask);
                        }
                        break;
                    }
                    default:
                        *chr = '\0';
                        println("gladio: unimplemented asm instruction %s %p", word, value);
                        break;
                }

                freeoperands:
                ArrayList_free(&operands, true);
            }

            UNMARK_END_LINE(chr + 1);
            *chr = oldChar;
            wordStart = -1;

            while (*chr && *chr != ';') chr++;
        }

        if (*chr == '\0') break;
        chr++;
    }

    free(asmString);
}

static void convertASMSource(GLenum type, ASMSource* asmSource, ArrayBuffer* shaderCode) {
    ArrayMap instructionMap = {0};
    getInstructionMap(&instructionMap);

    asmSource->type = type;
    ArrayBuffer_putString(shaderCode, "void main() {\n");

    iterateASMCodeLines(&instructionMap, asmSource, shaderCode);

    if (type == GL_FRAGMENT_PROGRAM_ARB) {
        ArrayBuffer_putString(shaderCode, "applyAlphaTest(gd_FragColor.a);\n");
    }
    ArrayBuffer_putString(shaderCode, "}\n");
    ArrayBuffer_put(shaderCode, '\0');

    ArrayBuffer headCode = {0};
    for (int i = 0; i < asmSource->variables.size; i++) {
        ARBVariable* variable = asmSource->variables.entries[i].value;
        if (variable->type == TYPE_QUALIFIER_PARAM) {
            if (variable->arraySize > 0) {
                ArrayBuffer_putString(&headCode, "uniform vec4 %s%u[%d];\n", variable->name, indexOfGLTarget(type), variable->arraySize);
            }
            else ArrayBuffer_putString(&headCode, "uniform vec4 %s%u;\n", variable->name, indexOfGLTarget(type));
        }
    }

    if (asmSource->genericAttribs.size > 0) {
        for (int i = 0; i < asmSource->genericAttribs.size; i++) {
            ArrayBuffer_putString(&headCode, "in vec4 gd_GenericAttrib%d;\n", asmSource->genericAttribs.values[i]);
        }
        IntArray_clear(&asmSource->genericAttribs);
    }

    if (headCode.size > 0) {
        ArrayBuffer_putString(&headCode, shaderCode->buffer);
        ArrayBuffer_put(&headCode, '\0');
        ArrayBuffer_free(shaderCode);
        shaderCode->buffer = headCode.buffer;
        shaderCode->size = shaderCode->capacity = headCode.size;
    }
}

ARBProgram* ARBProgram_create() {
    GLX_CONTEXT_LOCK();
    ARBProgram* program = calloc(1, sizeof(ARBProgram));
    program->id = maxProgramId++;
    program->threadId = currentThreadId();
    SparseArray_put(currentRenderer->clientState.arbPrograms, program->id, program);
    GLX_CONTEXT_UNLOCK();
    return program;
}

ARBProgram* ARBProgram_get(GLuint programId) {
    if (programId == 0) return NULL;
    GLX_CONTEXT_LOCK();
    ARBProgram* program = SparseArray_get(currentRenderer->clientState.arbPrograms, programId);
    GLX_CONTEXT_UNLOCK();
    return program;
}

ARBProgram* ARBProgram_getBound(GLenum target) {
    return currentRenderer->clientState.arbProgram[indexOfGLTarget(target)];
}

void ARBProgram_bind(GLenum target, GLuint programId) {
    GLX_CONTEXT_LOCK();
    ARBProgram* program = SparseArray_get(currentRenderer->clientState.arbPrograms, programId);
    GLX_CONTEXT_UNLOCK();
    if (!program) program = ARBProgram_create();

    if (program) {
        if (target == GL_VERTEX_PROGRAM_ARB) {
            if (program->shaderId == 0) program->shaderId = ShaderConverter_createShader(GL_VERTEX_SHADER);
            program->type = GL_VERTEX_PROGRAM_ARB;
        }
        else if (target == GL_FRAGMENT_PROGRAM_ARB) {
            if (program->shaderId == 0) program->shaderId = ShaderConverter_createShader(GL_FRAGMENT_SHADER);
            program->type = GL_FRAGMENT_PROGRAM_ARB;
        }
    }

    currentRenderer->clientState.arbProgram[indexOfGLTarget(target)] = program;
}

void ARBProgram_setSource(ARBProgram* program, GLenum format, char* string, GLuint length) {
    if (!program || format != GL_PROGRAM_FORMAT_ASCII_ARB) return;

    ASMSource asmSource = {0};
    asmSource.string = malloc(length + 1);
    memcpy(asmSource.string, string, length);
    asmSource.string[length] = '\0';

    ArrayBuffer shaderCode = {0};
    convertASMSource(program->type, &asmSource, &shaderCode);
    program->shaderCode = shaderCode.buffer;
    program->asmSource = asmSource.string;

    memcpy(program->samplerTypes, asmSource.samplerTypes, sizeof(program->samplerTypes));

    uint8_t numTextures = 0;
    for (int i = 0; i < MAX_TEXTURES; i++) if (asmSource.samplerTypes[i] > 0) numTextures++;
    program->numTextures = MAX(numTextures, asmSource.maxTexCoords);

    for (int i = 0; i < asmSource.variables.size; i++) {
        ARBVariable* variable = asmSource.variables.entries[i].value;
        if (variable->type == TYPE_QUALIFIER_PARAM) {
            ArrayList_add(&program->variables, variable);
        }
        else {
            MEMFREE(variable->name);
            MEMFREE(variable->value);
            free(variable);
        }
    }

    ArrayMap_free(&asmSource.variables, false, false);
}

static void destroyARBProgram(ARBProgram* program) {
    MEMFREE(program->shaderCode);
    MEMFREE(program->asmSource);

    for (int i = 0; i < program->variables.size; i++) {
        ARBVariable* variable = program->variables.elements[i];
        ArrayList_free(&variable->uniforms, true);
        MEMFREE(variable->name);
        free(variable);
    }

    free(program);
}

void ARBProgram_delete(GLuint programId) {
    GLX_CONTEXT_LOCK();
    ARBProgram* program = SparseArray_get(currentRenderer->clientState.arbPrograms, programId);
    GLX_CONTEXT_UNLOCK();

    if (program) {
        if (program->shaderId > 0) ShaderConverter_deleteShader(program->shaderId);
        destroyARBProgram(program);

        GLX_CONTEXT_LOCK();
        SparseArray_remove(currentRenderer->clientState.arbPrograms, programId);
        GLX_CONTEXT_UNLOCK();
    }
}

static void setUniformValueAt(ARBProgram* program, char type, int index, GLfloat x, GLfloat y, GLfloat z, GLfloat w) {
    for (int i = 0, j; i < program->variables.size; i++) {
        ARBVariable* variable = program->variables.elements[i];
        for (j = 0; j < variable->uniforms.size; j++) {
            ARBUniform* uniform = variable->uniforms.elements[j];
            if (uniform->type == type && uniform->index == index) {
                uniform->value[0] = x;
                uniform->value[1] = y;
                uniform->value[2] = z;
                uniform->value[3] = w;
            }
        }
    }
}

void ARBProgram_setEnvParameter(GLenum target, GLuint index, GLfloat x, GLfloat y, GLfloat z, GLfloat w) {
    GLX_CONTEXT_LOCK();
    int threadId = currentThreadId();
    SparseArray* programs = currentRenderer->clientState.arbPrograms;
    for (int i = 0; i < programs->size; i++) {
        ARBProgram* program = programs->entries[i].value;
        if (program->threadId == threadId && program->type == target) {
            setUniformValueAt(program, ARB_UNIFORM_TYPE_PROGRAM_ENV, index, x, y, z, w);
        }
    }
    GLX_CONTEXT_UNLOCK();
}

void ARBProgram_setLocalParameter(ARBProgram* program, GLuint index, GLfloat x, GLfloat y, GLfloat z, GLfloat w) {
    if (!program) return;
    setUniformValueAt(program, ARB_UNIFORM_TYPE_PROGRAM_LOCAL, index, x, y, z, w);
}

void ARBProgram_onDestroy(GLClientState* clientState) {
    SparseArray* programs = clientState->arbPrograms;
    for (int i = programs->size-1; i >= 0; i--) {
        ARBProgram* program = programs->entries[i].value;
        destroyARBProgram(program);
        SparseArray_removeAt(programs, i);
    }
}

bool ARBProgram_isActive(GLenum target) {
    uint8_t index = indexOfGLTarget(target);
    return currentRenderer->state.enabledARBPrograms[index] && currentRenderer->clientState.arbProgram[index];
}