package io.github.berial.flutter_bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * 经典蓝牙（BR/EDR）设备发现管理器。
 *
 * 使用 Android 传统的 [BluetoothAdapter.startDiscovery] API
 * 来发现经典蓝牙设备。
 */
class ClassicBluetoothManager(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val sendEvent: (Map<String, Any?>) -> Unit
) {
    private var isScanning = false
    private var activeFilter: ScanFilter? = null

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(
                        BluetoothDevice.EXTRA_DEVICE
                    ) ?: return
                    val rssi = intent.getShortExtra(
                        BluetoothDevice.EXTRA_RSSI,
                        Short.MIN_VALUE
                    ).toInt()

                    handleDeviceFound(device, rssi)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    isScanning = false
                }
            }
        }
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
        if (isScanning) return
        if (bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter?.cancelDiscovery()
        }

        activeFilter = filter
        isScanning = true

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(discoveryReceiver, filter)

        try {
            bluetoothAdapter?.startDiscovery()
        } catch (e: SecurityException) {
            isScanning = false
            android.util.Log.e("ClassicBT", "Discovery start failed: ${e.message}")
        }
    }

    fun stopScan() {
        if (!isScanning) return
        isScanning = false

        try {
            bluetoothAdapter?.cancelDiscovery()
            context.unregisterReceiver(discoveryReceiver)
        } catch (e: Exception) {
            // 已注销或安全异常
        }
    }
}
