package com.winlator.cmod.xserver.extensions;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.ScreenInfo;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;

/**
 * Minimal RandR 1.1: one screen size (the container resolution) plus the panel refresh rates.
 * Lets Wine detect RandR so its emulated mode changes (EmulateModeset) work for in-game resolution switches.
 * Ported from Winlator Ludashi (MIT).
 */
public class RandrExtension implements Extension {
    public static final byte MAJOR_OPCODE = -105;

    private static final byte FIRST_EVENT_ID = 65;
    private static final short RR_ROTATE_0 = 1;
    private static final byte SET_OF_ROTATIONS = (byte)RR_ROTATE_0;

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte SET_SCREEN_CONFIG = 2;
        private static final byte SELECT_INPUT = 4;
        private static final byte GET_SCREEN_INFO = 5;
    }

    private final ScreenInfo screenInfo;
    private volatile short[] refreshRates = {60};
    private volatile short currentRate = 60;

    public RandrExtension(ScreenInfo screenInfo) {
        this.screenInfo = screenInfo;
    }

    public void setRefreshRates(short[] rates, short activeRate) {
        if (rates == null || rates.length == 0) return;

        short[] copy = rates.clone();
        short current = copy[0];
        for (short rate : copy) {
            if (rate == activeRate) current = activeRate;
        }
        refreshRates = copy;
        currentRate = current;
    }

    @Override
    public String getName() {
        return "RANDR";
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
        return FIRST_EVENT_ID;
    }

    private static void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(8);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(1);
            outputStream.writeInt(1);
            outputStream.writePad(16);
        }
    }

    private void getScreenInfo(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int window = inputStream.readInt();
        short[] rates = refreshRates;
        short rateEntries = (short)(1 + rates.length);

        int bodyBytes = 8 + rateEntries * 2;
        int padBytes = (4 - bodyBytes % 4) % 4;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(SET_OF_ROTATIONS);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt((bodyBytes + padBytes) / 4);
            outputStream.writeInt(window);
            outputStream.writeInt(0);
            outputStream.writeInt(0);
            outputStream.writeShort((short)1);
            outputStream.writeShort((short)0);
            outputStream.writeShort(RR_ROTATE_0);
            outputStream.writeShort(currentRate);
            outputStream.writeShort(rateEntries);
            outputStream.writeShort((short)0);

            outputStream.writeShort(screenInfo.width);
            outputStream.writeShort(screenInfo.height);
            outputStream.writeShort(screenInfo.getWidthInMillimeters());
            outputStream.writeShort(screenInfo.getHeightInMillimeters());

            outputStream.writeShort((short)rates.length);
            for (short rate : rates) outputStream.writeShort(rate);
            if (padBytes > 0) outputStream.writePad(padBytes);
        }
    }

    private void setScreenConfig(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int window = inputStream.readInt();
        inputStream.skip(8);
        inputStream.readShort();
        inputStream.readShort();
        short requestedRate = inputStream.readShort();
        inputStream.skip(2);

        for (short rate : refreshRates) {
            if (rate == requestedRate) {
                currentRate = requestedRate;
                break;
            }
        }

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(0);
            outputStream.writeInt(0);
            outputStream.writeInt(window);
            outputStream.writeShort((short)0);
            outputStream.writeShort((short)0);
            outputStream.writePad(8);
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        switch (client.getRequestData()) {
            case ClientOpcodes.QUERY_VERSION:
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.GET_SCREEN_INFO:
                getScreenInfo(client, inputStream, outputStream);
                break;
            case ClientOpcodes.SET_SCREEN_CONFIG:
                setScreenConfig(client, inputStream, outputStream);
                break;
            case ClientOpcodes.SELECT_INPUT:
                inputStream.skip(8);
                break;
            default:
                throw new BadImplementation();
        }
    }
}
