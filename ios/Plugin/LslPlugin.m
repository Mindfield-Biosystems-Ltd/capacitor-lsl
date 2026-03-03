/**
 * @mindfield/capacitor-lsl
 * Capacitor 7 Plugin for Lab Streaming Layer (LSL).
 *
 * Copyright (c) 2026 Mindfield Biosystems Ltd.
 * Licensed under the MIT License.
 */

#import "LslPlugin.h"
#import <Capacitor/Capacitor-Swift.h>
#import <lsl_c.h>
#import <ifaddrs.h>
#import <arpa/inet.h>
#import <net/if.h>

#pragma mark - Outlet Wrapper

/**
 * Internal wrapper holding native LSL pointers and metadata for a single outlet.
 */
@interface LSLOutletWrapper : NSObject

@property (nonatomic, copy) NSString *outletId;
@property (nonatomic, assign) lsl_outlet outlet;
@property (nonatomic, assign) lsl_streaminfo info;
@property (nonatomic, assign) int channelFormat;
@property (nonatomic, assign) int channelCount;
@property (nonatomic, copy) NSString *name;
@property (nonatomic, copy) NSString *type;
@property (nonatomic, assign) BOOL destroyed;

- (void)destroy;

@end

@implementation LSLOutletWrapper

- (void)destroy {
    @synchronized (self) {
        if (self.destroyed) return;
        self.destroyed = YES;

        if (self.outlet) {
            lsl_destroy_outlet(self.outlet);
            self.outlet = NULL;
        }
        if (self.info) {
            lsl_destroy_streaminfo(self.info);
            self.info = NULL;
        }

        NSLog(@"[LslPlugin] Outlet %@ (%@/%@) destroyed", self.outletId, self.name, self.type);
    }
}

@end

#pragma mark - LslPlugin Implementation

@interface LslPlugin ()

/** Thread-safe dictionary of outletId -> LSLOutletWrapper. */
@property (nonatomic, strong) NSMutableDictionary<NSString *, LSLOutletWrapper *> *outlets;

/** Counter for generating unique outlet IDs. */
@property (nonatomic, assign) NSInteger outletCounter;

/** Serial queue for thread-safe outlet operations. */
@property (nonatomic, strong) dispatch_queue_t lslQueue;

@end

@implementation LslPlugin

- (void)load {
    self.outlets = [NSMutableDictionary new];
    self.outletCounter = 0;
    self.lslQueue = dispatch_queue_create("de.mindfield.capacitor.lsl", DISPATCH_QUEUE_SERIAL);
    NSLog(@"[LslPlugin] Initialized");
}

#pragma mark - Channel Format Constants

static int parseChannelFormat(NSString *format) {
    if ([format isEqualToString:@"float32"])  return cft_float32;
    if ([format isEqualToString:@"double64"]) return cft_double64;
    if ([format isEqualToString:@"string"])   return cft_string;
    if ([format isEqualToString:@"int32"])    return cft_int32;
    if ([format isEqualToString:@"int16"])    return cft_int16;
    if ([format isEqualToString:@"int8"])     return cft_int8;
    return -1;
}

#pragma mark - Outlet Operations

