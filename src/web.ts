import { WebPlugin } from '@capacitor/core';

import type {
  BackgroundLocationOptions,
  CallbackID,
  EnforceLocationOptions,
  HistoryResult,
  ImageOptions,
  ImageResult,
  LocationRecord,
  LocationStatus,
  PermissionStatus,
  PermissionType,
  Position,
  PositionOptions,
  ProcessProtectionOptions,
  SwbPlugin,
  VideoOptions,
  VideoResult,
  WatchPositionCallback,
  WatchPositionOptions,
} from './definitions';

export class SwbPluginWeb extends WebPlugin implements SwbPlugin {
  private watches: { [id: string]: number } = {};
  private nextWatchId = 1;
  private readonly historyStorageKey = 'swb_location_history';

  // --- Foreground Location ---

  async getCurrentPosition(options?: PositionOptions): Promise<Position> {
    return new Promise((resolve, reject) => {
      if (!('geolocation' in navigator)) {
        reject(
          new Error(
            'Fitur Geolocation tidak didukung oleh browser ini. Silakan gunakan peramban modern seperti Google Chrome atau Safari.',
          ),
        );
        return;
      }

      navigator.geolocation.getCurrentPosition(
        pos => {
          const formatted = this.formatWebPosition(pos);
          this.saveHistoryRecord(formatted);
          resolve(formatted);
        },
        err => {
          let msg = `Gagal memperoleh lokasi: ${err.message}`;
          if (err.code === 1) {
            msg =
              'Izin akses lokasi belum diberikan. Silakan izinkan akses lokasi pada peramban Anda.';
          } else if (err.code === 2) {
            msg =
              'Sinyal lokasi tidak tersedia. Pastikan GPS perangkat Anda aktif dan terhubung ke jaringan internet.';
          } else if (err.code === 3) {
            msg =
              'Waktu pencarian koordinat lokasi habis (timeout). Silakan coba kembali beberapa saat lagi.';
          }
          reject(new Error(msg));
        },
        {
          enableHighAccuracy: options?.enableHighAccuracy ?? true,
          timeout: options?.timeout ?? 10000,
          maximumAge: options?.maximumAge ?? 0,
        },
      );
    });
  }

  async watchPosition(
    options: WatchPositionOptions,
    callback: WatchPositionCallback,
  ): Promise<CallbackID> {
    if (!('geolocation' in navigator)) {
      throw new Error('Geolocation is not supported by this browser');
    }

    const watchIdStr = (this.nextWatchId++).toString();
    const nativeWatchId = navigator.geolocation.watchPosition(
      pos => {
        const formatted = this.formatWebPosition(pos);
        this.saveHistoryRecord(formatted);
        callback(formatted);
        this.notifyListeners('location', formatted);
      },
      err => {
        callback(null, err);
      },
      {
        enableHighAccuracy: options?.enableHighAccuracy ?? true,
        timeout: options?.timeout ?? 10000,
        maximumAge: options?.maximumAge ?? 0,
      },
    );

    this.watches[watchIdStr] = nativeWatchId;
    return { id: watchIdStr };
  }

  async stopWatch(options: CallbackID): Promise<void> {
    if (this.watches[options.id] !== undefined) {
      navigator.geolocation.clearWatch(this.watches[options.id]);
      delete this.watches[options.id];
    }
  }

  async checkPermissions(): Promise<PermissionStatus> {
    if ('permissions' in navigator) {
      try {
        const status = await navigator.permissions.query({
          name: 'geolocation' as PermissionName,
        });
        const state = status.state as 'prompt' | 'granted' | 'denied';
        return {
          location: state,
          coarseLocation: state,
          message:
            state === 'granted'
              ? 'Izin akses lokasi telah diberikan.'
              : 'Izin akses lokasi belum diberikan pada peramban.',
          solution:
            state === 'granted'
              ? 'Peramban siap membaca lokasi.'
              : 'Klik ikon izin pada bilah URL peramban lalu pilih "Izinkan" akses lokasi.',
        };
      } catch {
        // Fallback
      }
    }
    return {
      location: 'prompt',
      coarseLocation: 'prompt',
      message: 'Izin akses lokasi belum ditentukan.',
      solution: 'Berikan izin saat peramban meminta konfirmasi akses lokasi.',
    };
  }

  async requestPermissions(options?: {
    permissions: PermissionType[];
  }): Promise<PermissionStatus> {
    void options;
    try {
      await this.getCurrentPosition({ timeout: 5000 });
      return {
        location: 'granted',
        coarseLocation: 'granted',
        message: 'Izin akses lokasi berhasil diberikan.',
        solution: 'Fitur lokasi siap digunakan.',
      };
    } catch {
      return this.checkPermissions();
    }
  }

