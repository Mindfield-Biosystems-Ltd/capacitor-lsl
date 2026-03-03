/**
 * @mindfield/capacitor-lsl
 * JNI Shim Layer — bridges pre-built liblsl_jni.so to Capacitor plugin.
 *
 * CRITICAL: Package name MUST be de.mindfield.cordova.lsl to match
 * the JNI function signatures compiled into liblsl_jni.so.
 * See cordova-lsl/src/android/jni/lsl_jni.c lines 16-17.
 *
 * Copyright (c) 2026 Mindfield Biosystems Ltd.
 * Licensed under the MIT License.
 */
package de.mindfield.cordova.lsl;

import android.util.Log;

/**
 * JNI declarations for liblsl. This class ONLY holds native method declarations
 * and the library loader. No Cordova dependencies.
 *
 * All native methods are public static so the Capacitor plugin can call them directly.
 */
public class LSLPlugin {

    private static final String TAG = "LSL_JNI";
    private static boolean librariesLoaded = false;

    static {
        try {
            System.loadLibrary("lsl");
            System.loadLibrary("lsl_jni");
            librariesLoaded = true;
            Log.i(TAG, "Native libraries loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries: " + e.getMessage());
        }
    }

    /** Check if native libraries are available. */
    public static boolean isAvailable() {
        return librariesLoaded;
    }

    // ======================== Stream Info ========================

    public static native long lsl_create_streaminfo(String name, String type,
            int channelCount, double sampleRate, int channelFormat, String sourceId);

    public static native void lsl_destroy_streaminfo(long info);

    public static native long lsl_get_desc(long info);

    // ======================== XML Metadata ========================

    public static native long lsl_append_child(long parent, String name);

    public static native long lsl_append_child_value(long parent, String name, String value);

    // ======================== Outlet ========================

    public static native long lsl_create_outlet(long info, int chunkSize, int maxBuffered);

    public static native void lsl_destroy_outlet(long outlet);

    public static native int lsl_push_sample_f(long outlet, float[] data, double timestamp);

    public static native int lsl_push_sample_d(long outlet, double[] data, double timestamp);

    public static native int lsl_push_sample_i(long outlet, int[] data, double timestamp);

    public static native int lsl_push_sample_s(long outlet, short[] data, double timestamp);

    public static native int lsl_push_sample_c(long outlet, byte[] data, double timestamp);

    public static native int lsl_push_sample_str(long outlet, String[] data, double timestamp);

    public static native int lsl_push_chunk_f(long outlet, float[] data, int dataElements, double timestamp);

    public static native int lsl_push_chunk_d(long outlet, double[] data, int dataElements, double timestamp);

    public static native int lsl_push_chunk_i(long outlet, int[] data, int dataElements, double timestamp);

    public static native int lsl_push_chunk_s(long outlet, short[] data, int dataElements, double timestamp);

    public static native int lsl_push_chunk_c(long outlet, byte[] data, int dataElements, double timestamp);

    public static native int lsl_have_consumers(long outlet);

    public static native int lsl_wait_for_consumers(long outlet, double timeout);

    // ======================== Utility ========================

    public static native double lsl_local_clock();

    public static native int lsl_library_version();

    public static native int lsl_protocol_version();

    // ======================== Channel Format Constants ========================

    public static final int LSL_FORMAT_FLOAT32 = 1;
    public static final int LSL_FORMAT_DOUBLE64 = 2;
    public static final int LSL_FORMAT_STRING = 3;
    public static final int LSL_FORMAT_INT32 = 4;
    public static final int LSL_FORMAT_INT16 = 5;
    public static final int LSL_FORMAT_INT8 = 6;

    /** Parse channel format string to liblsl constant. Returns -1 if invalid. */
    public static int parseChannelFormat(String format) {
        switch (format) {
            case "float32":  return LSL_FORMAT_FLOAT32;
            case "double64": return LSL_FORMAT_DOUBLE64;
            case "string":   return LSL_FORMAT_STRING;
            case "int32":    return LSL_FORMAT_INT32;
            case "int16":    return LSL_FORMAT_INT16;
            case "int8":     return LSL_FORMAT_INT8;
            default:         return -1;
        }
    }
}