- (void)createOutlet:(CAPPluginCall *)call {
    dispatch_async(self.lslQueue, ^{
        NSString *name = [call getString:@"name" defaultValue:nil];
        NSString *type = [call getString:@"type" defaultValue:nil];
        NSNumber *channelCountNum = [call getNumber:@"channelCount" defaultValue:nil];
        NSNumber *sampleRateNum = [call getNumber:@"sampleRate" defaultValue:nil];
        NSString *channelFormatStr = [call getString:@"channelFormat" defaultValue:nil];
        NSString *sourceId = [call getString:@"sourceId" defaultValue:@""];

        if (!name || !type || !channelCountNum || !sampleRateNum || !channelFormatStr) {
            [call reject:@"Missing required parameters" :nil :nil :nil];
            return;
        }

        int channelFormat = parseChannelFormat(channelFormatStr);
        if (channelFormat == -1) {
            [call reject:@"Invalid channelFormat" :nil :nil :nil];
            return;
        }

        int channelCount = [channelCountNum intValue];
        double sampleRate = [sampleRateNum doubleValue];

        // Create stream info
        lsl_streaminfo info = lsl_create_streaminfo(
            [name UTF8String],
            [type UTF8String],
            channelCount,
            sampleRate,
            (lsl_channel_format_t)channelFormat,
            [sourceId UTF8String]
        );

        if (!info) {
            [call reject:@"Failed to create LSL stream info" :nil :nil :nil];
            return;
        }

        // Add metadata if provided
        JSObject *metadata = [call getObject:@"metadata" defaultValue:nil];
        if (metadata) {
            lsl_xml_ptr desc = lsl_get_desc(info);
            if (desc) {
                NSString *manufacturer = [metadata objectForKey:@"manufacturer"];
                if (manufacturer && [manufacturer isKindOfClass:[NSString class]]) {
                    lsl_append_child_value(desc, "manufacturer", [manufacturer UTF8String]);
                }

                NSString *device = [metadata objectForKey:@"device"];
                if (device && [device isKindOfClass:[NSString class]]) {
                    lsl_append_child_value(desc, "device", [device UTF8String]);
                }

                NSArray *channels = [metadata objectForKey:@"channels"];
                if (channels && [channels isKindOfClass:[NSArray class]]) {
                    lsl_xml_ptr channelsNode = lsl_append_child(desc, "channels");
                    if (channelsNode) {
                        for (NSDictionary *ch in channels) {
                            if (![ch isKindOfClass:[NSDictionary class]]) continue;
                            lsl_xml_ptr channelNode = lsl_append_child(channelsNode, "channel");
                            if (channelNode) {
                                NSString *label = ch[@"label"];
                                NSString *unit = ch[@"unit"];
                                NSString *chType = ch[@"type"];
                                if (label) lsl_append_child_value(channelNode, "label", [label UTF8String]);
                                if (unit) lsl_append_child_value(channelNode, "unit", [unit UTF8String]);
                                if (chType) lsl_append_child_value(channelNode, "type", [chType UTF8String]);
                            }
                        }
                    }
                }
            }
        }

        // Create outlet (chunk_size=0 for default, max_buffered=360 seconds)
        lsl_outlet outlet = lsl_create_outlet(info, 0, 360);
        if (!outlet) {
            lsl_destroy_streaminfo(info);
            [call reject:@"Failed to create LSL outlet" :nil :nil :nil];
            return;
        }

        // Generate unique outlet ID
        NSString *outletId;
        @synchronized (self) {
            self.outletCounter++;
            outletId = [NSString stringWithFormat:@"outlet_%ld", (long)self.outletCounter];
        }

        // Store wrapper
        LSLOutletWrapper *wrapper = [LSLOutletWrapper new];
        wrapper.outletId = outletId;
        wrapper.outlet = outlet;
        wrapper.info = info;
        wrapper.channelFormat = channelFormat;
        wrapper.channelCount = channelCount;
        wrapper.name = name;
        wrapper.type = type;
        wrapper.destroyed = NO;

        @synchronized (self.outlets) {
            self.outlets[outletId] = wrapper;
        }

        NSLog(@"[LslPlugin] Created outlet: %@ (%@, %@, %dch, %.1fHz)",
              outletId, name, type, channelCount, sampleRate);

        [call resolve:@{@"outletId": outletId}];
    });
}

- (void)pushSample:(CAPPluginCall *)call {
    NSString *outletId = [call getString:@"outletId" defaultValue:nil];
    NSArray *data = [call getArray:@"data" defaultValue:nil];
    NSNumber *timestampNum = [call getNumber:@"timestamp" defaultValue:@(0.0)];
    double timestamp = [timestampNum doubleValue];

    if (!outletId || !data) {
        [call reject:@"Missing required parameters (outletId, data)" :nil :nil :nil];
        return;
    }

    LSLOutletWrapper *wrapper;
    @synchronized (self.outlets) {
        wrapper = self.outlets[outletId];
    }

    if (!wrapper || wrapper.destroyed) {
        [call reject:[NSString stringWithFormat:@"Outlet not found: %@", outletId] :nil :nil :nil];
        return;
    }

    if ((int)data.count != wrapper.channelCount) {
        [call reject:[NSString stringWithFormat:
                      @"Data length (%lu) does not match channelCount (%d)",
                      (unsigned long)data.count, wrapper.channelCount] :nil :nil :nil];
        return;
    }

    @synchronized (wrapper) {
        if (wrapper.destroyed) {
            [call reject:@"Outlet destroyed during push" :nil :nil :nil];
            return;
        }
        [self pushSampleNative:wrapper data:data timestamp:timestamp];
    }
    [call resolve];
}