  async isLocationEnabled(): Promise<{
    enabled: boolean;
    message?: string;
    solution?: string;
  }> {
    const enabled = 'geolocation' in navigator;
    return {
      enabled,
      message: enabled
        ? 'Fitur Geolocation didukung oleh peramban.'
        : 'Fitur Geolocation tidak didukung atau nonaktif pada peramban ini.',
      solution: enabled
        ? 'Peramban siap membaca koordinat lokasi.'
        : 'Gunakan peramban modern (Google Chrome, Safari, Firefox) dan aktifkan GPS perangkat.',
    };
  }

  async checkLocationStatus(): Promise<LocationStatus> {
    const isEnabled = 'geolocation' in navigator;
    const perm = await this.checkPermissions();
    const isGranted = perm.location === 'granted';
    const isMock = false;
    const usable = isEnabled && isGranted;

    let code: LocationStatus['code'] = 'OK';
    let message = 'Layanan lokasi aktif dan valid.';
    let solution = 'Aplikasi siap digunakan.';

    if (!isEnabled) {
      code = 'GPS_DISABLED';
      message =
        'Peramban tidak mendukung atau menonaktifkan fitur Geolocation.';
      solution =
        'Gunakan peramban modern yang mendukung fitur lokasi (Chrome, Firefox, Safari) dan aktifkan GPS perangkat.';
    } else if (!isGranted) {
      code = 'PERMISSION_DENIED';
      message = 'Izin akses lokasi belum diberikan pada browser.';
      solution =
        'Klik ikon perizinan pada bilah URL browser Anda lalu pilih "Izinkan" akses lokasi.';
    }

    return {
      enabled: isEnabled,
      granted: isGranted,
      isMock,
      usable,
      code,
      message,
      solution,
    };
  }

  async openLocationSettings(): Promise<void> {
    console.info('openLocationSettings is not supported on web.');
  }

  async openAppSettings(): Promise<void> {
    console.info('openAppSettings is not supported on web.');
  }

  async enforceLocation(
    options?: EnforceLocationOptions,
  ): Promise<LocationStatus> {
    await this.requestPermissions();
    const status = await this.checkLocationStatus();

    const existingOverlay = document.getElementById('swb-web-gating-overlay');
    if (!status.usable) {
      if (!existingOverlay) {
        const overlay = document.createElement('div');
        overlay.id = 'swb-web-gating-overlay';
        overlay.style.cssText =
          'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.9);z-index:999999;display:flex;align-items:center;justify-content:center;padding:20px;font-family:sans-serif;color:#fff;text-align:center;';

        const card = document.createElement('div');
        card.style.cssText =
          'background:#fff;color:#111;padding:24px;border-radius:12px;max-width:360px;width:100%;box-shadow:0 8px 24px rgba(0,0,0,0.3);';

        const title = document.createElement('h3');
        title.style.cssText =
          'color:#d32f2f;margin-bottom:12px;font-size:1.2rem;';
        title.innerText = options?.title || 'Akses Lokasi Wajib Aktif';

        const msg = document.createElement('p');
        msg.style.cssText =
          'font-size:0.9rem;color:#555;margin-bottom:20px;line-height:1.4;';
        msg.innerText =
          options?.messagePermissionDenied ||
          'Aplikasi tidak dapat digunakan tanpa izin akses lokasi yang aktif. Silakan izinkan akses lokasi di browser.';

        const btn = document.createElement('button');
        btn.style.cssText =
          'background:#0066cc;color:#fff;border:none;padding:10px 20px;border-radius:6px;font-weight:600;cursor:pointer;font-size:0.9rem;';
        btn.innerText = options?.actionButtonLabel || 'Coba Lagi';
        btn.onclick = () => {
          this.enforceLocation(options);
        };

        card.appendChild(title);
        card.appendChild(msg);
        card.appendChild(btn);
        overlay.appendChild(card);
        document.body.appendChild(overlay);
      }
    } else {
      if (existingOverlay) {
        existingOverlay.remove();
      }
    }

    this.notifyListeners('locationStatusChanged', status);
    return status;
  }

  async dismissGatingDialog(): Promise<void> {
    const existingOverlay = document.getElementById('swb-web-gating-overlay');
    if (existingOverlay) {
      existingOverlay.remove();
    }
  }

  // --- Background Location ---

  async startBackgroundLocation(
    options: BackgroundLocationOptions,
  ): Promise<void> {
    void options;
    throw new Error('Background location is not supported on web');
  }

