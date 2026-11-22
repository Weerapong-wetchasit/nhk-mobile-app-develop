package com.psc.nhkcoop

import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine

// FlutterFragmentActivity (not FlutterActivity) is required by local_auth_android,
// which needs a FragmentActivity host to show the biometric prompt.
class MainActivity : FlutterFragmentActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // Register the `nhk/device_key` channel (EC keystore for DeviceAuth PIN).
        DeviceKeyChannel(flutterEngine.dartExecutor.binaryMessenger).register()
    }
}
