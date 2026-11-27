package com.cordiact.app.sandbox;

/** One use per isolated process. The host owns the installed package and authorizes input. */
interface IPackageScriptWorker {
    int getWorkerUid();
    byte[] evaluate(int version, String sourceDigest, in byte[] source, in byte[] input, long deadlineNanos);
}
