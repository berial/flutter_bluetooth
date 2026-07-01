package io.github.berial.flutter_bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper

/**
 * 经典蓝牙（BR/EDR）设备发现管理器。
 *
 * 使用 Android 传统的 [BluetoothAdapter.startDiscovery] API
 * 来发现经典蓝牙设备。
 *
 * 扫描完成（系统 [BluetoothAdapter.ACTION_DISCOVERY_FINISHED]）或
 * 兜底超时（[scanTimeoutMs]）时会推送 `scanStopped` 事件到 Dart 端，
 * 避免 Dart 侧 `isScanningNow` 永远卡在 true。
 */
class ClassicBluetoothManager(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val sendEvent: (Map<String, Any?>) -> Unit
) {
    @Volatile
    private var isScanning = false
    private var activeFilter: ScanFilter? = null
    private val handler = Handler(Looper.getMainLooper())

    /** 扫描兜底超时（毫秒）。系统未发送 ACTION_DISCOVERY_FINISHED 时强制结束。 */
    private val scanTimeoutMs = 30_000L

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    } ?: return
                    val rssi = intent.getShortExtra(
                        BluetoothDevice.EXTRA_RSSI,
                        Short.MIN_VALUE
                    ).toInt()

                    handleDeviceFound(device, rssi)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    onScanFinished()
                }
            }
        }
    }

    /** 扫描完成处理：发 scanStopped 事件（系统自动结束或兜底超时触发）。 */
    private fun onScanFinished() {
        if (!isScanning) return
        isScanning = false
        handler.removeCallbacksAndMessages(null)
        // N11: 仅在系统自动结束时注销；用户主动 stopScan 已注销且 isScanning 已置 false
        try { context.unregisterReceiver(discoveryReceiver) } catch (_: Exception) {}
        sendEvent(mapOf(
            "type" to "scanStopped",
            "source" to "classic"
        ))
    }

    private fun handleDeviceFound(device: BluetoothDevice, rssi: Int) {
        val filter = activeFilter ?: return

        val name = try {
            device.name ?: "Unknown"
        } catch (e: SecurityException) {
            "Unknown"
        }
        val address = device.address ?: return

        // 应用过滤器
        if (filter.names.isNotEmpty() && name !in filter.names) return
        if (filter.remoteIds.isNotEmpty() && address !in filter.remoteIds) return
        if (filter.keywords.isNotEmpty()) {
            val matched = filter.keywords.any { keyword ->
                name.contains(keyword, ignoreCase = true)
            }
            if (!matched) return
        }

        val deviceMap = mapOf(
            "remoteId" to address,
            "platformName" to name,
            "advName" to name,
            "type" to "classic"
        )

        val advMap = mapOf(
            "advName" to name,
            "txPowerLevel" to null,
            "connectable" to true,
            "manufacturerData" to emptyMap<String, List<Int>>(),
            "serviceData" to emptyMap<String, List<Int>>(),
            "serviceUuids" to emptyList<String>()
        )

        sendEvent(mapOf(
            "type" to "scanResult",
            "device" to deviceMap,
            "advertisementData" to advMap,
            "rssi" to rssi,
            "timeStamp" to System.currentTimeMillis()
        ))
    }

    fun startScan(filter: ScanFilter) {
        if (isScanning) {
            sendEvent(mapOf(
                "type" to "scanError",
                "source" to "classic",
                "errorCode" to -1,
                "message" to "Classic scan already in progress"
            ))
            return
        }
        // R9/P5: cancelDiscovery 异步，若正在 discovering 用 postDelayed 非阻塞等待
        if (bluetoothAdapter?.isDiscovering == true) {
            try { bluetoothAdapter?.cancelDiscovery() } catch (_: SecurityException) {}
            activeFilter = filter
            isScanning = true
            handler.postDelayed({ doStartDiscovery(filter) }, 300)
        } else {
            doStartDiscovery(filter)
        }
    }

    /** 实际启动 discovery（registerReceiver + startDiscovery）。 */
    private fun doStartDiscovery(filter: ScanFilter) {
        // P5: 等待期间可能已 stopScan，避免竞态重新置 isScanning=true
        if (!isScanning) return
        activeFilter = filter
        isScanning = true

        val intentFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        try {
            context.registerReceiver(discoveryReceiver, intentFilter)
        } catch (e: Exception) {
            isScanning = false
            sendEvent(mapOf(
                "type" to "scanError",
                "source" to "classic",
                "errorCode" to -1,
                "message" to "Register receiver failed: ${e.message}"
            ))
            return
        }

        try {
            bluetoothAdapter?.startDiscovery()
            // 兜底超时：系统未发送 ACTION_DISCOVERY_FINISHED 时强制结束
            handler.postDelayed({ onScanFinished() }, scanTimeoutMs)
        } catch (e: SecurityException) {
            isScanning = false
            handler.removeCallbacksAndMessages(null)
            try { context.unregisterReceiver(discoveryReceiver) } catch (_: Exception) {}
            sendEvent(mapOf(
                "type" to "scanError",
                "source" to "classic",
                "errorCode" to -1,
                "message" to "Discovery start failed: ${e.message}"
            ))
        }
    }

    fun stopScan() {
        if (!isScanning) return
        // 先置 false，避免 cancelDiscovery 触发的 ACTION_DISCOVERY_FINISHED 重复发事件
        isScanning = false
        handler.removeCallbacksAndMessages(null)

        try {
            bluetoothAdapter?.cancelDiscovery()
        } catch (_: Exception) {}
        try {
            context.unregisterReceiver(discoveryReceiver)
        } catch (_: Exception) {}
        // N11: 主动停止也推 scanStopped 事件，与 BLE 侧一致
        sendEvent(mapOf(
            "type" to "scanStopped",
            "source" to "classic"
        ))
    }
}