- (void)pushChunk:(CAPPluginCall *)call {
    NSString *outletId = [call getString:@"outletId" defaultValue:nil];
    NSArray *chunk = [call getArray:@"data" defaultValue:nil];

    if (!outletId || !chunk) {
        [call reject:@"Missing required parameters (outletId, data)" :nil :nil :nil];
        return;
    }

    LSLOutletWrapper *wrapper;
    @synchronized (self.outlets) {
        wrapper = self.outlets[outletId];
    }

    if (!wrapper || wrapper.destroyed) {
        [call reject:[NSString stringWithFormat:@"Outlet not found: %@", outletId] :nil :nil :nil];
        return;
    }

    int cc = wrapper.channelCount;
    NSUInteger numSamples = chunk.count;

    // Validate all samples first
    for (NSUInteger i = 0; i < numSamples; i++) {
        NSArray *sample = chunk[i];
        if (![sample isKindOfClass:[NSArray class]] || (int)sample.count != cc) {
            [call reject:[NSString stringWithFormat:
                          @"Sample %lu has invalid length", (unsigned long)i] :nil :nil :nil];
            return;
        }
    }

    @synchronized (wrapper) {
        if (wrapper.destroyed) {
            [call reject:@"Outlet destroyed during push" :nil :nil :nil];
            return;
        }
        [self pushChunkNative:wrapper chunk:chunk numSamples:numSamples];
    }
    [call resolve];
}

- (void)hasConsumers:(CAPPluginCall *)call {
    NSString *outletId = [call getString:@"outletId" defaultValue:nil];
    if (!outletId) {
        [call reject:@"Missing required parameter: outletId" :nil :nil :nil];
        return;
    }

    LSLOutletWrapper *wrapper;
    @synchronized (self.outlets) {
        wrapper = self.outlets[outletId];
    }

    if (!wrapper || wrapper.destroyed) {
        [call reject:[NSString stringWithFormat:@"Outlet not found: %@", outletId] :nil :nil :nil];
        return;
    }

    BOOL hasConsumers;
    @synchronized (wrapper) {
        if (wrapper.destroyed) {
            [call reject:@"Outlet destroyed" :nil :nil :nil];
            return;
        }
        hasConsumers = lsl_have_consumers(wrapper.outlet) > 0;
    }
    [call resolve:@{@"hasConsumers": @(hasConsumers)}];
}

- (void)waitForConsumers:(CAPPluginCall *)call {
    NSString *outletId = [call getString:@"outletId" defaultValue:nil];
    NSNumber *timeoutNum = [call getNumber:@"timeout" defaultValue:nil];

    if (!outletId || !timeoutNum) {
        [call reject:@"Missing required parameters (outletId, timeout)" :nil :nil :nil];
        return;
    }

    double timeout = [timeoutNum doubleValue];

    LSLOutletWrapper *wrapper;
    @synchronized (self.outlets) {
        wrapper = self.outlets[outletId];
    }

    if (!wrapper || wrapper.destroyed) {
        [call reject:[NSString stringWithFormat:@"Outlet not found: %@", outletId] :nil :nil :nil];
        return;
    }

    // Blocking call — run on serial queue
    dispatch_async(self.lslQueue, ^{
        BOOL found = lsl_wait_for_consumers(wrapper.outlet, timeout) > 0;
        [call resolve:@{@"hasConsumers": @(found)}];
    });
}

- (void)destroyOutlet:(CAPPluginCall *)call {
    NSString *outletId = [call getString:@"outletId" defaultValue:nil];
    if (!outletId) {
        [call reject:@"Missing required parameter: outletId" :nil :nil :nil];
        return;
    }

    LSLOutletWrapper *wrapper;
    @synchronized (self.outlets) {
        wrapper = self.outlets[outletId];
        if (wrapper) {
            [self.outlets removeObjectForKey:outletId];
        }
    }

    if (!wrapper) {
        [call reject:[NSString stringWithFormat:@"Outlet not found: %@", outletId] :nil :nil :nil];
        return;
    }

    [wrapper destroy];
    NSLog(@"[LslPlugin] Destroyed outlet: %@", outletId);
    [call resolve];
}

- (void)destroyAllOutlets:(CAPPluginCall *)call {
    [self destroyAllOutletsInternal];
    [call resolve];
}

#pragma mark - Utility Operations

- (void)getLocalClock:(CAPPluginCall *)call {
    double clock = lsl_local_clock();
    [call resolve:@{@"timestamp": @(clock)}];
}

- (void)getLibraryVersion:(CAPPluginCall *)call {
    int version = lsl_library_version();
    int major = version / 100;
    int minor = version % 100;
    NSString *versionStr = [NSString stringWithFormat:@"%d.%d", major, minor];
    [call resolve:@{@"version": versionStr}];
}

