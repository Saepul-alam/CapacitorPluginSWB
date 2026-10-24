import { SwbPlugin } from 'swbplugin';

const logOutput = document.getElementById('log-output');
const statusDisplay = document.getElementById('status-display');
const bgStatusDisplay = document.getElementById('bg-status-display');
const imgPreview = document.getElementById('img-preview');
const videoPreview = document.getElementById('video-preview');

function log(title, data) {
  const time = new Date().toLocaleTimeString();
  const text = `[${time}] ${title}:\n${typeof data === 'object' ? JSON.stringify(data, null, 2) : data}\n\n`;
  logOutput.innerText = text + logOutput.innerText;
}

function updateStatusBadge(status) {
  if (!status) return;
  const isOk = status.usable;
  const className = isOk ? 'status-ok' : 'status-bad';
  statusDisplay.innerHTML = `
    <span class="status-badge ${className}">
      GPS: ${status.enabled ? 'ON' : 'OFF'} | Perm: ${status.granted ? 'OK' : 'DENIED'} | Fake: ${status.isMock ? 'YES' : 'NO'} | Usable: ${status.usable ? 'YES' : 'NO'}
    </span>
  `;
}

function updateBgBadge(running) {
  const className = running ? 'status-ok' : 'status-warn';
  bgStatusDisplay.innerHTML = `
    <span class="status-badge ${className}">
      BG Service: ${running ? 'RUNNING' : 'STOPPED'}
    </span>
  `;
}

// Event Listeners
try {
  SwbPlugin.addListener('locationStatusChanged', (status) => {
    log('Event: locationStatusChanged', status);
    updateStatusBadge(status);
  });

  SwbPlugin.addListener('location', (position) => {
    log('Event: location', position);
  });

  SwbPlugin.addListener('backgroundLocation', (position) => {
    log('Event: backgroundLocation', position);
  });

  SwbPlugin.addListener('backgroundUpload', (status) => {
    log('Event: backgroundUpload', status);
  });
} catch (e) {
  log('Listener setup error', e.message);
}

// 1. Check Status
document.getElementById('btn-status').addEventListener('click', async () => {
  try {
    const status = await SwbPlugin.checkLocationStatus();
    log('checkLocationStatus', status);
    updateStatusBadge(status);
  } catch (err) {
    log('checkLocationStatus error', err.message);
  }
});

// 2. Enforce Gating
document.getElementById('btn-enforce').addEventListener('click', async () => {
  try {
    const status = await SwbPlugin.enforceLocation({
      title: 'Akses Lokasi Diperlukan',
      messagePermissionDenied: 'Aplikasi Wahana SWB membutuhkan izin lokasi presisi.',
      messageGpsDisabled: 'GPS perangkat Anda nonaktif. Silakan aktifkan GPS.',
      messageMockDetected: 'Fake GPS terdeteksi. Matikan Fake GPS untuk melanjutkan.',
    });
    log('enforceLocation', status);
    updateStatusBadge(status);
  } catch (err) {
    log('enforceLocation error', err.message);
  }
});

// 3. Get Current Position
document.getElementById('btn-current').addEventListener('click', async () => {
  try {
    const pos = await SwbPlugin.getCurrentPosition({ enableHighAccuracy: true });
    log('getCurrentPosition', pos);
  } catch (err) {
    log('getCurrentPosition error', err.message);
  }
});

// 4. Watch Position
let watchId = null;
document.getElementById('btn-watch').addEventListener('click', async () => {
  try {
    if (watchId) {
      await SwbPlugin.stopWatch(watchId);
      log('stopWatch', watchId);
      watchId = null;
      document.getElementById('btn-watch').innerText = 'Watch Position';
    } else {
      watchId = await SwbPlugin.watchPosition(
        { interval: 3000, fastestInterval: 1000, minimumDistance: 0 },
        (pos, err) => {
          if (err) {
            log('watch callback error', err);
          } else {
            log('watch callback position', pos);
          }
        },
      );
      log('watchPosition started', watchId);
      document.getElementById('btn-watch').innerText = 'Stop Watch';
    }
  } catch (err) {
    log('watchPosition error', err.message);
  }
});

