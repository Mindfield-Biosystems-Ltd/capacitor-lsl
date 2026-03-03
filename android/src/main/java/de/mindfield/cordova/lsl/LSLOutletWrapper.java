/**
 * @mindfield/capacitor-lsl
 * Wrapper for a single LSL outlet, holding native pointers and metadata.
 *
 * CRITICAL: Package name MUST be de.mindfield.cordova.lsl to match
 * the JNI function signatures compiled into liblsl_jni.so.
 *
 * Copyright (c) 2026 Mindfield Biosystems Ltd.
 * Licensed under the MIT License.
 */
package de.mindfield.cordova.lsl;

import android.util.Log;

/**
 * Thread-safe wrapper for a single LSL outlet.
 * Stores native pointers and metadata needed for push operations.
 */
public class LSLOutletWrapper {

    private static final String TAG = "LSLOutletWrapper";

    /** Unique identifier for this outlet (e.g. "outlet_1"). */
    public final String outletId;

    /** Native pointer to lsl_outlet. */
    public volatile long outletPtr;

    /** Native pointer to lsl_streaminfo (needed for cleanup). */
    public volatile long infoPtr;

    /** Channel format constant (LSL_FORMAT_FLOAT32, etc.). */
    public final int channelFormat;

    /** Number of channels in this outlet. */
    public final int channelCount;

    /** Stream name for logging. */
    public final String name;

    /** Stream type for logging. */
    public final String type;

    /** Whether this outlet has been destroyed. */
    private volatile boolean destroyed = false;

    public LSLOutletWrapper(String outletId, long outletPtr, long infoPtr,
            int channelFormat, int channelCount, String name, String type) {
        this.outletId = outletId;
        this.outletPtr = outletPtr;
        this.infoPtr = infoPtr;
        this.channelFormat = channelFormat;
        this.channelCount = channelCount;
        this.name = name;
        this.type = type;
    }

    /**
     * Destroy the native outlet and stream info, releasing all resources.
     * Safe to call multiple times.
     */
    public synchronized void destroy() {
        if (destroyed) return;
        destroyed = true;

        if (outletPtr != 0) {
            try {
                LSLPlugin.lsl_destroy_outlet(outletPtr);
            } catch (Exception e) {
                Log.e(TAG, "Error destroying outlet " + outletId + ": " + e.getMessage());
            }
            outletPtr = 0;
        }

        if (infoPtr != 0) {
            try {
                LSLPlugin.lsl_destroy_streaminfo(infoPtr);
            } catch (Exception e) {
                Log.e(TAG, "Error destroying streaminfo for " + outletId + ": " + e.getMessage());
            }
            infoPtr = 0;
        }

        Log.d(TAG, "Outlet " + outletId + " (" + name + "/" + type + ") destroyed");
    }

    public boolean isDestroyed() {
        return destroyed;
    }
}
