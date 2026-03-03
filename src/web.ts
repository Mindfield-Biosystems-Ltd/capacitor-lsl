import { WebPlugin } from '@capacitor/core';

import type {
  LSLPlugin,
  CreateOutletOptions,
  CreateOutletResult,
  PushSampleOptions,
  PushChunkOptions,
  OutletIdOptions,
  WaitForConsumersOptions,
  HasConsumersResult,
  LocalClockResult,
  LibraryVersionResult,
  ProtocolVersionResult,
  DeviceIPResult,
} from './definitions';

export class LSLWeb extends WebPlugin implements LSLPlugin {
  private unsupported(): never {
    throw this.unavailable(
      'LSL plugin requires native platform (Android or iOS). Web is not supported.',
    );
  }

  async createOutlet(_options: CreateOutletOptions): Promise<CreateOutletResult> {
    this.unsupported();
  }

  async pushSample(_options: PushSampleOptions): Promise<void> {
    this.unsupported();
  }

  async pushChunk(_options: PushChunkOptions): Promise<void> {
    this.unsupported();
  }

  async hasConsumers(_options: OutletIdOptions): Promise<HasConsumersResult> {
    this.unsupported();
  }

  async waitForConsumers(_options: WaitForConsumersOptions): Promise<HasConsumersResult> {
    this.unsupported();
  }

  async destroyOutlet(_options: OutletIdOptions): Promise<void> {
    this.unsupported();
  }

  async destroyAllOutlets(): Promise<void> {
    this.unsupported();
  }

  async getLocalClock(): Promise<LocalClockResult> {
    this.unsupported();
  }

  async getLibraryVersion(): Promise<LibraryVersionResult> {
    this.unsupported();
  }

  async getProtocolVersion(): Promise<ProtocolVersionResult> {
    this.unsupported();
  }

  async getDeviceIP(): Promise<DeviceIPResult> {
    this.unsupported();
  }
}