// 5. Start Background Location
document.getElementById('btn-start-bg').addEventListener('click', async () => {
  try {
    await SwbPlugin.startBackgroundLocation({
      interval: 3000,
      fastestInterval: 1000,
      minimumDistance: 0,
      enableHighAccuracy: true,
      uploadUrl: 'https://httpbin.org/post',
      uploadEnabled: true,
      allowInsecureHttp: false,
      notificationTitle: 'Wahana SWB Service',
      notificationText: 'Pelacakan lokasi aktif di latar belakang',
      restartOnBoot: true,
      historyRetentionDays: 7,
      historyMaxRecords: 10000,
      metadata: { userId: 'user-123', device: 'mobile-demo' },
    });
    log('startBackgroundLocation', 'Started successfully');
    updateBgBadge(true);
  } catch (err) {
    log('startBackgroundLocation error', err.message);
  }
});

// 6. Stop Background Location
document.getElementById('btn-stop-bg').addEventListener('click', async () => {
  try {
    await SwbPlugin.stopBackgroundLocation();
    log('stopBackgroundLocation', 'Stopped successfully');
    updateBgBadge(false);
  } catch (err) {
    log('stopBackgroundLocation error', err.message);
  }
});

// 7. Get History
document.getElementById('btn-history').addEventListener('click', async () => {
  try {
    const history = await SwbPlugin.getLocationHistory({ limit: 10, offset: 0 });
    log('getLocationHistory', history);
  } catch (err) {
    log('getLocationHistory error', err.message);
  }
});

// 8. Clear History
document.getElementById('btn-clear-history').addEventListener('click', async () => {
  try {
    await SwbPlugin.clearLocationHistory();
    log('clearLocationHistory', 'History cleared');
  } catch (err) {
    log('clearLocationHistory error', err.message);
  }
});

// 9. Pick Image
document.getElementById('btn-pick-img').addEventListener('click', async () => {
  try {
    const res = await SwbPlugin.pickImage({
      maxWidth: 1024,
      maxHeight: 1024,
      quality: 85,
      includeBase64: true,
    });
    log('pickImage', {
      path: res.path,
      uri: res.uri,
      fileName: res.fileName,
      mimeType: res.mimeType,
      size: res.size,
      width: res.width,
      height: res.height,
      hasBase64: !!res.base64,
    });

    if (res.base64) {
      imgPreview.src = `data:${res.mimeType};base64,${res.base64}`;
      imgPreview.style.display = 'block';
      videoPreview.style.display = 'none';
    } else if (res.uri) {
      imgPreview.src = res.uri;
      imgPreview.style.display = 'block';
      videoPreview.style.display = 'none';
    }
  } catch (err) {
    log('pickImage error', err.message);
  }
});

// 10. Take Photo
document.getElementById('btn-take-photo').addEventListener('click', async () => {
  try {
    const res = await SwbPlugin.takePhoto({
      maxWidth: 1024,
      maxHeight: 1024,
      quality: 85,
      includeBase64: true,
    });
    log('takePhoto', {
      path: res.path,
      uri: res.uri,
      fileName: res.fileName,
      mimeType: res.mimeType,
      size: res.size,
      width: res.width,
      height: res.height,
      hasBase64: !!res.base64,
    });

    if (res.base64) {
      imgPreview.src = `data:${res.mimeType};base64,${res.base64}`;
      imgPreview.style.display = 'block';
      videoPreview.style.display = 'none';
    } else if (res.uri) {
      imgPreview.src = res.uri;
      imgPreview.style.display = 'block';
      videoPreview.style.display = 'none';
    }
  } catch (err) {
    log('takePhoto error', err.message);
  }
});

// 11. Take Video
document.getElementById('btn-take-video').addEventListener('click', async () => {
  try {
    const res = await SwbPlugin.takeVideo({
      maxDuration: 15,
      quality: 'high',
    });
    log('takeVideo', res);

    if (res.uri) {
      videoPreview.src = res.uri;
      videoPreview.style.display = 'block';
      imgPreview.style.display = 'none';
    }
  } catch (err) {
    log('takeVideo error', err.message);
  }
});

// Clear Log
document.getElementById('btn-clear-log').addEventListener('click', () => {
  logOutput.innerText = '// Activity logs cleared.';
});

// Initial Status Check
SwbPlugin.checkLocationStatus()
  .then(updateStatusBadge)
  .catch(() => {});
SwbPlugin.isBackgroundLocationRunning()
  .then((res) => updateBgBadge(res.running))
  .catch(() => {});

