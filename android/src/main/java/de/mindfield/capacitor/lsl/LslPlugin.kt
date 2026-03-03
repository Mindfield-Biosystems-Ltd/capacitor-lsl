/**
 * @mindfield/capacitor-lsl
 * Capacitor 7 Plugin for Lab Streaming Layer (LSL).
 *
 * Delegates native LSL operations to the JNI shim in de.mindfield.cordova.lsl.LSLPlugin.
 * Pre-built liblsl + liblsl_jni .so files are loaded by the shim.
 *
 * Copyright (c) 2026 Mindfield Biosystems Ltd.
 * Licensed under the MIT License.
 */
package de.mindfield.capacitor.lsl

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import de.mindfield.cordova.lsl.LSLOutletWrapper
import de.mindfield.cordova.lsl.LSLPlugin as JniShim
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@CapacitorPlugin(name = "LSL")
class LslPlugin : Plugin() {

    companion object {
        private const val TAG = "LslPlugin"
    }

    private val outlets = ConcurrentHashMap<String, LSLOutletWrapper>()
    private val outletCounter = AtomicInteger(0)

    // Coroutine scope for blocking operations (waitForConsumers)
    private val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun load() {
        super.load()
        if (!JniShim.isAvailable()) {
            Log.e(TAG, "Native LSL libraries not available!")
        } else {
            Log.i(TAG, "LSL plugin loaded")
        }
    }

    override fun handleOnDestroy() {
        Log.i(TAG, "handleOnDestroy: Cleaning up all outlets")
        destroyAllOutletsInternal()
        pluginScope.cancel()
        super.handleOnDestroy()
    }

    // ======================== Outlet Operations ========================

    @PluginMethod
    fun createOutlet(call: PluginCall) {
        if (!JniShim.isAvailable()) {
            call.reject("Native LSL libraries not loaded")
            return
        }

        pluginScope.launch {
            try {
                val name = call.getString("name")
                    ?: return@launch call.reject("Missing required parameter: name")
                val type = call.getString("type")
                    ?: return@launch call.reject("Missing required parameter: type")
                val channelCount = call.getInt("channelCount")
                    ?: return@launch call.reject("Missing required parameter: channelCount")
                val sampleRate = call.getDouble("sampleRate")
                    ?: return@launch call.reject("Missing required parameter: sampleRate")
                val channelFormatStr = call.getString("channelFormat")
                    ?: return@launch call.reject("Missing required parameter: channelFormat")
                val sourceId = call.getString("sourceId") ?: ""

                val channelFormat = JniShim.parseChannelFormat(channelFormatStr)
                if (channelFormat == -1) {
                    return@launch call.reject("Invalid channelFormat: $channelFormatStr")
                }

                // Create stream info
                val info = JniShim.lsl_create_streaminfo(
                    name, type, channelCount, sampleRate, channelFormat, sourceId
                )
                if (info == 0L) {
                    return@launch call.reject("Failed to create LSL stream info")
                }

                // Add metadata if provided
                val metadata = call.getObject("metadata")
                if (metadata != null) {
                    appendMetadata(info, metadata)
                }

                // Create outlet (chunk_size=0 for default, max_buffered=360 seconds)
                val outlet = JniShim.lsl_create_outlet(info, 0, 360)
                if (outlet == 0L) {
                    JniShim.lsl_destroy_streaminfo(info)
                    return@launch call.reject("Failed to create LSL outlet")
                }

                // Generate unique outlet ID
                val outletId = "outlet_${outletCounter.incrementAndGet()}"

                // Store wrapper
                val wrapper = LSLOutletWrapper(
                    outletId, outlet, info, channelFormat, channelCount, name, type
                )
                outlets[outletId] = wrapper

                Log.i(TAG, "Created outlet: $outletId ($name, $type, ${channelCount}ch, ${sampleRate}Hz)")

                val result = JSObject()
                result.put("outletId", outletId)
                call.resolve(result)

            } catch (e: Exception) {
                Log.e(TAG, "createOutlet error: ${e.message}", e)
                call.reject("createOutlet failed: ${e.message}")
            }
        }
    }

    @PluginMethod
    fun pushSample(call: PluginCall) {
        try {
            val outletId = call.getString("outletId")
                ?: return call.reject("Missing required parameter: outletId")
            val dataArr = call.getArray("data")
                ?: return call.reject("Missing required parameter: data")
            val timestamp = call.getDouble("timestamp") ?: 0.0

            val wrapper = outlets[outletId]
            if (wrapper == null || wrapper.isDestroyed) {
                return call.reject("Outlet not found or destroyed: $outletId")
            }

            if (dataArr.length() != wrapper.channelCount) {
                return call.reject(
                    "Data length (${dataArr.length()}) does not match channelCount (${wrapper.channelCount})"
                )
            }

            synchronized(wrapper) {
                if (wrapper.isDestroyed) {
                    return call.reject("Outlet destroyed during push: $outletId")
                }
                pushSampleNative(wrapper.outletPtr, wrapper.channelFormat, wrapper.channelCount, dataArr, timestamp)
            }

            call.resolve()

        } catch (e: Exception) {
            Log.e(TAG, "pushSample error: ${e.message}", e)
            call.reject("pushSample failed: ${e.message}")
        }
    }