- (void)getProtocolVersion:(CAPPluginCall *)call {
    int version = lsl_protocol_version();
    [call resolve:@{@"version": @(version)}];
}

- (void)getDeviceIP:(CAPPluginCall *)call {
    NSString *ip = [self getWifiIPAddress];
    if (ip) {
        [call resolve:@{@"ip": ip}];
    } else {
        [call reject:@"Could not determine Wi-Fi IP address. Ensure Wi-Fi is connected." :nil :nil :nil];
    }
}

#pragma mark - Internal Helpers

- (void)destroyAllOutletsInternal {
    NSArray<LSLOutletWrapper *> *allOutlets;
    @synchronized (self.outlets) {
        allOutlets = [self.outlets.allValues copy];
        [self.outlets removeAllObjects];
    }

    for (LSLOutletWrapper *wrapper in allOutlets) {
        [wrapper destroy];
    }

    if (allOutlets.count > 0) {
        NSLog(@"[LslPlugin] Destroyed %lu outlet(s)", (unsigned long)allOutlets.count);
    }
}

- (void)pushChunkNative:(LSLOutletWrapper *)wrapper chunk:(NSArray *)chunk numSamples:(NSUInteger)numSamples {
    int cc = wrapper.channelCount;
    unsigned long totalElements = numSamples * cc;

    switch (wrapper.channelFormat) {
        case cft_float32: {
            float *flat = (float *)malloc(totalElements * sizeof(float));
            if (!flat) { NSLog(@"[LslPlugin] malloc failed in pushChunk (float32)"); return; }
            for (NSUInteger i = 0; i < numSamples; i++) {
                NSArray *sample = chunk[i];
                for (int j = 0; j < cc; j++) {
                    flat[i * cc + j] = (float)[sample[j] doubleValue];
                }
            }
            lsl_push_chunk_ft(wrapper.outlet, flat, totalElements, 0.0);
            free(flat);
            break;
        }
        case cft_double64: {
            double *flat = (double *)malloc(totalElements * sizeof(double));
            if (!flat) { NSLog(@"[LslPlugin] malloc failed in pushChunk (double64)"); return; }
            for (NSUInteger i = 0; i < numSamples; i++) {
                NSArray *sample = chunk[i];
                for (int j = 0; j < cc; j++) {
                    flat[i * cc + j] = [sample[j] doubleValue];
                }
            }
            lsl_push_chunk_dt(wrapper.outlet, flat, totalElements, 0.0);
            free(flat);
            break;
        }
        case cft_int32: {
            int32_t *flat = (int32_t *)malloc(totalElements * sizeof(int32_t));
            if (!flat) { NSLog(@"[LslPlugin] malloc failed in pushChunk (int32)"); return; }
            for (NSUInteger i = 0; i < numSamples; i++) {
                NSArray *sample = chunk[i];
                for (int j = 0; j < cc; j++) {
                    flat[i * cc + j] = (int32_t)[sample[j] doubleValue];
                }
            }
            lsl_push_chunk_it(wrapper.outlet, flat, totalElements, 0.0);
            free(flat);
            break;
        }
        case cft_int16: {
            int16_t *flat = (int16_t *)malloc(totalElements * sizeof(int16_t));
            if (!flat) { NSLog(@"[LslPlugin] malloc failed in pushChunk (int16)"); return; }
            for (NSUInteger i = 0; i < numSamples; i++) {
                NSArray *sample = chunk[i];
                for (int j = 0; j < cc; j++) {
                    flat[i * cc + j] = (int16_t)[sample[j] doubleValue];
                }
            }
            lsl_push_chunk_st(wrapper.outlet, flat, totalElements, 0.0);
            free(flat);
            break;
        }
        case cft_int8: {
            char *flat = (char *)malloc(totalElements * sizeof(char));
            if (!flat) { NSLog(@"[LslPlugin] malloc failed in pushChunk (int8)"); return; }
            for (NSUInteger i = 0; i < numSamples; i++) {
                NSArray *sample = chunk[i];
                for (int j = 0; j < cc; j++) {
                    flat[i * cc + j] = (char)[sample[j] doubleValue];
                }
            }
            lsl_push_chunk_ct(wrapper.outlet, flat, totalElements, 0.0);
            free(flat);
            break;
        }
        case cft_string: {
            for (NSUInteger i = 0; i < numSamples; i++) {
                [self pushSampleNative:wrapper data:chunk[i] timestamp:0.0];
            }
            break;
        }
        default:
            NSLog(@"[LslPlugin] Unsupported channel format for chunk: %d", wrapper.channelFormat);
            break;
    }
}

