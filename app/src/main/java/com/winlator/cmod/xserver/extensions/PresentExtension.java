package com.winlator.cmod.xserver.extensions;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.SparseArray;

import com.winlator.cmod.renderer.GPUImage;
import com.winlator.cmod.renderer.Texture;
import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pixmap;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XResource;
import com.winlator.cmod.xserver.XResourceManager;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.BadMatch;
import com.winlator.cmod.xserver.errors.BadPixmap;
import com.winlator.cmod.xserver.errors.BadWindow;
import com.winlator.cmod.xserver.errors.XRequestError;
import com.winlator.cmod.xserver.events.PresentCompleteNotify;
import com.winlator.cmod.xserver.events.PresentIdleNotify;

import java.io.IOException;
import java.util.concurrent.locks.LockSupport;

public class PresentExtension implements Extension, XResourceManager.OnResourceLifecycleListener, WindowManager.OnWindowModificationListener {
    public static final byte MAJOR_OPCODE = -103;
    private static final int FAKE_INTERVAL = 1000000 / 60;
    public enum Kind {PIXMAP, MSC_NOTIFY}
    public enum Mode {COPY, FLIP, SKIP}
    private final SparseArray<Event> events = new SparseArray<>();
    private SyncExtension syncExtension;

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte PRESENT_PIXMAP = 1;
        private static final byte SELECT_INPUT = 3;
    }

    private static class Event {
        private Window window;
        private XClient client;
        private int id;
        private Bitmask mask;
    }

    @Override
    public String getName() {
        return "Present";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return 0;
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }

    private void sendIdleNotify(Window window, Pixmap pixmap, int serial, int idleFence) {
        if (idleFence != 0) syncExtension.setTriggered(idleFence);

        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentIdleNotify.getEventMask())) {
                    event.client.sendEvent(new PresentIdleNotify(event.id, window, pixmap, serial, idleFence));
                    flushClientOutput(event.client);
                }
            }
        }
    }

    private void sendCompleteNotify(Window window, int serial, Kind kind, Mode mode, long ust, long msc) {
        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentCompleteNotify.getEventMask())) {
                    event.client.sendEvent(new PresentCompleteNotify(event.id, window, serial, kind, mode, ust, msc));
                    flushClientOutput(event.client);
                }
            }
        }
    }

    private void flushClientOutput(XClient client) {
        if (client == null || client.getOutputStream() == null) return;
        try {
            try (XStreamLock ignored = client.getOutputStream().lock()) {}
        } catch (Exception ignored) {}
    }

    private static void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(8);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(1);
            outputStream.writeInt(0);
            outputStream.writePad(16);
        }
    }

    private void presentPixmap(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int pixmapId = inputStream.readInt();
        int serial = inputStream.readInt();
        inputStream.skip(8);
        short xOff = inputStream.readShort();
        short yOff = inputStream.readShort();
        inputStream.skip(8);
        int idleFence = inputStream.readInt();
        inputStream.skip(client.getRemainingRequestLength());

        final Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        final Pixmap pixmap = client.xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap == null) throw new BadPixmap(pixmapId);

        int targetFps = client.xServer != null ? client.xServer.getFpsLimit() : 0;
        long ust = System.nanoTime() / 1000;
        long mscIntervalUs = targetFps > 0 ? (1_000_000L / targetFps) : (1_000_000L / 60);
        long msc = ust / mscIntervalUs;

        if (client.xServer.getDisplayXView() != null) {
            pixmap.drawable.updateDirect();
        } else {
            Drawable content = window.getContent();
            if (content.visual.depth != pixmap.drawable.visual.depth) throw new BadMatch();
            synchronized (content.renderLock) {
                content.copyArea((short)0, (short)0, xOff, yOff, pixmap.drawable.width, pixmap.drawable.height, pixmap.drawable);
            }
        }
        sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.COPY, ust, msc);
        scheduleIdleNotify(window, pixmap, serial, idleFence, targetFps);

        if (client.xServer != null && client.xServer.getWinlatorHUD() != null) {
            client.xServer.getWinlatorHUD().onFrame();
        }
    }

    private void selectInput(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int eventId = inputStream.readInt();
        int windowId = inputStream.readInt();
        Bitmask mask = new Bitmask(inputStream.readInt());

        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        if (GPUImage.isSupported() && !mask.isEmpty()) {
            Drawable content = window.getContent();
            final Texture oldTexture = content.getTexture();
            if (client.xServer != null && client.xServer.getRenderer() != null && client.xServer.getRenderer().xServerView != null) {
                client.xServer.getRenderer().xServerView.queueEvent(oldTexture::destroy);
            }
            content.setTexture(new GPUImage(content.width, content.height));
        }

        synchronized (events) {
            Event event = events.get(eventId);
            if (event != null) {
                if (event.window != window || event.client != client) throw new BadMatch();

                if (!mask.isEmpty()) {
                    event.mask = mask;
                }
                else events.remove(eventId);
            }
            else {
                event = new Event();
                event.id = eventId;
                event.window = window;
                event.client = client;
                event.mask = mask;
                events.put(eventId, event);
            }
        }
    }

    private static final long FIRE_EARLY_NS = 700_000L; // 0.7 ms

    private static class WindowTiming {
        long nextIdleNs;
    }

    private static class PendingIdle {
        final Window window;
        final Pixmap pixmap;
        final int serial;
        final int idleFence;
        final long fireNs;

        PendingIdle(Window window, Pixmap pixmap, int serial, int idleFence, long fireNs) {
            this.window = window;
            this.pixmap = pixmap;
            this.serial = serial;
            this.idleFence = idleFence;
            this.fireNs = fireNs;
        }
    }

    private final java.util.concurrent.ConcurrentHashMap<Integer, WindowTiming> windowTimings =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Use a PriorityBlockingQueue instead of a ConcurrentHashMap keyed by window.id.
    // The old map overwrote pending idles when a game submitted multiple frames before
    // the previous one was released (triple buffering), permanently leaking swapchain
    // images and stalling DXVK. The queue guarantees every frame's idle fence fires.
    private final java.util.concurrent.PriorityBlockingQueue<PendingIdle> idleQueue =
            new java.util.concurrent.PriorityBlockingQueue<>(16,
                    (a, b) -> Long.compare(a.fireNs, b.fireNs));

    private volatile Thread pacerThread;

    private void startPacer() {
        if (pacerThread != null) return;
        synchronized (this) {
            if (pacerThread != null) return;
            Thread t = new Thread(this::runPacer, "PresentPacer");
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY);
            pacerThread = t;
            t.start();
        }
    }

    private void runPacer() {
        while (!Thread.interrupted()) {
            try {
                PendingIdle p = idleQueue.take();
                long remaining = p.fireNs - FIRE_EARLY_NS - System.nanoTime();
                while (remaining > 0) {
                    if (remaining > 100_000L) {
                        LockSupport.parkNanos(remaining - 50_000L);
                    }
                    if (Thread.interrupted()) return;
                    // Precision spin-finish for the final ~50µs
                    remaining = p.fireNs - FIRE_EARLY_NS - System.nanoTime();
                }
                sendIdleNotify(p.window, p.pixmap, p.serial, p.idleFence);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void scheduleIdleNotify(Window window, Pixmap pixmap, int serial, int idleFence, int targetFps) {
        if (targetFps <= 0) {
            sendIdleNotify(window, pixmap, serial, idleFence);
            return;
        }

        long frameNs = 1_000_000_000L / targetFps;
        long now = System.nanoTime();
        WindowTiming wt = windowTimings.computeIfAbsent(window.id, k -> new WindowTiming());
        synchronized (wt) {
            if (wt.nextIdleNs <= now - frameNs || now < wt.nextIdleNs - frameNs * 2) {
                wt.nextIdleNs = now + frameNs;
            } else {
                wt.nextIdleNs += frameNs;
            }
            idleQueue.offer(new PendingIdle(window, pixmap, serial, idleFence, wt.nextIdleNs - FIRE_EARLY_NS));
        }
        startPacer();
    }

    private boolean lifecycleListenersRegistered = false;
    private XServer xServer;

    private void registerLifecycleListeners(XServer xServer) {
        if (lifecycleListenersRegistered || xServer == null) return;
        synchronized (this) {
            if (lifecycleListenersRegistered) return;
            this.xServer = xServer;
            xServer.pixmapManager.addOnResourceLifecycleListener(this);
            xServer.windowManager.addOnWindowModificationListener(this);
            lifecycleListenersRegistered = true;
        }
    }

    private void drainAndFire(Window window, Pixmap pixmap) {
        if (window != null) windowTimings.remove(window.id);
        java.util.ArrayList<PendingIdle> hit = new java.util.ArrayList<>();
        for (PendingIdle p : idleQueue) {
            if ((window != null && p.window == window) || (pixmap != null && p.pixmap == pixmap)) hit.add(p);
        }
        for (PendingIdle p : hit) {
            if (idleQueue.remove(p)) sendIdleNotify(p.window, p.pixmap, p.serial, p.idleFence);
        }
    }

    @Override
    public void onFreeResource(XResource resource) {
        if (resource instanceof Pixmap) {
            drainAndFire(null, (Pixmap) resource);
        }
    }

    @Override
    public void onDestroyWindow(Window window) {
        drainAndFire(window, null);
        synchronized (events) {
            for (int i = events.size() - 1; i >= 0; i--) {
                if (events.valueAt(i).window == window) events.removeAt(i);
            }
        }
    }

    public void drainAndFireAll() {
        java.util.ArrayList<PendingIdle> remaining = new java.util.ArrayList<>();
        idleQueue.drainTo(remaining);
        for (PendingIdle p : remaining) {
            sendIdleNotify(p.window, p.pixmap, p.serial, p.idleFence);
        }
        windowTimings.clear();
    }

    public void onFpsLimitChanged(int newLimit) {
        if (newLimit <= 0) {
            drainAndFireAll();
        }
    }

    public void close() {
        drainAndFireAll();
        Thread t = pacerThread;
        if (t != null) {
            t.interrupt();
            pacerThread = null;
        }
        if (lifecycleListenersRegistered && xServer != null) {
            xServer.pixmapManager.removeOnResourceLifecycleListener(this);
            xServer.windowManager.removeOnWindowModificationListener(this);
            lifecycleListenersRegistered = false;
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        registerLifecycleListeners(client.xServer);
        int opcode = client.getRequestData();
        if (syncExtension == null) syncExtension = client.xServer.getExtension(SyncExtension.MAJOR_OPCODE);

        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.PRESENT_PIXMAP:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER)) {
                    presentPixmap(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.SELECT_INPUT:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    selectInput(client, inputStream, outputStream);
                }
                break;
            default:
                throw new BadImplementation();
        }
    }
}