    @PluginMethod
    fun pushChunk(call: PluginCall) {
        try {
            val outletId = call.getString("outletId")
                ?: return call.reject("Missing required parameter: outletId")
            val chunk = call.getArray("data")
                ?: return call.reject("Missing required parameter: data")

            val wrapper = outlets[outletId]
            if (wrapper == null || wrapper.isDestroyed) {
                return call.reject("Outlet not found or destroyed: $outletId")
            }

            val numSamples = chunk.length()
            val cc = wrapper.channelCount

            // Validate all samples first
            for (i in 0 until numSamples) {
                val sample = chunk.getJSONArray(i)
                if (sample.length() != cc) {
                    return call.reject(
                        "Sample $i length (${sample.length()}) does not match channelCount ($cc)"
                    )
                }
            }

            synchronized(wrapper) {
                if (wrapper.isDestroyed) {
                    return call.reject("Outlet destroyed during push: $outletId")
                }
                pushChunkNative(wrapper.outletPtr, wrapper.channelFormat, cc, chunk, numSamples)
            }

            call.resolve()

        } catch (e: Exception) {
            Log.e(TAG, "pushChunk error: ${e.message}", e)
            call.reject("pushChunk failed: ${e.message}")
        }
    }

    @PluginMethod
    fun hasConsumers(call: PluginCall) {
        try {
            val outletId = call.getString("outletId")
                ?: return call.reject("Missing required parameter: outletId")

            val wrapper = outlets[outletId]
            if (wrapper == null || wrapper.isDestroyed) {
                return call.reject("Outlet not found or destroyed: $outletId")
            }

            val has: Boolean
            synchronized(wrapper) {
                if (wrapper.isDestroyed) {
                    return call.reject("Outlet destroyed: $outletId")
                }
                has = JniShim.lsl_have_consumers(wrapper.outletPtr) > 0
            }

            val result = JSObject()
            result.put("hasConsumers", has)
            call.resolve(result)

        } catch (e: Exception) {
            Log.e(TAG, "hasConsumers error: ${e.message}", e)
            call.reject("hasConsumers failed: ${e.message}")
        }
    }

    @PluginMethod
    fun waitForConsumers(call: PluginCall) {
        val outletId = call.getString("outletId")
            ?: return call.reject("Missing required parameter: outletId")
        val timeout = call.getDouble("timeout")
            ?: return call.reject("Missing required parameter: timeout")

        val wrapper = outlets[outletId]
        if (wrapper == null || wrapper.isDestroyed) {
            return call.reject("Outlet not found or destroyed: $outletId")
        }

        // Blocking call — run on IO dispatcher
        pluginScope.launch {
            try {
                val found = JniShim.lsl_wait_for_consumers(wrapper.outletPtr, timeout) > 0

                val result = JSObject()
                result.put("hasConsumers", found)
                call.resolve(result)

            } catch (e: Exception) {
                Log.e(TAG, "waitForConsumers error: ${e.message}", e)
                call.reject("waitForConsumers failed: ${e.message}")
            }
        }
    }

    @PluginMethod
    fun destroyOutlet(call: PluginCall) {
        try {
            val outletId = call.getString("outletId")
                ?: return call.reject("Missing required parameter: outletId")

            val wrapper = outlets.remove(outletId)
            if (wrapper == null) {
                return call.reject("Outlet not found: $outletId")
            }

            wrapper.destroy()
            Log.i(TAG, "Destroyed outlet: $outletId")
            call.resolve()

        } catch (e: Exception) {
            Log.e(TAG, "destroyOutlet error: ${e.message}", e)
            call.reject("destroyOutlet failed: ${e.message}")
        }
    }

    @PluginMethod
    fun destroyAllOutlets(call: PluginCall) {
        destroyAllOutletsInternal()
        call.resolve()
    }

    // ======================== Utility Operations ========================

    @PluginMethod
    fun getLocalClock(call: PluginCall) {
        try {
            val clock = JniShim.lsl_local_clock()
            val result = JSObject()
            result.put("timestamp", clock)
            call.resolve(result)
        } catch (e: Exception) {
            call.reject("getLocalClock failed: ${e.message}")
        }
    }

