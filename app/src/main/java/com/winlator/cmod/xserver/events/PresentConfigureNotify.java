package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.extensions.PresentExtension;

import java.io.IOException;

/** Tells Present clients (Mesa's Vulkan WSI) that a window changed, so swapchains get recreated. Ported from Winlator Ludashi (MIT). */
public class PresentConfigureNotify extends Event {
    public static final int PIXMAP_FLAG_WINDOW_DESTROYED = 1;

    private final int eventId;
    private final Window window;
    private final short x;
    private final short y;
    private final short width;
    private final short height;
    private final int pixmapFlags;

    public PresentConfigureNotify(int eventId, Window window, int pixmapFlags) {
        super(35);
        this.eventId = eventId;
        this.window = window;
        this.x = window.getX();
        this.y = window.getY();
        this.width = window.getWidth();
        this.height = window.getHeight();
        this.pixmapFlags = pixmapFlags;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(code);
            outputStream.writeByte(PresentExtension.MAJOR_OPCODE);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(2);
            outputStream.writeShort(getEventType());
            outputStream.writeShort((short)0);
            outputStream.writeInt(eventId);
            outputStream.writeInt(window.id);
            outputStream.writeShort(x);
            outputStream.writeShort(y);
            outputStream.writeShort(width);
            outputStream.writeShort(height);
            outputStream.writeShort((short)0);
            outputStream.writeShort((short)0);
            outputStream.writeShort(width);
            outputStream.writeShort(height);
            outputStream.writeInt(pixmapFlags);
        }
    }

    public static short getEventType() {
        return 0;
    }

    public static int getEventMask() {
        return 1 << getEventType();
    }
}
