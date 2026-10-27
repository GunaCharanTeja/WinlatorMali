package com.winlator.cmod.xserver.extensions;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.Log;
import android.util.SparseArray;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Pixmap;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.BadMatch;
import com.winlator.cmod.xserver.errors.BadPixmap;
import com.winlator.cmod.xserver.errors.BadValue;
import com.winlator.cmod.xserver.errors.BadWindow;
import com.winlator.cmod.xserver.errors.GLXBadContext;
import com.winlator.cmod.xserver.errors.GLXBadFBConfig;
import com.winlator.cmod.xserver.errors.GLXBadWindow;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;

/**
 * Standard GLX protocol for Mesa's libGL with direct rendering (Zink), from Pipetto's build. Mesa renders on its own
 * through Vulkan and presents with DRI3/Present; the server only answers the GLX queries and keeps the context, window
 * and pixmap ids. Used instead of the Gladio GLXExtension when the OpenGL renderer is Zink.
 */
public class MesaGLXExtension implements Extension {
    private final XServer xServer;
    private final FBConfig defaultConfig;
    private final FBConfig[] fbConfigs;
    private final SparseArray<GLXContext> glxContexts = new SparseArray<>();
    private final SparseArray<GLXPixmap> glxPixmaps = new SparseArray<>();
    private final SparseArray<GLXWindow> glxWindows = new SparseArray<>();

    private static abstract class ClientOpcodes {
        private static final byte CREATE_CONTEXT = 3;
        private static final byte DESTROY_CONTEXT = 4;
        private static final byte IS_DIRECT = 6;
        private static final byte QUERY_VERSION = 7;
        private static final byte GET_VISUAL_CONFIGS = 14;
        private static final byte QUERY_SERVER_STRING = 19;
        private static final byte CLIENT_INFO = 20;
        private static final byte GET_FB_CONFIGS = 21;
        private static final byte CREATE_PIXMAP = 22;
        private static final byte DESTROY_PIXMAP = 23;
        private static final byte CREATE_NEW_CONTEXT = 24;
        private static final byte GET_DRAWABLE_ATTRIBUTES = 29;
        private static final byte CREATE_WINDOW = 31;
        private static final byte DESTROY_WINDOW = 32;
        private static final byte CLIENT_INFO_ARB = 33;
        private static final byte CREATE_CONTEXT_ATTRIBS_ARB = 34;
        private static final byte CLIENT_INFO_2_ARB = 35;
    }

    private static final int GLX_X_VISUAL_ID = 0x800B;
    private static final int GLX_FBCONFIG_ID = 0x8013;
    private static final int GLX_X_RENDERABLE = 0x8012;
    private static final int GLX_WIDTH = 0x801D;
    private static final int GLX_HEIGHT = 0x801E;
    private static final int GLX_RGBA = 4;
    private static final int GLX_RENDER_TYPE = 0x8011;
    private static final int GLX_DOUBLEBUFFER = 5;
    private static final int GLX_STEREO = 6;
    private static final int GLX_BUFFER_SIZE = 2;
    private static final int GLX_LEVEL = 3;
    private static final int GLX_AUX_BUFFERS = 7;
    private static final int GLX_RED_SIZE = 8;
    private static final int GLX_GREEN_SIZE = 9;
    private static final int GLX_BLUE_SIZE = 10;
    private static final int GLX_ALPHA_SIZE = 11;
    private static final int GLX_DEPTH_SIZE = 12;
    private static final int GLX_STENCIL_SIZE = 13;
    private static final int GLX_ACCUM_RED_SIZE = 14;
    private static final int GLX_ACCUM_GREEN_SIZE = 15;
    private static final int GLX_ACCUM_BLUE_SIZE = 16;
    private static final int GLX_ACCUM_ALPHA_SIZE = 17;
    private static final int GLX_X_VISUAL_TYPE = 0x22;
    private static final int GLX_TRANSPARENT_TYPE = 0x23;
    private static final int GLX_TRANSPARENT_INDEX_VALUE = 0x24;
    private static final int GLX_TRANSPARENT_RED_VALUE = 0x25;
    private static final int GLX_TRANSPARENT_GREEN_VALUE = 0x26;
    private static final int GLX_TRANSPARENT_BLUE_VALUE = 0x27;
    private static final int GLX_TRANSPARENT_ALPHA_VALUE = 0x28;
    private static final int GLX_DRAWABLE_TYPE = 0x8010;
    private static final int GLX_NONE = 0x8000;
    private static final int GLX_TRUE_COLOR = 0x8002;
    private static final int GLX_WINDOW_BIT = 0x1;
    private static final int GLX_PIXMAP_BIT = 0x2;
    private static final int GLX_RGBA_BIT = 0x1;
    private static final int GLX_RGBA_TYPE = 0x8014;
    private static final int GLX_CONTEXT_MAJOR_VERSION_ARB = 0x2091;
    private static final int GLX_CONTEXT_MINOR_VERSION_ARB = 0x2092;

