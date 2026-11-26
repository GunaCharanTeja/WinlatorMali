package com.winlator.cmod.renderer;

import androidx.annotation.Keep;
import java.nio.ByteBuffer;

/** CPU-mappable AHardwareBuffer shared with the native EGL renderer (DRI3 client buffers). */
public class GPUImage {
    public long hardwareBufferPtr;
    public int format = 5;
    private ByteBuffer virtualData;
    public short stride;

    static {
        System.loadLibrary("winlator");
    }

    public GPUImage(short width, short height) {
        hardwareBufferPtr = createHardwareBuffer(width, height);
        if (hardwareBufferPtr != 0) {
            virtualData = lockHardwareBuffer(hardwareBufferPtr);
            if (virtualData == null) {
                System.err.println("Error: Failed to lock hardware buffer");
                destroyHardwareBuffer(hardwareBufferPtr);
                hardwareBufferPtr = 0;
            }
        } else {
            System.err.println("Error: Failed to create hardware buffer");
        }
    }

    public GPUImage(int socketFd) {
        hardwareBufferPtr = hardwareBufferFromSocket(socketFd);
        if (hardwareBufferPtr != 0) {
            virtualData = lockHardwareBuffer(hardwareBufferPtr);
            if (virtualData == null) {
                System.err.println("Error: Failed to lock hardware buffer");
                destroyHardwareBuffer(hardwareBufferPtr);
                hardwareBufferPtr = 0;
            }
        } else {
            System.err.println("Error: Failed to create hardware buffer");
        }
    }

    public short getStride() {
        return stride;
    }

    @Keep
    private void setStride(short stride) {
        this.stride = stride;
    }

    public ByteBuffer getVirtualData() {
        return virtualData;
    }

    /** Releases this reference; the native renderer keeps its own EGLImage reference until it drops the texture. */
    public void destroy() {
        if (hardwareBufferPtr != 0) {
            destroyHardwareBuffer(hardwareBufferPtr);
            hardwareBufferPtr = 0;
        }
        virtualData = null;
    }

    private native long hardwareBufferFromSocket(int fd);

    private native long createHardwareBuffer(short width, short height);

    private native void destroyHardwareBuffer(long hardwareBufferPtr);

    private native ByteBuffer lockHardwareBuffer(long hardwareBufferPtr);
}