  async stopBackgroundLocation(): Promise<void> {
    throw new Error('Background location is not supported on web');
  }

  async isBackgroundLocationRunning(): Promise<{
    running: boolean;
    message?: string;
    solution?: string;
  }> {
    return {
      running: false,
      message:
        'Pelacakan lokasi latar belakang tidak didukung pada peramban web.',
      solution:
        'Gunakan aplikasi Android atau iOS untuk mengaktifkan pelacakan di latar belakang.',
    };
  }

  // --- Local History (localStorage best-effort) ---

  async getLocationHistory(options?: {
    limit?: number;
    offset?: number;
    from?: number;
    to?: number;
  }): Promise<HistoryResult> {
    const raw = localStorage.getItem(this.historyStorageKey);
    let records: LocationRecord[] = raw ? JSON.parse(raw) : [];

    const fromTs = options?.from;
    if (fromTs !== undefined) {
      records = records.filter(r => r.position.timestamp >= fromTs);
    }
    const toTs = options?.to;
    if (toTs !== undefined) {
      records = records.filter(r => r.position.timestamp <= toTs);
    }

    const total = records.length;
    const pendingUpload = records.filter(r => !r.uploaded).length;

    const offset = options?.offset ?? 0;
    const limit = options?.limit ?? 100;
    const paginated = records.slice(offset, offset + limit);

    return {
      records: paginated,
      total,
      pendingUpload,
    };
  }

  async clearLocationHistory(): Promise<void> {
    localStorage.removeItem(this.historyStorageKey);
  }

  private saveHistoryRecord(pos: Position): void {
    try {
      const raw = localStorage.getItem(this.historyStorageKey);
      const records: LocationRecord[] = raw ? JSON.parse(raw) : [];
      const record: LocationRecord = {
        id: 'web-' + Date.now() + '-' + Math.random().toString(36).substr(2, 9),
        position: pos,
        isMock: false,
        uploaded: false,
        attempts: 0,
      };
      records.unshift(record);
      // Cap at 1000 records on web
      if (records.length > 1000) {
        records.length = 1000;
      }
      localStorage.setItem(this.historyStorageKey, JSON.stringify(records));
    } catch {
      // Ignore localStorage errors
    }
  }

  // --- Media ---

  async pickImage(options?: ImageOptions): Promise<ImageResult> {
    return this.selectMediaFile(false, 'image/*', options);
  }

  async takePhoto(options?: ImageOptions): Promise<ImageResult> {
    return this.selectMediaFile(true, 'image/*', options);
  }

  async takeVideo(options?: VideoOptions): Promise<VideoResult> {
    return new Promise((resolve, reject) => {
      const input = document.createElement('input');
      input.type = 'file';
      input.accept = 'video/*';
      input.setAttribute('capture', 'environment');
      input.style.display = 'none';

      let settled = false;

      const cleanup = () => {
        if (input.parentNode) {
          input.parentNode.removeChild(input);
        }
      };

      input.onchange = async () => {
        settled = true;
        const file = input.files?.[0];
        cleanup();
        if (!file) {
          reject(new Error('Perekaman video dibatalkan oleh pengguna.'));
          return;
        }

        const objectUrl = URL.createObjectURL(file);
        let duration: number | undefined;
        let width: number | undefined;
        let height: number | undefined;

        try {
          const videoMeta = await this.getVideoMetadata(objectUrl);
          duration = videoMeta.duration;
          width = videoMeta.width;
          height = videoMeta.height;
        } catch {
          // Metadata read failure is non-fatal
        }

        resolve({
          path: '',
          uri: objectUrl,
          fileName: options?.fileName || file.name,
          mimeType: file.type || 'video/mp4',
          size: file.size,
          duration,
          width,
          height,
        });
      };

      const handleFocus = () => {
        setTimeout(() => {
          if (!settled && (!input.files || input.files.length === 0)) {
            settled = true;
            window.removeEventListener('focus', handleFocus);
            cleanup();
            reject(new Error('User cancelled'));
          }
        }, 500);
      };

      window.addEventListener('focus', handleFocus, { once: true });

      document.body.appendChild(input);
      input.click();
    });
  }

  // --- Process Protection ---

  async enableProcessProtection(
    options?: ProcessProtectionOptions,
  ): Promise<void> {
    void options;
  }

  async disableProcessProtection(): Promise<void> {
    // no-op on web
  }

  async isProcessProtectionRunning(): Promise<{
    running: boolean;
    message?: string;
    solution?: string;
  }> {
    return {
      running: false,
      message: 'Proteksi proses keep-alive tidak didukung pada platform web.',
      solution:
        'Fitur proteksi proses dirancang khusus untuk platform Android.',
    };
  }