    @PluginMethod
    fun getLibraryVersion(call: PluginCall) {
        try {
            val version = JniShim.lsl_library_version()
            val major = version / 100
            val minor = version % 100
            val result = JSObject()
            result.put("version", "$major.$minor")
            call.resolve(result)
        } catch (e: Exception) {
            call.reject("getLibraryVersion failed: ${e.message}")
        }
    }

    @PluginMethod
    fun getProtocolVersion(call: PluginCall) {
        try {
            val version = JniShim.lsl_protocol_version()
            val result = JSObject()
            result.put("version", version)
            call.resolve(result)
        } catch (e: Exception) {
            call.reject("getProtocolVersion failed: ${e.message}")
        }
    }

    @PluginMethod
    fun getDeviceIP(call: PluginCall) {
        try {
            val ip = getWifiIPAddress()
            if (ip != null) {
                val result = JSObject()
                result.put("ip", ip)
                call.resolve(result)
            } else {
                call.reject("Could not determine Wi-Fi IP address. Ensure Wi-Fi is connected.")
            }
        } catch (e: Exception) {
            call.reject("getDeviceIP failed: ${e.message}")
        }
    }

    // ======================== Internal Helpers ========================

    private fun destroyAllOutletsInternal() {
        val count = outlets.size
        for (wrapper in outlets.values) {
            try {
                wrapper.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying outlet ${wrapper.outletId}: ${e.message}")
            }
        }
        outlets.clear()
        if (count > 0) {
            Log.i(TAG, "Destroyed $count outlet(s)")
        }
    }

    /**
     * Push a single sample using the correct native type for the channel format.
     * JS numbers are always doubles — we cast safely to the target type.
     * Runs SYNCHRONOUSLY on the calling thread (no coroutine overhead).
     */
    private fun pushSampleNative(
        outletPtr: Long,
        channelFormat: Int,
        channelCount: Int,
        data: JSArray,
        timestamp: Double
    ) {
        when (channelFormat) {
            JniShim.LSL_FORMAT_FLOAT32 -> {
                val sample = FloatArray(channelCount) { data.optDouble(it, 0.0).toFloat() }
                JniShim.lsl_push_sample_f(outletPtr, sample, timestamp)
            }
            JniShim.LSL_FORMAT_DOUBLE64 -> {
                val sample = DoubleArray(channelCount) { data.optDouble(it, 0.0) }
                JniShim.lsl_push_sample_d(outletPtr, sample, timestamp)
            }
            JniShim.LSL_FORMAT_INT32 -> {
                val sample = IntArray(channelCount) { data.optDouble(it, 0.0).toInt() }
                JniShim.lsl_push_sample_i(outletPtr, sample, timestamp)
            }
            JniShim.LSL_FORMAT_INT16 -> {
                val sample = ShortArray(channelCount) { data.optDouble(it, 0.0).toInt().toShort() }
                JniShim.lsl_push_sample_s(outletPtr, sample, timestamp)
            }
            JniShim.LSL_FORMAT_INT8 -> {
                val sample = ByteArray(channelCount) { data.optDouble(it, 0.0).toInt().toByte() }
                JniShim.lsl_push_sample_c(outletPtr, sample, timestamp)
            }
            JniShim.LSL_FORMAT_STRING -> {
                val sample = Array(channelCount) { data.optString(it) ?: "" }
                JniShim.lsl_push_sample_str(outletPtr, sample, timestamp)
            }
            else -> throw IllegalArgumentException("Unsupported channel format: $channelFormat")
        }
    }

