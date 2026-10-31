package com.cordiact.app.sandbox;

/** Fixed-payload S2 proof protocol. Not a general plugin execution API. */
interface ISandboxProbe {
    int getProcessUid();
    boolean isProcessIsolated();
    boolean openSession(String sessionId, String nonce, String pluginId, String pluginVersion, String sha256);
    String executeProbe(String sessionId, String nonce, String requestId, String operation, String payload, long deadlineMillis);
    void cancelProbe(String sessionId, String nonce, String requestId);
    void closeSession(String sessionId, String nonce);
}