  async setProcessProtectionConfig(options: {
    autoProtectOnBackground: boolean;
  }): Promise<void> {
    void options;
  }

  private selectMediaFile(
    isCamera: boolean,
    accept: string,
    options?: ImageOptions,
  ): Promise<ImageResult> {
    return new Promise((resolve, reject) => {
      const input = document.createElement('input');
      input.type = 'file';
      input.accept = accept;
      if (isCamera) {
        input.setAttribute('capture', 'environment');
      }
      input.style.display = 'none';

      let settled = false;

      const cleanup = () => {
        if (input.parentNode) {
          input.parentNode.removeChild(input);
        }
      };

      input.onchange = async () => {
        settled = true;
        const file = input.files?.[0];
        cleanup();
        if (!file) {
          reject(
            new Error(
              isCamera
                ? 'Pengambilan foto dibatalkan oleh pengguna.'
                : 'Pemilihan gambar dibatalkan oleh pengguna.',
            ),
          );
          return;
        }

        try {
          const result = await this.processImageFile(file, options);
          resolve(result);
        } catch (err) {
          reject(err);
        }
      };

      const handleFocus = () => {
        setTimeout(() => {
          if (!settled && (!input.files || input.files.length === 0)) {
            settled = true;
            window.removeEventListener('focus', handleFocus);
            cleanup();
            reject(new Error('User cancelled'));
          }
        }, 500);
      };

      window.addEventListener('focus', handleFocus, { once: true });

      document.body.appendChild(input);
      input.click();
    });
  }

  private async processImageFile(
    file: File,
    options?: ImageOptions,
  ): Promise<ImageResult> {
    const objectUrl = URL.createObjectURL(file);
    const includeBase64 = options?.includeBase64 ?? true;
    const quality = (options?.quality ?? 90) / 100;

    return new Promise((resolve, reject) => {
      const img = new Image();
      img.onload = () => {
        let width = img.naturalWidth;
        let height = img.naturalHeight;

        const maxWidth = options?.maxWidth;
        const maxHeight = options?.maxHeight;

        if (maxWidth && width > maxWidth) {
          height = Math.round((height * maxWidth) / width);
          width = maxWidth;
        }
        if (maxHeight && height > maxHeight) {
          width = Math.round((width * maxHeight) / height);
          height = maxHeight;
        }

        const canvas = document.createElement('canvas');
        canvas.width = width;
        canvas.height = height;
        const ctx = canvas.getContext('2d');
        if (!ctx) {
          URL.revokeObjectURL(objectUrl);
          reject(new Error('Failed to create canvas context'));
          return;
        }

        ctx.drawImage(img, 0, 0, width, height);

        let base64: string | undefined;
        let mimeType = file.type || 'image/jpeg';
        if (
          mimeType !== 'image/jpeg' &&
          mimeType !== 'image/png' &&
          mimeType !== 'image/webp'
        ) {
          mimeType = 'image/jpeg';
        }

        if (includeBase64) {
          const dataUrl = canvas.toDataURL(mimeType, quality);
          const commaIndex = dataUrl.indexOf(',');
          base64 =
            commaIndex >= 0 ? dataUrl.substring(commaIndex + 1) : dataUrl;
        }

        resolve({
          path: '',
          uri: objectUrl,
          fileName: options?.fileName || file.name,
          mimeType,
          size: file.size,
          width,
          height,
          base64,
        });
      };

      img.onerror = () => {
        URL.revokeObjectURL(objectUrl);
        reject(new Error('Failed to load image file'));
      };

      img.src = objectUrl;
    });
  }

  private getVideoMetadata(
    url: string,
  ): Promise<{ duration: number; width: number; height: number }> {
    return new Promise((resolve, reject) => {
      const video = document.createElement('video');
      video.preload = 'metadata';
      video.onloadedmetadata = () => {
        resolve({
          duration: Math.round(video.duration),
          width: video.videoWidth,
          height: video.videoHeight,
        });
      };
      video.onerror = () => {
        reject(new Error('Failed to read video metadata'));
      };
      video.src = url;
    });
  }

  private formatWebPosition(pos: any): Position {
    return {
      timestamp: pos.timestamp,
      coords: {
        latitude: pos.coords.latitude,
        longitude: pos.coords.longitude,
        accuracy: pos.coords.accuracy,
        altitude: pos.coords.altitude,
        altitudeAccuracy: pos.coords.altitudeAccuracy,
        heading: pos.coords.heading,
        speed: pos.coords.speed,
        isMock: false,
      },
    };
  }
}
