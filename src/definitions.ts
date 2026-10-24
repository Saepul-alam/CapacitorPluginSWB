import type { PluginListenerHandle } from '@capacitor/core';

export type PermissionState =
  | 'prompt'
  | 'prompt-with-rationale'
  | 'granted'
  | 'denied';
export type PermissionType =
  | 'location'
  | 'coarseLocation'
  | 'backgroundLocation'
  | 'camera'
  | 'microphone';

export interface LocationCoordinates {
  latitude: number;
  longitude: number;
  accuracy: number;
  altitude: number | null;
  altitudeAccuracy: number | null;
  heading: number | null;
  speed: number | null;
  isMock: boolean;
}

export interface Position {
  timestamp: number;
  coords: LocationCoordinates;
}

export interface PositionOptions {
  enableHighAccuracy?: boolean;
  timeout?: number;
  maximumAge?: number;
}

export interface WatchPositionOptions extends PositionOptions {
  interval?: number;
  fastestInterval?: number;
  minimumDistance?: number;
}

export interface CallbackID {
  id: string;
}

export type WatchPositionCallback = (
  position: Position | null,
  err?: any,
) => void;

export interface PermissionStatus {
  location: PermissionState;
  coarseLocation?: PermissionState;
  backgroundLocation?: PermissionState;
  camera?: PermissionState;
  microphone?: PermissionState;
  message?: string;
  solution?: string;
}

export type LocationStatusCode =
  | 'OK'
  | 'PERMISSION_DENIED'
  | 'COARSE_ONLY'
  | 'GPS_DISABLED'
  | 'MOCK_DETECTED'
  | 'SIGNAL_TIMEOUT';

export interface LocationStatus {
  enabled: boolean;
  granted: boolean;
  isMock: boolean;
  usable: boolean;
  code?: LocationStatusCode;
  message?: string;
  solution?: string;
}

export interface EnforceLocationOptions {
  title?: string;
  messagePermissionDenied?: string;
  messageGpsDisabled?: string;
  messageMockDetected?: string;
  actionButtonLabel?: string;
  exitButtonLabel?: string;
}

export interface BackgroundLocationOptions extends WatchPositionOptions {
  uploadUrl?: string;
  headers?: Record<string, string>;
  uploadEnabled?: boolean;
  metadata?: Record<string, unknown>;
  allowInsecureHttp?: boolean;
  notificationTitle?: string;
  notificationText?: string;
  restartOnBoot?: boolean;
  significantChanges?: boolean;
  historyRetentionDays?: number;
  historyMaxRecords?: number;
}

export interface LocationRecord {
  id: string;
  position: Position;
  isMock: boolean;
  uploaded: boolean;
  uploadedAt?: number;
  attempts: number;
}

export interface HistoryResult {
  records: LocationRecord[];
  total: number;
  pendingUpload: number;
}

export interface UploadStatus {
  uploaded: boolean;
  pending: number;
  error?: string;
}

export interface ImageOptions {
  includeBase64?: boolean;
  maxWidth?: number;
  maxHeight?: number;
  quality?: number;
  fileName?: string;
}

export interface VideoOptions {
  maxDuration?: number;
  quality?: 'low' | 'medium' | 'high';
  fileName?: string;
}

export interface ImageResult {
  path: string;
  uri?: string;
  fileName: string;
  mimeType: string;
  size: number;
  width?: number;
  height?: number;
  base64?: string;
}

export interface VideoResult {
  path: string;
  uri?: string;
  fileName: string;
  mimeType: string;
  size: number;
  duration?: number;
  width?: number;
  height?: number;
}

export interface ProcessProtectionOptions {
  title?: string;
  text?: string;
  autoProtectOnBackground?: boolean;
}

export interface SwbPlugin {
  // Location foreground
  getCurrentPosition(options?: PositionOptions): Promise<Position>;
  watchPosition(
    options: WatchPositionOptions,
    callback: WatchPositionCallback,
  ): Promise<CallbackID>;
  stopWatch(options: CallbackID): Promise<void>;
  checkLocationStatus(): Promise<LocationStatus>;
  isLocationEnabled(): Promise<{
    enabled: boolean;
    message?: string;
    solution?: string;
  }>;
  enforceLocation(options?: EnforceLocationOptions): Promise<LocationStatus>;
  dismissGatingDialog(): Promise<void>;
  checkPermissions(): Promise<PermissionStatus>;
  requestPermissions(options?: {
    permissions: PermissionType[];
  }): Promise<PermissionStatus>;
  openLocationSettings(): Promise<void>;
  openAppSettings(): Promise<void>;

  // Background + history + upload
  startBackgroundLocation(options: BackgroundLocationOptions): Promise<void>;
  stopBackgroundLocation(): Promise<void>;
  isBackgroundLocationRunning(): Promise<{
    running: boolean;
    message?: string;
    solution?: string;
  }>;
  getLocationHistory(options?: {
    limit?: number;
    offset?: number;
    from?: number;
    to?: number;
  }): Promise<HistoryResult>;
  clearLocationHistory(): Promise<void>;

  // Media
  pickImage(options?: ImageOptions): Promise<ImageResult>;
  takePhoto(options?: ImageOptions): Promise<ImageResult>;
  takeVideo(options?: VideoOptions): Promise<VideoResult>;

  // Process protection / Keep-alive
  enableProcessProtection(options?: ProcessProtectionOptions): Promise<void>;
  disableProcessProtection(): Promise<void>;
  isProcessProtectionRunning(): Promise<{
    running: boolean;
    message?: string;
    solution?: string;
  }>;
  setProcessProtectionConfig(options: {
    autoProtectOnBackground: boolean;
  }): Promise<void>;

  // Events
  addListener(
    eventName: 'location',
    listenerFunc: (position: Position) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'locationStatusChanged',
    listenerFunc: (status: LocationStatus) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'backgroundLocation',
    listenerFunc: (position: Position) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'backgroundUpload',
    listenerFunc: (status: UploadStatus) => void,
  ): Promise<PluginListenerHandle>;
}
