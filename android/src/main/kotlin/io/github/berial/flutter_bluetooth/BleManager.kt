package io.github.berial.flutter_bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter as LeScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 管理 BLE 扫描和 GATT 操作。
 */
class BleManager(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val bluetoothManager: BluetoothManager,
    private val sendEvent: (Map<String, Any?>) -> Unit,
    private val onDeviceDisconnected: (String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var leScanner: BluetoothLeScanner? = null

    // 活跃的 GATT 连接：remoteId -> BluetoothGatt
    private val gattMap = ConcurrentHashMap<String, BluetoothGatt>()

    // 等待 GATT 回调的待处理操作
    private val pendingCallbacks = ConcurrentHashMap<String, MethodChannel.Result>()

    // 每个设备当前协商的 MTU（默认 23 → 最大有效载荷 20 字节）
    private val mtuMap = ConcurrentHashMap<String, Int>()
    private val DEFAULT_MTU = 23

    // 等待 MTU 协商完成的连接请求
    private data class PendingConnect(
        val result: MethodChannel.Result,
        val onConnected: (String, BluetoothGatt?) -> Unit,
        val desiredMtu: Int? = null
    )
    private val pendingConnects = ConcurrentHashMap<String, PendingConnect>()

    // 分包写入队列：remoteId_charUuid -> 剩余分包
    private data class ChunkedWrite(
        val chunks: MutableList<ByteArray>,
        val serviceUuid: String,
        val characteristicUuid: String,
        val withoutResponse: Boolean,
        val result: MethodChannel.Result
    )
    private val pendingChunkedWrites = ConcurrentHashMap<String, ChunkedWrite>()

    // ─── BLE 扫描 ──────────────────────────────────────────────────────

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val record = result.scanRecord ?: return

            val name = try {
                record.deviceName ?: device.name ?: "Unknown"
            } catch (e: SecurityException) {
                "Unknown"
            }

            val serviceUuids = record.serviceUuids?.map {
                FlutterBluetoothPlugin.uuidToStandardString(it.uuid)
            } ?: emptyList()

            val manufacturerData = mutableMapOf<Int, List<Int>>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val msd = record.manufacturerSpecificData
                if (msd != null) {
                    for (i in 0 until msd.size()) {
                        val id = msd.keyAt(i)
                        val data = msd.valueAt(i)
                        manufacturerData[id] = data.toList().map { it.toInt() and 0xFF }
                    }
                }
            }

            val serviceData = mutableMapOf<String, List<Int>>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                record.serviceData?.forEach { (uuid, data) ->
                    serviceData[FlutterBluetoothPlugin.uuidToStandardString(
                        uuid.uuid)] = data.toList().map { it.toInt() and 0xFF }
                }
            }

            val deviceMap = mapOf(
                "remoteId" to (device.address ?: ""),
                "platformName" to name,
                "advName" to name,
                "type" to "ble"
            )

            val advMap = mapOf(
                "advName" to name,
                "txPowerLevel" to (record.txPowerLevel ?: -128),
                "connectable" to result.isConnectable,
                "manufacturerData" to manufacturerData,
                "serviceData" to serviceData,
                "serviceUuids" to serviceUuids
            )

            sendEvent(mapOf(
                "type" to "scanResult",
                "device" to deviceMap,
                "advertisementData" to advMap,
                "rssi" to result.rssi,
                "timeStamp" to System.currentTimeMillis()
            ))
        }

        override fun onScanFailed(errorCode: Int) {
            android.util.Log.e("BleManager", "BLE scan failed with error: $errorCode")
            isScanning = false
            sendEvent(mapOf(
                "type" to "scanError",
                "errorCode" to errorCode
            ))
        }
    }

    private var isScanning = false

    fun startScan(scanModeStr: String, filter: ScanFilter) {
        if (isScanning) return

        leScanner = bluetoothAdapter?.bluetoothLeScanner
        if (leScanner == null) {
            android.util.Log.w("BleManager", "BLE scanner not available")
            return
        }

        val scanMode = when (scanModeStr) {
            "lowPower" -> ScanSettings.SCAN_MODE_LOW_POWER
            "balanced" -> ScanSettings.SCAN_MODE_BALANCED
            "lowLatency" -> ScanSettings.SCAN_MODE_LOW_LATENCY
            else -> ScanSettings.SCAN_MODE_LOW_LATENCY
        }

        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .build()

        // 构建原生扫描过滤器
        val nativeFilters = mutableListOf<LeScanFilter>()
        if (filter.serviceUuids.isNotEmpty()) {
            for (uuidStr in filter.serviceUuids) {
                try {
                    val uuid = UUID.fromString(uuidStr)
                    nativeFilters.add(
                        LeScanFilter.Builder()
                            .setServiceUuid(ParcelUuid(uuid))
                            .build()
                    )
                } catch (_: IllegalArgumentException) {}
            }
        }

        isScanning = true
        try {
            leScanner?.startScan(
                if (nativeFilters.isEmpty()) null else nativeFilters,
                settings,
                bleScanCallback
            )
        } catch (e: SecurityException) {
            isScanning = false
            android.util.Log.e("BleManager", "Bluetooth scan permission denied: ${e.message}")
        }
    }

    fun stopScan() {
        if (!isScanning) return
        isScanning = false
        try {
            leScanner?.stopScan(bleScanCallback)
        } catch (e: SecurityException) {}
    }

    // ─── 连接管理 ────────────────────────────────────────────────────────

    fun connect(device: BluetoothDevice, autoConnect: Boolean, result: MethodChannel.Result,
                onConnected: (String, BluetoothGatt?) -> Unit, desiredMtu: Int? = null) {
        scope.launch {
            try {
                val gatt = device.connectGatt(context, autoConnect, gattCallback)
                gattMap[device.address] = gatt

                if (desiredMtu != null && desiredMtu > DEFAULT_MTU) {
                    // 延迟连接结果，等待 MTU 协商完成
                    pendingConnects[device.address] = PendingConnect(result, onConnected, desiredMtu)
                } else {
                    // 无需请求 MTU，立即报告已连接
                    pendingCallbacks["connect_${device.address}"] = result
                }
            } catch (e: SecurityException) {
                result.error("SECURITY", "Permission denied: ${e.message}", null)
            } catch (e: Exception) {
                result.error("CONNECT_FAILED", e.message, null)
            }
        }
    }

    fun disconnect(remoteId: String) {
        val gatt = gattMap[remoteId]
        if (gatt != null) {
            try {
                gatt.disconnect()
                // 不在此处调用 close() — 由 onConnectionStateChange
                // 收到 STATE_DISCONNECTED 后执行，避免竞态条件
            } catch (e: SecurityException) {}
        }
        mtuMap.remove(remoteId)
        pendingConnects.remove(remoteId)
        // 移除该设备的待处理分包写入
        pendingChunkedWrites.keys.filter { it.startsWith("${remoteId}_") }
            .forEach { pendingChunkedWrites.remove(it) }
        // 通知 Dart 侧 MTU 已重置
        sendEvent(mapOf(
            "type" to "mtuChanged",
            "remoteId" to remoteId,
            "mtu" to DEFAULT_MTU
        ))
    }

    // ─── GATT 操作 ─────────────────────────────────────────────────────

    fun discoverServices(remoteId: String, result: MethodChannel.Result) {
        val gatt = gattMap[remoteId]
        if (gatt == null) {
            result.error("NOT_CONNECTED", "Device not connected", null)
            return
        }
        pendingCallbacks["discover_$remoteId"] = result
        try {
            gatt.discoverServices()
        } catch (e: SecurityException) {
            result.error("SECURITY", e.message, null)
        }
    }

    fun readRssi(remoteId: String, result: MethodChannel.Result) {
        val gatt = gattMap[remoteId]
        if (gatt == null) {
            result.error("NOT_CONNECTED", "Device not connected", null)
            return
        }
        pendingCallbacks["readRssi_$remoteId"] = result
        try {
            gatt.readRemoteRssi()
        } catch (e: SecurityException) {
            result.error("SECURITY", e.message, null)
        }
    }

    fun requestMtu(remoteId: String, desiredMtu: Int, result: MethodChannel.Result) {
        val gatt = gattMap[remoteId]
        if (gatt == null) {
            result.error("NOT_CONNECTED", "Device not connected", null)
            return
        }
        pendingCallbacks["mtu_$remoteId"] = result
        try {
            gatt.requestMtu(desiredMtu)
        } catch (e: SecurityException) {
            result.error("SECURITY", e.message, null)
        }
    }

    fun readCharacteristic(remoteId: String, serviceUuid: String,
                           characteristicUuid: String, result: MethodChannel.Result) {
        val gatt = gattMap[remoteId]
        if (gatt == null) {
            result.error("NOT_CONNECTED", "Device not connected", null)
            return
        }
        val characteristic = findCharacteristic(gatt, serviceUuid, characteristicUuid)
        if (characteristic == null) {
            result.error("CHAR_NOT_FOUND", "Characteristic not found", null)
            return
        }
        pendingCallbacks["read_${remoteId}_$characteristicUuid"] = result
        try {
            gatt.readCharacteristic(characteristic)
        } catch (e: SecurityException) {
            result.error("SECURITY", e.message, null)
        }
    }

    fun writeCharacteristic(remoteId: String, serviceUuid: String,
                            characteristicUuid: String, value: ByteArray,
                            withoutResponse: Boolean, result: MethodChannel.Result) {
        val gatt = gattMap[remoteId]
        if (gatt == null) {
            result.error("NOT_CONNECTED", "Device not connected", null)
            return
        }
        val characteristic = findCharacteristic(gatt, serviceUuid, characteristicUuid)
        if (characteristic == null) {
            result.error("CHAR_NOT_FOUND", "Characteristic not found", null)
            return
        }

        // 根据协商的 MTU 计算最大有效载荷
        val currentMtu = mtuMap[remoteId] ?: DEFAULT_MTU
        val maxPayload = currentMtu - 3 // ATT 头部 = 3 字节

        if (value.size <= maxPayload) {
            // 数据可放入单个包 — 直接写入
            writeSingleChunk(gatt, characteristic, value, withoutResponse, remoteId, characteristicUuid, result)
        } else {
            // 拆分为多个分包并顺序写入
            val chunks = value.toList().chunked(maxPayload).map { it.toByteArray() }.toMutableList()
            val firstChunk = chunks.removeAt(0)
            val writeKey = "${remoteId}_${FlutterBluetoothPlugin.uuidToStandardString(characteristic.uuid)}"

            // 将剩余分包加入队列
            pendingChunkedWrites[writeKey] = ChunkedWrite(
                chunks = chunks,
                serviceUuid = serviceUuid,
                characteristicUuid = characteristicUuid,
                withoutResponse = withoutResponse,
                result = result
            )

            // 写入第一个分包
            writeSingleChunk(gatt, characteristic, firstChunk, withoutResponse, remoteId, characteristicUuid, null)
        }
    }

    private fun writeSingleChunk(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        withoutResponse: Boolean,
        remoteId: String,
        characteristicUuid: String,
        result: MethodChannel.Result?
    ) {
        characteristic.value = value
        characteristic.writeType = if (withoutResponse)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        if (withoutResponse) {
            try {
                gatt.writeCharacteristic(characteristic)
                result?.success(null)
            } catch (e: SecurityException) {
                result?.error("SECURITY", e.message, null)
            }
        } else {
            if (result != null) {
                pendingCallbacks["write_${remoteId}_$characteristicUuid"] = result
            }
            try {
                gatt.writeCharacteristic(characteristic)
            } catch (e: SecurityException) {
                result?.error("SECURITY", e.message, null)
            }
        }
    }

    fun setNotifyValue(remoteId: String, serviceUuid: String,
                       characteristicUuid: String, enable: Boolean,
                       result: MethodChannel.Result) {
        val gatt = gattMap[remoteId]
        if (gatt == null) {
            result.error("NOT_CONNECTED", "Device not connected", null)
            return
        }
        val characteristic = findCharacteristic(gatt, serviceUuid, characteristicUuid)
        if (characteristic == null) {
            result.error("CHAR_NOT_FOUND", "Characteristic not found", null)
            return
        }

        // 启用本地通知
        try {
            val descriptor = characteristic.getDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            )
            if (descriptor != null) {
                descriptor.value = if (enable)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }

            gatt.setCharacteristicNotification(characteristic, enable)

            if (descriptor != null) {
                pendingCallbacks["notify_${remoteId}_$characteristicUuid"] = result
                gatt.writeDescriptor(descriptor)
            } else {
                result.success(enable)
            }
        } catch (e: SecurityException) {
            result.error("SECURITY", e.message, null)
        }
    }

    fun readDescriptor(remoteId: String, serviceUuid: String,
                       characteristicUuid: String, descriptorUuid: String,
                       result: MethodChannel.Result) {
        val gatt = gattMap[remoteId]
        if (gatt == null) {
            result.error("NOT_CONNECTED", "Device not connected", null)
            return
        }
        val descriptor = findDescriptor(gatt, serviceUuid, characteristicUuid, descriptorUuid)
        if (descriptor == null) {
            result.error("DESC_NOT_FOUND", "Descriptor not found", null)
            return
        }
        pendingCallbacks["readDesc_${remoteId}_$descriptorUuid"] = result
        try {
            gatt.readDescriptor(descriptor)
        } catch (e: SecurityException) {
            result.error("SECURITY", e.message, null)
        }
    }

    fun writeDescriptor(remoteId: String, serviceUuid: String,
                        characteristicUuid: String, descriptorUuid: String,
                        value: ByteArray, result: MethodChannel.Result) {
        val gatt = gattMap[remoteId]
        if (gatt == null) {
            result.error("NOT_CONNECTED", "Device not connected", null)
            return
        }
        val descriptor = findDescriptor(gatt, serviceUuid, characteristicUuid, descriptorUuid)
        if (descriptor == null) {
            result.error("DESC_NOT_FOUND", "Descriptor not found", null)
            return
        }
        descriptor.value = value
        pendingCallbacks["writeDesc_${remoteId}_$descriptorUuid"] = result
        try {
            gatt.writeDescriptor(descriptor)
        } catch (e: SecurityException) {
            result.error("SECURITY", e.message, null)
        }
    }

    fun requestConnectionPriority(remoteId: String, priority: Int, result: MethodChannel.Result) {
        val gatt = gattMap[remoteId]
        if (gatt == null) {
            result.error("NOT_CONNECTED", "Device not connected", null)
            return
        }
        val connPriority = when (priority) {
            0 -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
            1 -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
            2 -> BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
            else -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        }
        val success = try {
            gatt.requestConnectionPriority(connPriority)
        } catch (e: SecurityException) {
            false
        }
        result.success(success)
    }

    fun clearGattCache(remoteId: String) {
        val gatt = gattMap[remoteId] ?: return
        try {
            val method = gatt.javaClass.getMethod("refresh")
            method.invoke(gatt)
        } catch (_: Exception) {}
    }

    // ─── GATT 回调 ──────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val address = gatt.device.address
                val pendingConnect = pendingConnects.remove(address)

                if (pendingConnect != null) {
                    // 连接时请求了 MTU — 现在发起请求
                    try {
                        gatt.requestMtu(pendingConnect.desiredMtu ?: 512)
                        // 保存连接信息供 onMtuChanged 回调使用
                        pendingConnects["mtu_pending_$address"] = pendingConnect
                    } catch (e: SecurityException) {
                        // MTU 请求失败，仍然报告已连接
                        pendingConnect.result.success(null)
                        pendingConnect.onConnected(address, gatt)
                    }
                } else {
                    val key = "connect_$address"
                    pendingCallbacks.remove(key)?.success(null)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val address = gatt.device.address
                gattMap.remove(address)
                mtuMap.remove(address)
                pendingConnects.remove(address)
                pendingConnects.remove("mtu_pending_$address")
                gatt.close()
                onDeviceDisconnected(address)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val key = "discover_${gatt.device.address}"
            val result = pendingCallbacks.remove(key)

            if (status == BluetoothGatt.GATT_SUCCESS) {
                val services = gatt.services.map { serviceToMap(gatt.device.address, it) }
                result?.success(services)
            } else {
                result?.error("GATT_ERROR", "Service discovery failed: $status", null)
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            val key = "readRssi_${gatt.device.address}"
            val result = pendingCallbacks.remove(key)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                result?.success(rssi)
            } else {
                result?.error("GATT_ERROR", "Read RSSI failed: $status", null)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val address = gatt.device.address
            val key = "mtu_$address"
            val result = pendingCallbacks.remove(key)

            if (status == BluetoothGatt.GATT_SUCCESS) {
                // 记录协商后的 MTU
                mtuMap[address] = mtu

                sendEvent(mapOf(
                    "type" to "mtuChanged",
                    "remoteId" to address,
                    "mtu" to mtu
                ))
                result?.success(mtu)

                // 如果是 connect() 发起的自动 MTU 请求，完成连接
                val pendingConnect = pendingConnects.remove("mtu_pending_$address")
                if (pendingConnect != null) {
                    pendingConnect.result.success(null)
                    pendingConnect.onConnected(address, gatt)
                }
            } else {
                result?.error("GATT_ERROR", "MTU change failed: $status", null)

                // MTU 协商失败 — 仍以默认 MTU 报告已连接
                val pendingConnect = pendingConnects.remove("mtu_pending_$address")
                if (pendingConnect != null) {
                    pendingConnect.result.success(null)
                    pendingConnect.onConnected(address, gatt)
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
                                          status: Int) {
            val key = "read_${gatt.device.address}_${FlutterBluetoothPlugin.uuidToStandardString(characteristic.uuid)}"
            val result = pendingCallbacks.remove(key)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val value = characteristic.value?.toList() ?: emptyList<Int>()
                result?.success(value)
            } else {
                result?.error("GATT_ERROR", "Read characteristic failed: $status", null)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
                                           status: Int) {
            val charUuid = FlutterBluetoothPlugin.uuidToStandardString(characteristic.uuid)
            val writeKey = "${gatt.device.address}_$charUuid"

            // 检查是否有分包写入队列
            val chunkedWrite = pendingChunkedWrites[writeKey]
            if (chunkedWrite != null && chunkedWrite.chunks.isNotEmpty() && status == BluetoothGatt.GATT_SUCCESS) {
                // 发送下一个分包
                val nextChunk = chunkedWrite.chunks.removeAt(0)
                val nextChar = findCharacteristic(gatt, chunkedWrite.serviceUuid, chunkedWrite.characteristicUuid)
                if (nextChar != null) {
                    writeSingleChunk(gatt, nextChar, nextChunk, chunkedWrite.withoutResponse,
                        gatt.device.address, chunkedWrite.characteristicUuid, null)
                } else {
                    // 特征不可用，清理队列并报错
                    pendingChunkedWrites.remove(writeKey)
                    chunkedWrite.result.error("GATT_ERROR", "Characteristic not found for chunked write", null)
                }
                // 如果这是最后一个剩余分包，清理并报告成功
                if (chunkedWrite.chunks.isEmpty()) {
                    pendingChunkedWrites.remove(writeKey)
                    chunkedWrite.result.success(null)
                }
                return
            }

            // 分包写入中任一分包失败，清理队列并报错
            if (chunkedWrite != null && status != BluetoothGatt.GATT_SUCCESS) {
                pendingChunkedWrites.remove(writeKey)
                chunkedWrite.result.error("GATT_ERROR", "Chunked write failed at intermediate chunk: $status", null)
                return
            }

            // 无分包写入 — 普通单次写入回调
            pendingChunkedWrites.remove(writeKey)
            val key = "write_${gatt.device.address}_$charUuid"
            val result = pendingCallbacks.remove(key)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                result?.success(null)
            } else {
                result?.error("GATT_ERROR", "Write characteristic failed: $status", null)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value?.toList() ?: emptyList<Int>()
            sendEvent(mapOf(
                "type" to "characteristicNotified",
                "remoteId" to gatt.device.address,
                "serviceUuid" to FlutterBluetoothPlugin.uuidToStandardString(characteristic.service.uuid),
                "characteristicUuid" to FlutterBluetoothPlugin.uuidToStandardString(characteristic.uuid),
                "value" to value
            ))
        }

        override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val key = "readDesc_${gatt.device.address}_${FlutterBluetoothPlugin.uuidToStandardString(descriptor.uuid)}"
            val result = pendingCallbacks.remove(key)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val value = descriptor.value?.toList() ?: emptyList<Int>()
                result?.success(value)
            } else {
                result?.error("GATT_ERROR", "Read descriptor failed: $status", null)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val key = "writeDesc_${gatt.device.address}_${FlutterBluetoothPlugin.uuidToStandardString(descriptor.uuid)}"
            // 同时检查通知描述符写入
            val characteristicUuid = FlutterBluetoothPlugin.uuidToStandardString(descriptor.characteristic.uuid)
            val notifyKey = "notify_${gatt.device.address}_$characteristicUuid"

            pendingCallbacks.remove(key)?.success(null)
            val notifyResult = pendingCallbacks.remove(notifyKey)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                notifyResult?.success(true)
            } else {
                notifyResult?.error("GATT_ERROR", "Write descriptor failed: $status", null)
            }
        }
    }

    // ─── 辅助方法 ─────────────────────────────────────────────────────

    private fun findCharacteristic(gatt: BluetoothGatt, serviceUuid: String,
                                   characteristicUuid: String): BluetoothGattCharacteristic? {
        val service = gatt.getService(UUID.fromString(serviceUuid)) ?: return null
        return service.getCharacteristic(UUID.fromString(characteristicUuid))
    }

    private fun findDescriptor(gatt: BluetoothGatt, serviceUuid: String,
                               characteristicUuid: String,
                               descriptorUuid: String): BluetoothGattDescriptor? {
        val characteristic = findCharacteristic(gatt, serviceUuid, characteristicUuid) ?: return null
        return characteristic.getDescriptor(UUID.fromString(descriptorUuid))
    }

    companion object {
        fun serviceToMap(remoteId: String, service: BluetoothGattService): Map<String, Any?> {
            val characteristics = service.characteristics.map { char ->
                val properties = char.properties
                val descs = char.descriptors.map { desc ->
                    mapOf(
                        "remoteId" to remoteId,
                        "serviceUuid" to FlutterBluetoothPlugin.uuidToStandardString(service.uuid),
                        "characteristicUuid" to FlutterBluetoothPlugin.uuidToStandardString(char.uuid),
                        "descriptorUuid" to FlutterBluetoothPlugin.uuidToStandardString(desc.uuid)
                    )
                }

                mapOf(
                    "remoteId" to remoteId,
                    "serviceUuid" to FlutterBluetoothPlugin.uuidToStandardString(service.uuid),
                    "characteristicUuid" to FlutterBluetoothPlugin.uuidToStandardString(char.uuid),
                    "instanceId" to char.instanceId,
                    "properties" to mapOf(
                        "broadcast" to (properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0),
                        "read" to (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0),
                        "writeWithoutResponse" to (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0),
                        "write" to (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0),
                        "notify" to (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0),
                        "indicate" to (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0),
                        "authenticatedSignedWrites" to (properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0),
                        "extendedProperties" to (properties and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0),
                        "notifyEncryptionRequired" to false,
                        "indicateEncryptionRequired" to false
                    ),
                    "descriptors" to descs
                )
            }

            return mapOf(
                "remoteId" to remoteId,
                "primaryServiceUuid" to null,
                "serviceUuid" to FlutterBluetoothPlugin.uuidToStandardString(service.uuid),
                "characteristics" to characteristics
            )
        }
    }
}