    /**
     * Push a chunk of samples using native lsl_push_chunk_* for performance.
     * Flattens the 2D JSON array into a 1D native array and pushes in one JNI call.
     * Falls back to sample-by-sample for string format.
     * Runs SYNCHRONOUSLY on the calling thread.
     */
    private fun pushChunkNative(
        outletPtr: Long,
        channelFormat: Int,
        channelCount: Int,
        chunk: JSArray,
        numSamples: Int
    ) {
        val totalElements = numSamples * channelCount

        when (channelFormat) {
            JniShim.LSL_FORMAT_FLOAT32 -> {
                val flat = FloatArray(totalElements)
                for (i in 0 until numSamples) {
                    val sample = chunk.getJSONArray(i)
                    for (j in 0 until channelCount) {
                        flat[i * channelCount + j] = sample.optDouble(j, 0.0).toFloat()
                    }
                }
                JniShim.lsl_push_chunk_f(outletPtr, flat, totalElements, 0.0)
            }
            JniShim.LSL_FORMAT_DOUBLE64 -> {
                val flat = DoubleArray(totalElements)
                for (i in 0 until numSamples) {
                    val sample = chunk.getJSONArray(i)
                    for (j in 0 until channelCount) {
                        flat[i * channelCount + j] = sample.optDouble(j, 0.0)
                    }
                }
                JniShim.lsl_push_chunk_d(outletPtr, flat, totalElements, 0.0)
            }
            JniShim.LSL_FORMAT_INT32 -> {
                val flat = IntArray(totalElements)
                for (i in 0 until numSamples) {
                    val sample = chunk.getJSONArray(i)
                    for (j in 0 until channelCount) {
                        flat[i * channelCount + j] = sample.optDouble(j, 0.0).toInt()
                    }
                }
                JniShim.lsl_push_chunk_i(outletPtr, flat, totalElements, 0.0)
            }
            JniShim.LSL_FORMAT_INT16 -> {
                val flat = ShortArray(totalElements)
                for (i in 0 until numSamples) {
                    val sample = chunk.getJSONArray(i)
                    for (j in 0 until channelCount) {
                        flat[i * channelCount + j] = sample.optDouble(j, 0.0).toInt().toShort()
                    }
                }
                JniShim.lsl_push_chunk_s(outletPtr, flat, totalElements, 0.0)
            }
            JniShim.LSL_FORMAT_INT8 -> {
                val flat = ByteArray(totalElements)
                for (i in 0 until numSamples) {
                    val sample = chunk.getJSONArray(i)
                    for (j in 0 until channelCount) {
                        flat[i * channelCount + j] = sample.optDouble(j, 0.0).toInt().toByte()
                    }
                }
                JniShim.lsl_push_chunk_c(outletPtr, flat, totalElements, 0.0)
            }
            JniShim.LSL_FORMAT_STRING -> {
                // String format: fall back to sample-by-sample
                for (i in 0 until numSamples) {
                    val sample = chunk.getJSONArray(i)
                    val strs = Array(channelCount) { sample.optString(it) ?: "" }
                    JniShim.lsl_push_sample_str(outletPtr, strs, 0.0)
                }
            }
            else -> throw IllegalArgumentException("Unsupported channel format: $channelFormat")
        }
    }

    /**
     * Append metadata XML to stream info.
     */
    private fun appendMetadata(info: Long, metadata: JSObject) {
        val desc = JniShim.lsl_get_desc(info)
        if (desc == 0L) return

        metadata.getString("manufacturer")?.let {
            JniShim.lsl_append_child_value(desc, "manufacturer", it)
        }
        metadata.getString("device")?.let {
            JniShim.lsl_append_child_value(desc, "device", it)
        }

        val channels = metadata.optJSONArray("channels")
        if (channels != null) {
            val channelsNode = JniShim.lsl_append_child(desc, "channels")
            if (channelsNode != 0L) {
                for (i in 0 until channels.length()) {
                    val ch = channels.optJSONObject(i) ?: continue
                    val channelNode = JniShim.lsl_append_child(channelsNode, "channel")
                    if (channelNode != 0L) {
                        ch.optString("label", null)?.let {
                            JniShim.lsl_append_child_value(channelNode, "label", it)
                        }
                        ch.optString("unit", null)?.let {
                            JniShim.lsl_append_child_value(channelNode, "unit", it)
                        }
                        ch.optString("type", null)?.let {
                            JniShim.lsl_append_child_value(channelNode, "type", it)
                        }
                    }
                }
            }
        }
    }

    /**
     * Get the device's Wi-Fi IP address.
     * Uses ConnectivityManager on Android 10+ for accuracy.
     */
    private fun getWifiIPAddress(): String? {
        try {
            val ctx = context

            // Android 10+ preferred method
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (cm != null) {
                    val activeNetwork = cm.activeNetwork
                    if (activeNetwork != null) {
                        val caps = cm.getNetworkCapabilities(activeNetwork)
                        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            val linkProps = cm.getLinkProperties(activeNetwork)
                            if (linkProps != null) {
                                for (addr in linkProps.linkAddresses) {
                                    val inetAddr = addr.address
                                    if (!inetAddr.isLoopbackAddress && inetAddr is Inet4Address) {
                                        return inetAddr.hostAddress
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Fallback: WifiManager (deprecated but widely compatible)
            @Suppress("DEPRECATION")
            val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                @Suppress("DEPRECATION")
                val ipInt = wifiManager.connectionInfo.ipAddress
                if (ipInt != 0) {
                    return "${ipInt and 0xff}.${ipInt shr 8 and 0xff}.${ipInt shr 16 and 0xff}.${ipInt shr 24 and 0xff}"
                }
            }

            // Last resort: enumerate network interfaces
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in interfaces) {
                if (ni.isLoopback || !ni.isUp) continue
                if (!ni.name.startsWith("wlan")) continue
                for (addr in ni.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error getting Wi-Fi IP: ${e.message}", e)
        }
        return null
    }
}
