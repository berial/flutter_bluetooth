package com.example.flutter_bluetooth_example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var permissionResult: MethodChannel.Result? = null
    private lateinit var permissionChannel: MethodChannel

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        permissionChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger, "flutter_bluetooth_example/permissions"
        )
        permissionChannel.setMethodCallHandler { call, result ->
            if (call.method == "requestBluetoothPermissions") {
                requestBluetoothPermissions(result)
            } else {
                result.notImplemented()
            }
        }
    }

    private fun requestBluetoothPermissions(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            result.success(true)
            return
        }
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        when {
            missing.isEmpty() -> result.success(true)
            permissionResult != null -> result.error("PERMISSION_IN_PROGRESS", "Permission request pending", null)
            else -> {
                permissionResult = result
                requestPermissions(missing.toTypedArray(), BLUETOOTH_PERMISSIONS)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != BLUETOOTH_PERMISSIONS) return
        val result = permissionResult
        permissionResult = null
        result?.success(grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        permissionChannel.setMethodCallHandler(null)
        permissionResult?.success(false)
        permissionResult = null
        super.cleanUpFlutterEngine(flutterEngine)
    }

    companion object {
        private const val BLUETOOTH_PERMISSIONS = 0x4643
    }
}
