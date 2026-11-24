package com.iflytek.astron.console.hub.websocket;

/** Exposes package-private relay helpers to tests in other packages. */
public final class VolcAsrRelayHandlerTestAccess {

    private VolcAsrRelayHandlerTestAccess() {}

    public static boolean isEndMessage(byte[] bytes) {
        return VolcAsrRelayHandler.isEndMessage(bytes);
    }
}
