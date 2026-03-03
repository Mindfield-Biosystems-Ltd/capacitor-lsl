/**
 * @mindfield/capacitor-lsl
 * Capacitor 7 Plugin for Lab Streaming Layer (LSL).
 *
 * Copyright (c) 2026 Mindfield Biosystems Ltd.
 * Licensed under the MIT License.
 */

#import <Capacitor/CAPPlugin.h>

/**
 * Capacitor Plugin for Lab Streaming Layer (LSL).
 * Provides outlet (sender) functionality for streaming biosignal data.
 *
 * Directly calls the liblsl C API from Objective-C (no Swift bridging needed
 * because the xcframework lacks a Clang module.modulemap).
 */
@interface LslPlugin : CAPPlugin

// Outlet operations
- (void)createOutlet:(CAPPluginCall *)call;
- (void)pushSample:(CAPPluginCall *)call;
- (void)pushChunk:(CAPPluginCall *)call;
- (void)hasConsumers:(CAPPluginCall *)call;
- (void)waitForConsumers:(CAPPluginCall *)call;
- (void)destroyOutlet:(CAPPluginCall *)call;
- (void)destroyAllOutlets:(CAPPluginCall *)call;

// Utility
- (void)getLocalClock:(CAPPluginCall *)call;
- (void)getLibraryVersion:(CAPPluginCall *)call;
- (void)getProtocolVersion:(CAPPluginCall *)call;
- (void)getDeviceIP:(CAPPluginCall *)call;

@end
