# @mindfield/capacitor-lsl

[![npm version](https://img.shields.io/npm/v/@mindfield/capacitor-lsl.svg)](https://www.npmjs.com/package/@mindfield/capacitor-lsl)
[![license](https://img.shields.io/npm/l/@mindfield/capacitor-lsl.svg)](LICENSE)

Capacitor 7 plugin for [Lab Streaming Layer (LSL)](https://labstreaminglayer.org/) — stream biosignal data from mobile devices to LabRecorder or any LSL-compatible receiver.

## Installation

```bash
npm install @mindfield/capacitor-lsl
npx cap sync
```

> **Warning:** Do NOT install `cordova-plugin-lsl` and `@mindfield/capacitor-lsl` in the same project. They share the same native libraries and will cause duplicate class conflicts.

## Platforms

| Platform | Status | Notes |
|----------|--------|-------|
| Android  | Supported | ARM64, ARMv7, x86_64 |
| iOS      | Supported | ARM64 (device + simulator) |
| Web      | Not supported | LSL requires native UDP multicast |

## Quick Start

```typescript
import { LSL } from '@mindfield/capacitor-lsl';

// Create an outlet
const { outletId } = await LSL.createOutlet({
  name: 'eSense_EDA',
  type: 'EDA',
  channelCount: 1,
  sampleRate: 5.0,
  channelFormat: 'float32',
  sourceId: 'esense-eda-001',
  metadata: {
    manufacturer: 'Mindfield Biosystems',
    device: 'eSense EDA',
    channels: [{ label: 'EDA', unit: 'microsiemens', type: 'EDA' }],
  },
});

// Show IP for KnownPeers config
const { ip } = await LSL.getDeviceIP();
console.log(`Add to lsl_api.cfg: KnownPeers = {${ip}}`);

// Push samples
await LSL.pushSample({
  outletId,
  data: [3.14],
});

// Push chunks (more efficient for high sample rates)
await LSL.pushChunk({
  outletId,
  data: [[3.14], [3.15], [3.16], [3.17], [3.18]],
});

// Check for consumers (LabRecorder etc.)
const { hasConsumers } = await LSL.hasConsumers({ outletId });

// Wait for a consumer to connect (blocking, with timeout)
const result = await LSL.waitForConsumers({ outletId, timeout: 30.0 });

// Cleanup
await LSL.destroyOutlet({ outletId });
// or: await LSL.destroyAllOutlets();
```

## API Reference

### Outlet Operations

| Method | Description |
|--------|-------------|
| `createOutlet(options)` | Create a new LSL outlet. Returns `{ outletId }`. |
| `pushSample(options)` | Push a single sample to an outlet. |
| `pushChunk(options)` | Push a chunk of samples (single JNI/C call, more efficient). |
| `hasConsumers(options)` | Check if any consumer is connected. Returns `{ hasConsumers }`. |
| `waitForConsumers(options)` | Wait for a consumer (blocking). Returns `{ hasConsumers }`. |
| `destroyOutlet(options)` | Destroy a specific outlet. |
| `destroyAllOutlets()` | Destroy all outlets and release resources. |

### Utility Operations

| Method | Description |
|--------|-------------|
| `getLocalClock()` | Get LSL monotonic clock time. Returns `{ timestamp }`. |
| `getLibraryVersion()` | Get liblsl version. Returns `{ version }`. |
| `getProtocolVersion()` | Get LSL protocol version. Returns `{ version }`. |
| `getDeviceIP()` | Get Wi-Fi IP address. Returns `{ ip }`. |

### Types

```typescript
type ChannelFormat = 'float32' | 'double64' | 'int32' | 'int16' | 'int8' | 'string';

interface CreateOutletOptions {
  name: string;
  type: string;
  channelCount: number;
  sampleRate: number;
  channelFormat: ChannelFormat;
  sourceId?: string;
  metadata?: StreamMetadata;
}

interface StreamMetadata {
  manufacturer?: string;
  device?: string;
  channels?: ChannelInfo[];
}

interface ChannelInfo {
  label: string;
  unit: string;
  type: string;
}
```

## Receiving LSL Streams on PC

1. Install [LabRecorder](https://github.com/labstreaminglayer/App-LabRecorder)
2. Get the device IP: `const { ip } = await LSL.getDeviceIP()`
3. Add to `lsl_api.cfg`:
   ```
   [lab]
   KnownPeers = {192.168.1.100}
   ```
4. Start LabRecorder — the stream should appear in the stream list

## Migration from cordova-plugin-lsl

| Cordova | Capacitor |
|---------|-----------|
| `LSL.createOutlet(options)` → `string` | `LSL.createOutlet(options)` → `{ outletId: string }` |
| `LSL.pushSample(outletId, data, ts)` | `LSL.pushSample({ outletId, data, timestamp })` |
| `LSL.pushChunk(outletId, data)` | `LSL.pushChunk({ outletId, data })` |
| `LSL.hasConsumers(outletId)` → `boolean` | `LSL.hasConsumers({ outletId })` → `{ hasConsumers }` |
| `LSL.waitForConsumers(outletId, timeout)` | `LSL.waitForConsumers({ outletId, timeout })` |
| `LSL.destroyOutlet(outletId)` | `LSL.destroyOutlet({ outletId })` |
| `LSL.getLocalClock()` → `number` | `LSL.getLocalClock()` → `{ timestamp }` |
| `LSL.getLibraryVersion()` → `string` | `LSL.getLibraryVersion()` → `{ version }` |
| `LSL.getDeviceIP()` → `string` | `LSL.getDeviceIP()` → `{ ip }` |

Key differences:
- All parameters are passed as options objects (Capacitor convention)
- All return values are wrapped in objects
- Import from `@mindfield/capacitor-lsl` instead of global `LSL` variable

## Architecture

```
TypeScript API (definitions.ts)
    │
    ├── Android: Kotlin Plugin (LslPlugin.kt)
    │       └── JNI Shim (de.mindfield.cordova.lsl.LSLPlugin.java)
    │               └── liblsl_jni.so → liblsl.so (pre-built C library)
    │
    └── iOS: Objective-C Plugin (LslPlugin.m)
            └── liblsl.xcframework (pre-built C library, direct C API calls)
```

## License

MIT — Copyright (c) 2026 Mindfield Biosystems Ltd.