    private static class FBConfig {
        final int id, depth, stencil, bufferSize;
        final boolean doubleBuffered;

        FBConfig(int id, int depth, int stencil, boolean doubleBuffered, int bufferSize) {
            this.id = id;
            this.depth = depth;
            this.stencil = stencil;
            this.doubleBuffered = doubleBuffered;
            this.bufferSize = bufferSize;
        }
    }

    private static class GLXContext {
        final boolean isDirect;
        GLXContext(boolean isDirect) { this.isDirect = isDirect; }
    }

    private static class GLXPixmap {
        final Pixmap pixmap;
        final FBConfig fbconfig;
        GLXPixmap(Pixmap pixmap, FBConfig fbconfig) { this.pixmap = pixmap; this.fbconfig = fbconfig; }
    }

    private static class GLXWindow {
        final Window window;
        final FBConfig fbconfig;
        GLXWindow(Window window, FBConfig fbconfig) { this.window = window; this.fbconfig = fbconfig; }
    }

    public MesaGLXExtension(XServer xServer) {
        this.xServer = xServer;
        defaultConfig = new FBConfig(1, 0, 0, true, 32);
        fbConfigs = new FBConfig[]{
            defaultConfig,
            new FBConfig(2, 0, 0, false, 32),
            new FBConfig(3, 24, 0, false, 32),
            new FBConfig(4, 24, 8, false, 32),
            new FBConfig(5, 24, 0, true, 32),
            new FBConfig(6, 24, 8, true, 32)
        };
    }

    private FBConfig findFBConfig(int id) {
        for (FBConfig config : fbConfigs) if (config.id == id) return config;
        return null;
    }

    private static void writePair(XOutputStream outputStream, int name, int value) {
        outputStream.writeInt(name);
        outputStream.writeInt(value);
    }

