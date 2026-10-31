package com.cordiact.app.sandbox;

/** Test-only fixed-script surface; no source code or host capabilities cross Binder. */
interface ISandboxJsPoc {
    int getWorkerUid();
    boolean isIsolated();
    String runSample(long timeoutMillis);
    String runMemoryLimitProbe(long timeoutMillis);
    String runStackLimitProbe(long timeoutMillis);
    String runTimeoutProbe(long timeoutMillis);
}