- (void)pushSampleNative:(LSLOutletWrapper *)wrapper data:(NSArray *)data timestamp:(double)timestamp {
    int count = wrapper.channelCount;

    switch (wrapper.channelFormat) {
        case cft_float32: {
            float *sample = (float *)malloc(count * sizeof(float));
            if (!sample) { NSLog(@"[LslPlugin] malloc failed in pushSample (float32)"); return; }
            for (int i = 0; i < count; i++) {
                sample[i] = (float)[data[i] doubleValue];
            }
            lsl_push_sample_ft(wrapper.outlet, sample, timestamp);
            free(sample);
            break;
        }
        case cft_double64: {
            double *sample = (double *)malloc(count * sizeof(double));
            if (!sample) { NSLog(@"[LslPlugin] malloc failed in pushSample (double64)"); return; }
            for (int i = 0; i < count; i++) {
                sample[i] = [data[i] doubleValue];
            }
            lsl_push_sample_dt(wrapper.outlet, sample, timestamp);
            free(sample);
            break;
        }
        case cft_int32: {
            int32_t *sample = (int32_t *)malloc(count * sizeof(int32_t));
            if (!sample) { NSLog(@"[LslPlugin] malloc failed in pushSample (int32)"); return; }
            for (int i = 0; i < count; i++) {
                sample[i] = (int32_t)[data[i] doubleValue];
            }
            lsl_push_sample_it(wrapper.outlet, sample, timestamp);
            free(sample);
            break;
        }
        case cft_int16: {
            int16_t *sample = (int16_t *)malloc(count * sizeof(int16_t));
            if (!sample) { NSLog(@"[LslPlugin] malloc failed in pushSample (int16)"); return; }
            for (int i = 0; i < count; i++) {
                sample[i] = (int16_t)[data[i] doubleValue];
            }
            lsl_push_sample_st(wrapper.outlet, sample, timestamp);
            free(sample);
            break;
        }
        case cft_int8: {
            char *sample = (char *)malloc(count * sizeof(char));
            if (!sample) { NSLog(@"[LslPlugin] malloc failed in pushSample (int8)"); return; }
            for (int i = 0; i < count; i++) {
                sample[i] = (char)[data[i] doubleValue];
            }
            lsl_push_sample_ct(wrapper.outlet, sample, timestamp);
            free(sample);
            break;
        }
        case cft_string: {
            const char **sample = (const char **)malloc(count * sizeof(const char *));
            if (!sample) { NSLog(@"[LslPlugin] malloc failed in pushSample (string)"); return; }
            for (int i = 0; i < count; i++) {
                sample[i] = [data[i] isKindOfClass:[NSString class]]
                    ? [data[i] UTF8String]
                    : [[data[i] description] UTF8String];
            }
            lsl_push_sample_strt(wrapper.outlet, sample, timestamp);
            free(sample);
            break;
        }
        default:
            NSLog(@"[LslPlugin] Unsupported channel format: %d", wrapper.channelFormat);
            break;
    }
}

- (NSString *)getWifiIPAddress {
    struct ifaddrs *interfaces = NULL;
    struct ifaddrs *addr = NULL;
    NSString *wifiAddress = nil;

    if (getifaddrs(&interfaces) != 0) {
        return nil;
    }

    addr = interfaces;
    while (addr != NULL) {
        if (addr->ifa_addr != NULL && addr->ifa_addr->sa_family == AF_INET) {
            NSString *ifName = [NSString stringWithUTF8String:addr->ifa_name];
            if ([ifName isEqualToString:@"en0"]) {
                struct sockaddr_in *sockAddr = (struct sockaddr_in *)addr->ifa_addr;
                char *ipStr = inet_ntoa(sockAddr->sin_addr);
                wifiAddress = [NSString stringWithUTF8String:ipStr];
                break;
            }
        }
        addr = addr->ifa_next;
    }

    freeifaddrs(interfaces);
    return wifiAddress;
}

@end

// Capacitor plugin registration — maps JS method names to ObjC selectors
CAP_PLUGIN(LslPlugin, "LSL",
    CAP_PLUGIN_METHOD(createOutlet, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(pushSample, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(pushChunk, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(hasConsumers, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(waitForConsumers, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(destroyOutlet, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(destroyAllOutlets, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(getLocalClock, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(getLibraryVersion, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(getProtocolVersion, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(getDeviceIP, CAPPluginReturnPromise);
)
