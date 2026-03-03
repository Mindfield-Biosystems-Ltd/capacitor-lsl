/**
 * @mindfield/capacitor-lsl
 * Lab Streaming Layer (LSL) plugin for Capacitor 7.
 *
 * Copyright (c) 2026 Mindfield Biosystems Ltd.
 * Licensed under the MIT License.
 */

/** Supported LSL channel data formats. */
export type ChannelFormat =
  | 'float32'
  | 'double64'
  | 'int32'
  | 'int16'
  | 'int8'
  | 'string';

/** Channel metadata description. */
export interface ChannelInfo {
  /** Channel label (e.g. "EDA", "HeartRate"). */
  label: string;
  /** Unit of measurement (e.g. "microsiemens", "bpm"). */
  unit: string;
  /** Channel type (e.g. "EDA", "PPG"). */
  type: string;
}

/** Stream metadata embedded in the LSL stream info XML. */
export interface StreamMetadata {
  /** Manufacturer name (e.g. "Mindfield Biosystems"). */
  manufacturer?: string;
  /** Device name (e.g. "eSense EDA"). */
  device?: string;
  /** Channel descriptions. Length should match channelCount. */
  channels?: ChannelInfo[];
}

/** Options for creating an LSL outlet. */
export interface CreateOutletOptions {
  /** Stream name (e.g. "eSense_EDA"). */
  name: string;
  /** Stream type (e.g. "EDA", "Temperature", "EMG", "PPG", "Respiration"). */
  type: string;
  /** Number of channels (>= 1). */
  channelCount: number;
  /** Nominal sampling rate in Hz (e.g. 5.0). Use 0 for irregular rate. */
  sampleRate: number;
  /** Data format for channel values. */
  channelFormat: ChannelFormat;
  /** Unique source identifier for the device. */
  sourceId?: string;
  /** Optional stream metadata (manufacturer, device, channel descriptions). */
  metadata?: StreamMetadata;
}

/** Result of creating an outlet. */
export interface CreateOutletResult {
  /** Unique outlet identifier for subsequent operations. */
  outletId: string;
}

/** Options for pushing a single sample. */
export interface PushSampleOptions {
  /** The outlet ID returned by createOutlet. */
  outletId: string;
  /** Array of sample values (length must match channelCount). */
  data: number[] | string[];
  /**
   * Optional LSL timestamp from getLocalClock().
   * If omitted (0), native code timestamps automatically.
   * WARNING: Do NOT use Date.now() — only use values from getLocalClock().
   */
  timestamp?: number;
}

/** Options for pushing a chunk of samples. */
export interface PushChunkOptions {
  /** The outlet ID returned by createOutlet. */
  outletId: string;
  /** Array of sample arrays. More efficient than multiple pushSample calls. */
  data: number[][] | string[][];
}

/** Options for checking/waiting for consumers. */
export interface OutletIdOptions {
  /** The outlet ID returned by createOutlet. */
  outletId: string;
}

/** Options for waiting for consumers. */
export interface WaitForConsumersOptions {
  /** The outlet ID returned by createOutlet. */
  outletId: string;
  /** Maximum wait time in seconds. */
  timeout: number;
}

/** Result of hasConsumers/waitForConsumers. */
export interface HasConsumersResult {
  /** True if at least one consumer is connected. */
  hasConsumers: boolean;
}

/** Result of getLocalClock. */
export interface LocalClockResult {
  /** LSL timestamp in seconds (high-resolution monotonic clock). */
  timestamp: number;
}

/** Result of getLibraryVersion. */
export interface LibraryVersionResult {
  /** Version string (e.g. "1.17"). */
  version: string;
}

/** Result of getProtocolVersion. */
export interface ProtocolVersionResult {
  /** Protocol version as integer. */
  version: number;
}

/** Result of getDeviceIP. */
export interface DeviceIPResult {
  /** Wi-Fi IP address string (e.g. "192.168.1.100"). */
  ip: string;
}

/** Lab Streaming Layer Plugin API. */
export interface LSLPlugin {
  // ==================== OUTLET (Sender) ====================

  /**
   * Create a new LSL outlet for streaming data.
   * @param options - Outlet configuration.
   * @returns Promise resolving with the outlet ID.
   */
  createOutlet(options: CreateOutletOptions): Promise<CreateOutletResult>;

  /**
   * Push a single sample to an outlet.
   * @param options - Outlet ID, sample data, and optional timestamp.
   */
  pushSample(options: PushSampleOptions): Promise<void>;

  /**
   * Push a chunk of samples to an outlet (more efficient than multiple pushSample calls).
   * Recommended for sample rates above 10 Hz.
   * @param options - Outlet ID and array of sample arrays.
   */
  pushChunk(options: PushChunkOptions): Promise<void>;

  /**
   * Check if any consumers (e.g. LabRecorder) are currently connected to the outlet.
   * @param options - Outlet ID.
   */
  hasConsumers(options: OutletIdOptions): Promise<HasConsumersResult>;

  /**
   * Wait until at least one consumer connects to the outlet or timeout expires.
   * @param options - Outlet ID and timeout in seconds.
   */
  waitForConsumers(options: WaitForConsumersOptions): Promise<HasConsumersResult>;

  /**
   * Destroy an outlet and release its resources.
   * @param options - Outlet ID.
   */
  destroyOutlet(options: OutletIdOptions): Promise<void>;

  /**
   * Destroy all outlets and release all resources.
   * Call this during app cleanup or before creating new outlets.
   */
  destroyAllOutlets(): Promise<void>;

  // ==================== UTILITY ====================

  /**
   * Get the current LSL clock time.
   * Use this for custom timestamps with pushSample().
   * WARNING: Do NOT use Date.now() — it is NOT compatible with LSL's clock.
   */
  getLocalClock(): Promise<LocalClockResult>;

  /**
   * Get the version of the underlying liblsl library.
   */
  getLibraryVersion(): Promise<LibraryVersionResult>;

  /**
   * Get the LSL protocol version number.
   */
  getProtocolVersion(): Promise<ProtocolVersionResult>;

  /**
   * Get the device's current Wi-Fi IP address.
   * Display this to the user so they can add it as KnownPeer in lsl_api.cfg on their PC.
   */
  getDeviceIP(): Promise<DeviceIPResult>;
}
