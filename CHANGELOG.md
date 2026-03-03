# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-03-03

### Added

- Initial release
- `createOutlet()` — Create LSL outlets with full metadata (manufacturer, device, channel descriptions)
- `pushSample()` — Push single samples with optional timestamp
- `pushChunk()` — Push sample chunks efficiently (single JNI/C call)
- `hasConsumers()` — Check if any consumer (e.g., LabRecorder) is connected
- `waitForConsumers()` — Blocking wait for consumer connection with timeout
- `destroyOutlet()` / `destroyAllOutlets()` — Clean up resources
- `getLocalClock()` — LSL monotonic clock for synchronized timestamps
- `getLibraryVersion()` / `getProtocolVersion()` — Library introspection
- `getDeviceIP()` — Wi-Fi IP address for KnownPeers configuration
- All 6 LSL channel formats: float32, double64, int32, int16, int8, string
- Android: Kotlin plugin with JNI shim + pre-built liblsl (ARM64, ARMv7, x86_64)
- iOS: Objective-C plugin with direct C API calls via liblsl.xcframework
- Thread-safe outlet management (ConcurrentHashMap on Android, @synchronized on iOS)
- Full TypeScript definitions with JSDoc documentation