    private void writeFBConfig(XOutputStream outputStream, FBConfig fbConfig) {
        writePair(outputStream, GLX_X_VISUAL_ID, xServer.drawableManager.getVisual().id);
        writePair(outputStream, GLX_FBCONFIG_ID, fbConfig.id);
        writePair(outputStream, GLX_X_RENDERABLE, 1);
        writePair(outputStream, GLX_RGBA, 1);
        writePair(outputStream, GLX_RENDER_TYPE, GLX_RGBA_BIT);
        writePair(outputStream, GLX_DOUBLEBUFFER, fbConfig.doubleBuffered ? 1 : 0);
        writePair(outputStream, GLX_STEREO, 0);
        writePair(outputStream, GLX_BUFFER_SIZE, fbConfig.bufferSize);
        writePair(outputStream, GLX_LEVEL, 0);
        writePair(outputStream, GLX_AUX_BUFFERS, 0);
        writePair(outputStream, GLX_RED_SIZE, 8);
        writePair(outputStream, GLX_BLUE_SIZE, 8);
        writePair(outputStream, GLX_GREEN_SIZE, 8);
        writePair(outputStream, GLX_ALPHA_SIZE, 8);
        writePair(outputStream, GLX_ACCUM_RED_SIZE, 0);
        writePair(outputStream, GLX_ACCUM_BLUE_SIZE, 0);
        writePair(outputStream, GLX_ACCUM_GREEN_SIZE, 0);
        writePair(outputStream, GLX_ACCUM_ALPHA_SIZE, 0);
        writePair(outputStream, GLX_DEPTH_SIZE, fbConfig.depth);
        writePair(outputStream, GLX_STENCIL_SIZE, fbConfig.stencil);
        writePair(outputStream, GLX_X_VISUAL_TYPE, GLX_TRUE_COLOR);
        writePair(outputStream, GLX_TRANSPARENT_TYPE, GLX_NONE);
        writePair(outputStream, GLX_TRANSPARENT_INDEX_VALUE, 0);
        writePair(outputStream, GLX_TRANSPARENT_RED_VALUE, 0);
        writePair(outputStream, GLX_TRANSPARENT_BLUE_VALUE, 0);
        writePair(outputStream, GLX_TRANSPARENT_GREEN_VALUE, 0);
        writePair(outputStream, GLX_TRANSPARENT_ALPHA_VALUE, 0);
        writePair(outputStream, GLX_DRAWABLE_TYPE, GLX_WINDOW_BIT | GLX_PIXMAP_BIT);
        for (int i = 0; i < 16; i++) writePair(outputStream, 0, 0);
    }

    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(8);
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(1);
            outputStream.writeInt(4);
            outputStream.writePad(16);
        }
    }

    private void getVisualConfigs(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(4);
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(18 * fbConfigs.length);
            outputStream.writeInt(fbConfigs.length);
            outputStream.writeInt(18);
            outputStream.writePad(16);

            for (FBConfig fbconfig : fbConfigs) {
                outputStream.writeInt(xServer.drawableManager.getVisual().id);
                outputStream.writeInt(xServer.drawableManager.getVisual().visualClass);
                outputStream.writeInt(1);
                outputStream.writeInt(8);
                outputStream.writeInt(8);
                outputStream.writeInt(8);
                outputStream.writeInt(8);
                outputStream.writeInt(0);
                outputStream.writeInt(0);
                outputStream.writeInt(0);
                outputStream.writeInt(0);
                outputStream.writeInt(fbconfig.doubleBuffered ? 1 : 0);
                outputStream.writeInt(0);
                outputStream.writeInt(fbconfig.bufferSize);
                outputStream.writeInt(fbconfig.depth);
                outputStream.writeInt(fbconfig.stencil);
                outputStream.writeInt(0);
                outputStream.writeInt(0);
            }
        }
    }

    private void queryServerString(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(4);
        int name = inputStream.readInt();
        String string = name == 1 ? "Winlator " : name == 2 ? "1.4 " : name == 3 ? "GLX_ARB_create_context GLX_ARB_create_context_profile " : "";
        int length = string.length();

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt((length + 3) / 4);
            outputStream.writePad(4);
            outputStream.writeInt(length);
            outputStream.writePad(16);
            outputStream.writeString8(string);
        }
    }

    private void getFBConfigs(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(4);
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(88 * fbConfigs.length);
            outputStream.writeInt(fbConfigs.length);
            outputStream.writeInt(44);
            outputStream.writePad(16);
            for (FBConfig config : fbConfigs) writeFBConfig(outputStream, config);
        }
    }

    // ClientInfo requests carry the client's GL/GLX extension strings, which the server does not need.
    private void skipClientInfo(XClient client, XInputStream inputStream) {
        inputStream.skip(client.getRemainingRequestLength());
    }

    private void createContextAttribsARB(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int contextId = inputStream.readInt();
        int fbconfigId = inputStream.readInt();
        inputStream.skip(4);
        int shareList = inputStream.readInt();
        boolean isDirect = inputStream.readByte() != 0;
        inputStream.skip(3);
        int numAttribs = inputStream.readInt();

        int majorGL = 1;
        for (int i = 0; i < numAttribs; i++) {
            int attr = inputStream.readInt();
            int value = inputStream.readInt();
            if (attr == GLX_CONTEXT_MAJOR_VERSION_ARB) majorGL = value;
        }

        if (findFBConfig(fbconfigId) == null) throw new GLXBadFBConfig();
        if (shareList != 0 && glxContexts.get(shareList) == null) throw new GLXBadContext();
        if (majorGL < 1 || majorGL > 4) throw new BadMatch();
        glxContexts.put(contextId, new GLXContext(isDirect));
    }

    private void createNewContext(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int contextId = inputStream.readInt();
        int fbconfigId = inputStream.readInt();
        inputStream.skip(4);
        int renderType = inputStream.readInt();
        int shareList = inputStream.readInt();
        boolean isDirect = inputStream.readByte() != 0;
        inputStream.skip(3);

        if (renderType != GLX_RGBA_TYPE) throw new BadValue(renderType);
        if (findFBConfig(fbconfigId) == null) throw new GLXBadFBConfig();
        if (shareList != 0 && glxContexts.get(shareList) == null) throw new GLXBadContext();
        glxContexts.put(contextId, new GLXContext(isDirect));
    }

    private void createContext(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int contextId = inputStream.readInt();
        int visualId = inputStream.readInt();
        inputStream.skip(4);
        int shareList = inputStream.readInt();
        boolean isDirect = inputStream.readByte() != 0;
        inputStream.skip(3);

        if (visualId != xServer.drawableManager.getVisual().id) throw new BadValue(visualId);
        if (shareList != 0 && glxContexts.get(shareList) == null) throw new GLXBadContext();
        glxContexts.put(contextId, new GLXContext(isDirect));
    }

    private void isDirect(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int contextId = inputStream.readInt();
        GLXContext context = glxContexts.get(contextId);
        if (context == null) throw new GLXBadContext();

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeByte(context.isDirect ? (byte)1 : (byte)0);
            outputStream.writePad(23);
        }
    }

    private void getDrawableAttributes(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int drawableId = inputStream.readInt();

        GLXWindow glxWindow = glxWindows.get(drawableId);
        Window window = glxWindow != null ? glxWindow.window : xServer.windowManager.getWindow(drawableId);
        GLXPixmap glxPixmap = glxPixmaps.get(drawableId);
        if (window == null && glxPixmap == null) throw new BadMatch();

        FBConfig fbconfig = glxWindow != null ? glxWindow.fbconfig : (glxPixmap != null ? glxPixmap.fbconfig : defaultConfig);
        int drawableType = glxPixmap != null ? GLX_PIXMAP_BIT : GLX_WINDOW_BIT;
        int width = window != null ? window.getWidth() : glxPixmap.pixmap.drawable.width;
        int height = window != null ? window.getHeight() : glxPixmap.pixmap.drawable.height;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(10);
            outputStream.writeInt(5);
            outputStream.writePad(20);
            writePair(outputStream, GLX_FBCONFIG_ID, fbconfig.id);
            writePair(outputStream, GLX_DRAWABLE_TYPE, drawableType);
            writePair(outputStream, GLX_RENDER_TYPE, 1);
            writePair(outputStream, GLX_WIDTH, width);
            writePair(outputStream, GLX_HEIGHT, height);
        }
    }

    private void createPixmap(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(4);
        int fbConfigId = inputStream.readInt();
        int pixmapId = inputStream.readInt();
        int glxPixmapId = inputStream.readInt();
        int numAttrs = inputStream.readInt();
        inputStream.skip(numAttrs * 8);

        Pixmap pixmap = xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap == null) throw new BadPixmap(pixmapId);
        FBConfig fbconfig = findFBConfig(fbConfigId);
        if (fbconfig == null) throw new GLXBadFBConfig();
        glxPixmaps.put(glxPixmapId, new GLXPixmap(pixmap, fbconfig));
    }

    private void createWindow(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(4);
        int fbConfigId = inputStream.readInt();
        int windowId = inputStream.readInt();
        int glxWindowId = inputStream.readInt();
        int numAttrs = inputStream.readInt();
        inputStream.skip(numAttrs * 8);

        Window window = xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);
        FBConfig fbConfig = findFBConfig(fbConfigId);
        if (fbConfig == null) throw new GLXBadFBConfig();
        glxWindows.put(glxWindowId, new GLXWindow(window, fbConfig));
    }

    private void destroyWindow(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int glxWindowId = inputStream.readInt();
        if (glxWindows.get(glxWindowId) == null) throw new GLXBadWindow();
        glxWindows.delete(glxWindowId);
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION: queryVersion(client, inputStream, outputStream); break;
            case ClientOpcodes.QUERY_SERVER_STRING: queryServerString(client, inputStream, outputStream); break;
            case ClientOpcodes.GET_VISUAL_CONFIGS: getVisualConfigs(client, inputStream, outputStream); break;
            case ClientOpcodes.GET_FB_CONFIGS: getFBConfigs(client, inputStream, outputStream); break;
            case ClientOpcodes.CLIENT_INFO:
            case ClientOpcodes.CLIENT_INFO_ARB:
            case ClientOpcodes.CLIENT_INFO_2_ARB: skipClientInfo(client, inputStream); break;
            case ClientOpcodes.CREATE_NEW_CONTEXT: createNewContext(client, inputStream, outputStream); break;
            case ClientOpcodes.CREATE_CONTEXT: createContext(client, inputStream, outputStream); break;
            case ClientOpcodes.CREATE_CONTEXT_ATTRIBS_ARB: createContextAttribsARB(client, inputStream, outputStream); break;
            case ClientOpcodes.IS_DIRECT: isDirect(client, inputStream, outputStream); break;
            case ClientOpcodes.GET_DRAWABLE_ATTRIBUTES: getDrawableAttributes(client, inputStream, outputStream); break;
            case ClientOpcodes.DESTROY_CONTEXT:
                int contextId = inputStream.readInt();
                if (glxContexts.get(contextId) == null) throw new GLXBadContext();
                glxContexts.delete(contextId);
                break;
            case ClientOpcodes.CREATE_WINDOW: createWindow(client, inputStream, outputStream); break;
            case ClientOpcodes.DESTROY_WINDOW: destroyWindow(client, inputStream, outputStream); break;
            case ClientOpcodes.CREATE_PIXMAP: createPixmap(client, inputStream, outputStream); break;
            case ClientOpcodes.DESTROY_PIXMAP: glxPixmaps.delete(inputStream.readInt()); break;
            default:
                Log.d("MesaGLXExtension", "Unimplemented opcode " + opcode);
                throw new BadImplementation();
        }
    }

    @Override
    public String getName() {
        return "GLX";
    }

    @Override
    public byte getMajorOpcode() {
        return GLXExtension.MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return Byte.MIN_VALUE;
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }
}
