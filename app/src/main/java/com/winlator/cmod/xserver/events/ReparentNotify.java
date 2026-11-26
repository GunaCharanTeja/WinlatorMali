package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Window;

import java.io.IOException;

/** Ported from Winlator Ludashi (MIT). */
public class ReparentNotify extends Event {
    private final Window event;
    private final Window window;
    private final Window parent;
    private final short x;
    private final short y;

    public ReparentNotify(Window event, Window window, Window parent, short x, short y) {
        super(21);
        this.event = event;
        this.window = window;
        this.parent = parent;
        this.x = x;
        this.y = y;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(code);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(event.id);
            outputStream.writeInt(window.id);
            outputStream.writeInt(parent.id);
            outputStream.writeShort(x);
            outputStream.writeShort(y);
            outputStream.writeByte((byte)(window.attributes.isOverrideRedirect() ? 1 : 0));
            outputStream.writePad(11);
        }
    }
}
