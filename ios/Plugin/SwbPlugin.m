#import <Foundation/Foundation.h>
#import <Capacitor/Capacitor.h>

// Define the plugin using the CAP_PLUGIN Macro, and
// each method the plugin supports using the CAP_PLUGIN_METHOD macro.
CAP_PLUGIN(SwbPlugin, "SwbPlugin",
           CAP_PLUGIN_METHOD(getCurrentPosition, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(watchPosition, CAPPluginReturnCallback);
           CAP_PLUGIN_METHOD(stopWatch, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(checkLocationStatus, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(isLocationEnabled, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(enforceLocation, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(dismissGatingDialog, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(checkPermissions, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(requestPermissions, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(openLocationSettings, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(openAppSettings, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(startBackgroundLocation, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(stopBackgroundLocation, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(isBackgroundLocationRunning, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(getLocationHistory, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(clearLocationHistory, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(pickImage, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(takePhoto, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(takeVideo, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(enableProcessProtection, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(disableProcessProtection, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(isProcessProtectionRunning, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(setProcessProtectionConfig, CAPPluginReturnPromise);
)
