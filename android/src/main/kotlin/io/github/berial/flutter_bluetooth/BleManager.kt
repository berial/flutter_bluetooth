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
    private val onDeviceDisconnected: (String, Int, String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var leScanner: BluetoothLeScanner? = null

    // 活跃的 GATT 连接：remoteId -> BluetoothGatt
    private val gattMap = ConcurrentHashMap<String, BluetoothGatt>()

    // 设备的 autoConnect 标记：用于断开时决定是否跳过 gatt.close() 保留重连句柄
    private val autoConnectMap = ConcurrentHashMap<String, Boolean>()

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

    // I3: 扫描结果去重 — remoteId -> 广播包指纹，相同指纹直接丢弃
    private val advSeen = ConcurrentHashMap<String, String>()

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

            val address = device.address ?: return

            // I3: 去重 — 相同广播包指纹直接丢弃，避免重复广播刷屏
            val advHex = buildAdvFingerprint(name, serviceUuids, manufacturerData, serviceData, record.txPowerLevel)
            if (advSeen[address] == advHex) return
            advSeen[address] = advHex

            val deviceMap = mapOf(
                "remoteId" to address,
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
            scanHandler.removeCallbacks(scanTimeoutRunnable)
            sendEvent(mapOf(
                "type" to "scanError",
                "source" to "ble",
                "errorCode" to errorCode
            ))
            // R12: 扫描失败后补推 scanStopped，重置 Dart 端 _isBleScanning
            sendEvent(mapOf(
                "type" to "scanStopped",
                "source" to "ble"
            ))
        }
    }

    /** I3: 构造广播包指纹（name + serviceUuids + manufacturerData + serviceData + txPower）。 */
    private fun buildAdvFingerprint(
        name: String,
        serviceUuids: List<String>,
        manufacturerData: Map<Int, List<Int>>,
        serviceData: Map<String, List<Int>>,
        txPower: Int?
    ): String {
        val sb = StringBuilder()
        sb.append(name).append('|')
        sb.append(serviceUuids.joinToString(",")).append('|')
        manufacturerData.toSortedMap().forEach { (k, v) -> sb.append(k).append(':').append(v.joinToString(",")).append(';') }
        sb.append('|')
        serviceData.toSortedMap().forEach { (k, v) -> sb.append(k).append(':').append(v.joinToString(",")).append(';') }
        sb.append('|').append(txPower ?: -128)
        return sb.toString()
    }

    @Volatile
    private var isScanning = false
    private val scanHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** BLE 扫描原生兜底超时（毫秒）。Dart 端 crash 时防止永久扫描。 */
    private val scanTimeoutMs = 120_000L

    private val scanTimeoutRunnable = Runnable {
        if (isScanning) {
            android.util.Log.w("BleManager", "BLE scan native timeout, force stop")
            stopScan()
        }
    }

    fun startScan(scanModeStr: String, filter: ScanFilter) {
        if (isScanning) {
            // 重复扫描时推送 scanError，与 Dart 端契约一致
            sendEvent(mapOf(
                "type" to "scanError",
                "source" to "ble",
                "errorCode" to -1,
                "message" to "BLE scan already in progress"
            ))
            return
        }

        leScanner = bluetoothAdapter?.bluetoothLeScanner
        if (leScanner == null) {
            android.util.Log.w("BleManager", "BLE scanner not available")
            sendEvent(mapOf(
                "type" to "scanError",
                "source" to "ble",
                "errorCode" to -1,
                "message" to "BLE scanner not available"
            ))
            return
        }

        val scanMode = when (scanModeStr) {
            "lowPower" -> ScanSettings.SCAN_MODE_LOW_POWER
            "balanced" -> ScanSettings.SCAN_MODE_BALANCED
            "lowLatency" -> ScanSettings.SCAN_MODE_LOW_LATENCY
            else -> ScanSettings.SCAN_MODE_LOW_LATENCY
        }

        // I2: Android 8+ 加 setPhy + setLegacy，支持双 PHY 扫描与扩展广播
        val settingsBuilder = ScanSettings.Builder().setScanMode(scanMode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settingsBuilder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            settingsBuilder.setLegacy(false)
        }
        val settings = settingsBuilder.build()

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
        advSeen.clear()
        try {
            leScanner?.startScan(
                if (nativeFilters.isEmpty()) null else nativeFilters,
                settings,
                bleScanCallback
            )
            // N2: 原生兜底超时，防止 Dart 端 crash 后永久扫描
            scanHandler.postDelayed(scanTimeoutRunnable, scanTimeoutMs)
        } catch (e: SecurityException) {
            isScanning = false
            android.util.Log.e("BleManager", "Bluetooth scan permission denied: ${e.message}")
            sendEvent(mapOf(
                "type" to "scanError",
                "source" to "ble",
                "errorCode" to -1,
                "message" to "Permission denied: ${e.message}"
            ))
        }
    }

    fun stopScan() {
        if (!isScanning) return
        isScanning = false
        scanHandler.removeCallbacks(scanTimeoutRunnable)
        try {
            leScanner?.stopScan(bleScanCallback)
        } catch (e: SecurityException) {}
        // I4: 推送 scanStopped 事件，Dart 端统一处理（与经典蓝牙侧一致）
        sendEvent(mapOf(
            "type" to "scanStopped",
            "source" to "ble"
        ))
    }

    // ─── 连接管理 ────────────────────────────────────────────────────────

    fun connect(device: BluetoothDevice, autoConnect: Boolean, result: MethodChannel.Result,
                onConnected: (String, BluetoothGatt?) -> Unit, desiredMtu: Int? = null) {
        scope.launch {
            try {
                // B1: 显式传 TRANSPORT_LE，部分外设默认 AUTO 传输下连接失败
                // B8: 先 connectGatt 再 put，避免异常时 gattMap 存入 null
                val gatt = device.connectGatt(
                    context, autoConnect, gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
                gattMap[device.address] = gatt
                autoConnectMap[device.address] = autoConnect

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
        // N3: 用户主动 disconnect = 终止 autoConnect，显式 close 释放句柄
        // 否则 autoConnect 设备跳过 close 会导致 gatt 泄漏（再次 connect 同一设备时旧 gatt 孤儿）
        autoConnectMap.remove(remoteId)
        mtuMap.remove(remoteId)
        pendingConnects.remove(remoteId)
        // B6: 清理该设备的所有 pendingCallbacks，避免 Future 挂死
        val prefix = "${remoteId}_"
        val keysToFail = pendingCallbacks.keys.filter { it.startsWith(prefix) || it == "connect_$remoteId" }
        for (key in keysToFail) {
            pendingCallbacks.remove(key)?.error("DISCONNECTED", "Device disconnected before operation completed", null)
            // R6: 取消关联的超时 Job
            writeTimeoutJobs.remove(key)?.cancel()
        }
        // 移除该设备的待处理分包写入
        pendingChunkedWrites.keys.filter { it.startsWith("${remoteId}_") }
            .forEach {
                val cw = pendingChunkedWrites.remove(it)
                cw?.result?.error("DISCONNECTED", "Device disconnected during chunked write", null)
            }
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
        val key = "discover_$remoteId"
        pendingCallbacks[key] = result
        try {
            gatt.discoverServices()
        } catch (e: SecurityException) {
            // U1: 清理 pendingCallbacks，避免残留已回复的 result
            pendingCallbacks.remove(key)
            result.error("SECURITY", e.message, null)
        }
    }

    fun readRssi(remoteId: String, result: MethodChannel.Result) {
        val gatt = gattMap[remoteId]
        if (gatt == null) {
            result.error("NOT_CONNECTED", "Device not connected", null)
            return
        }
        val key = "readRssi_$remoteId"
        pendingCallbacks[key] = result
        try {
            gatt.readRemoteRssi()
        } catch (e: SecurityException) {
            // U1: 清理 pendingCallbacks，避免残留已回复的 result
            pendingCallbacks.remove(key)
            result.error("SECURITY", e.message, null)
        }
    }

    fun requestMtu(remoteId: String, desiredMtu: Int, result: MethodChannel.Result) {
        val gatt = gattMap[remoteId]
        if (gatt == null) {
            result.error("NOT_CONNECTED", "Device not connected", null)
            return
        }
        val key = "mtu_$remoteId"
        pendingCallbacks[key] = result
        try {
            gatt.requestMtu(desiredMtu)
        } catch (e: SecurityException) {
            // U1: 清理 pendingCallbacks，避免残留已回复的 result
            pendingCallbacks.remove(key)
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
        val key = "read_${remoteId}_$characteristicUuid"
        pendingCallbacks[key] = result
        try {
            gatt.readCharacteristic(characteristic)
        } catch (e: SecurityException) {
            // U1: 清理 pendingCallbacks，避免残留已回复的 result
            pendingCallbacks.remove(key)
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
        // Q3: MTU 下限保护，避免异常值导致 maxPayload ≤ 0 引发分包死循环
        val maxPayload = (currentMtu - 3).coerceAtLeast(20) // ATT 头部 = 3 字节，最小 20

        // P2: withoutResponse 模式不触发 onCharacteristicWrite 回调，分包队列无法推进
        // 强制 withoutResponse + 大包场景退化为 withResponse，避免死锁
        val effectiveWithoutResponse = withoutResponse && value.size <= maxPayload

        if (value.size <= maxPayload) {
            // 数据可放入单个包 — 直接写入
            writeSingleChunk(gatt, characteristic, value, effectiveWithoutResponse, remoteId, characteristicUuid, result)
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
                withoutResponse = effectiveWithoutResponse,
                result = result
            )

            // 写入第一个分包
            writeSingleChunk(gatt, characteristic, firstChunk, effectiveWithoutResponse, remoteId, characteristicUuid, null)
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
        val writeType = if (withoutResponse)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        // B2: Android 13+ (API 33) 用新 API writeCharacteristic(char, value, writeType)
        val writeOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val rv = gatt.writeCharacteristic(characteristic, value, writeType)
                if (rv == android.bluetooth.BluetoothStatusCodes.SUCCESS) {
                    true
                } else {
                    // N5: 返回非 SUCCESS 时显式报错，避免 Dart 端 Future 挂死
                    result?.error("GATT_ERROR", "writeCharacteristic returned $rv", null)
                    failChunkedWrite(gatt, remoteId, characteristicUuid, "rv=$rv")
                    false
                }
            } catch (e: SecurityException) {
                result?.error("SECURITY", e.message, null)
                // B9: 异常时清理整个 ChunkedWrite
                failChunkedWrite(gatt, remoteId, characteristicUuid, e.message ?: "SecurityException")
                false
            } catch (e: Exception) {
                result?.error("GATT_ERROR", e.message, null)
                failChunkedWrite(gatt, remoteId, characteristicUuid, e.message ?: "Exception")
                false
            }
        } else {
            // T1: 将「正常返回 false」与「抛异常」两条路径互斥处理，
            // 避免重复调用 result.error / failChunkedWrite
            @Suppress("DEPRECATION")
            try {
                characteristic.value = value
                characteristic.writeType = writeType
                val ok = gatt.writeCharacteristic(characteristic)
                if (!ok) {
                    // S1: gatt.writeCharacteristic 返回 false 时（GATT 内部忙/特征不支持），
                    // 显式报错 + 清理 ChunkedWrite，避免 Dart 端 Future 挂死
                    result?.error("GATT_ERROR", "writeCharacteristic returned false", null)
                    failChunkedWrite(gatt, remoteId, characteristicUuid, "writeCharacteristic returned false")
                }
                ok
            } catch (e: SecurityException) {
                result?.error("SECURITY", e.message, null)
                failChunkedWrite(gatt, remoteId, characteristicUuid, e.message ?: "SecurityException")
                false
            } catch (e: Exception) {
                result?.error("GATT_ERROR", e.message, null)
                failChunkedWrite(gatt, remoteId, characteristicUuid, e.message ?: "Exception")
                false
            }
        }

        if (!writeOk) return

        // R2/R10: withoutResponse 模式立即返回（Android 不保证触发 onCharacteristicWrite，
        // 分包场景等回调会卡死）；withResponse 模式等待回调 + 超时兜底
        if (withoutResponse) {
            result?.success(null)
        } else if (result != null) {
            val key = "write_${remoteId}_$characteristicUuid"
            pendingCallbacks[key] = result
            val writeTimeoutMs = 15_000L
            // R6: 保存 Job 以便回调成功时取消，避免协程泄漏
            val timeoutJob = scope.launch {
                delay(writeTimeoutMs)
                val pending = pendingCallbacks.remove(key)
                pending?.error("TIMEOUT", "Write characteristic timed out", null)
            }
            // 关联存储 timeoutJob，onCharacteristicWrite 时取消
            writeTimeoutJobs[key] = timeoutJob
        }
    }

    /** R6: 存储 writeCharacteristic 的超时 Job，成功回调时取消避免协程泄漏。 */
    private val writeTimeoutJobs = ConcurrentHashMap<String, Job>()

    /** B9: 写入异常时清理整个 ChunkedWrite 队列并报错。 */
    private fun failChunkedWrite(gatt: BluetoothGatt, remoteId: String, characteristicUuid: String, msg: String) {
        val writeKey = "${remoteId}_${characteristicUuid}"
        val cw = pendingChunkedWrites.remove(writeKey)
        if (cw != null) {
            try { cw.result.error("GATT_ERROR", "Chunked write failed: $msg", null) } catch (_: Exception) {}
        }
    }

    fun setNotifyValue(remoteId: String, serviceUuid: String,
                       characteristicUuid: String, enable: Boolean,
                       forceIndications: Boolean,
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

        val properties = characteristic.properties
        val supportsNotify = (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
        val supportsIndicate = (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

        // B4: 选择描述符值
        // - enable=false → DISABLE
        // - forceIndications=true → 必须 INDICATE（不支持则报错）
        // - 同时支持 notify+indicate → 默认 NOTIFY（与 iOS CoreBluetooth 一致）
        // - 只支持其中一种 → 用支持的那种
        val descriptorValue = if (!enable) {
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        } else {
            if (forceIndications) {
                if (!supportsIndicate) {
                    result.error("UNSUPPORTED", "Characteristic does not support indicate", null)
                    return
                }
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else if (supportsNotify) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else if (supportsIndicate) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                result.error("UNSUPPORTED", "Characteristic does not support notify or indicate", null)
                return
            }
        }

        try {
            val descriptor = characteristic.getDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            )

            // 本地监听开关
            gatt.setCharacteristicNotification(characteristic, enable)

            if (descriptor != null) {
                // B2: Android 13+ 用新 API writeDescriptor(desc, value)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // N7: Android 13+ 部分 OEM 实现不触发 onDescriptorWrite 回调，
                    // SUCCESS 后直接 success，避免 Dart 端挂起到 timeout
                    // R5: enable=false 时返回 false 表示「已关闭通知」
                    try {
                        val rv = gatt.writeDescriptor(descriptor, descriptorValue)
                        if (rv == android.bluetooth.BluetoothStatusCodes.SUCCESS) {
                            result.success(enable)
                        } else {
                            result.error("GATT_ERROR", "writeDescriptor failed: $rv", null)
                        }
                    } catch (e: SecurityException) {
                        result.error("SECURITY", e.message, null)
                    }
                } else {
                    pendingCallbacks["notify_${remoteId}_$characteristicUuid"] = result
                    @Suppress("DEPRECATION")
                    try {
                        descriptor.value = descriptorValue
                        gatt.writeDescriptor(descriptor)
                    } catch (e: SecurityException) {
                        pendingCallbacks.remove("notify_${remoteId}_$characteristicUuid")
                        result.error("SECURITY", e.message, null)
                    }
                }
            } else {
                // B7: 无 CCCD 描述符 — 本地监听已开，但未写描述符，返回 false 表示未写
                result.success(false)
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
        pendingCallbacks["writeDesc_${remoteId}_$descriptorUuid"] = result
        // B2: Android 13+ 用新 API writeDescriptor(desc, value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val rv = gatt.writeDescriptor(descriptor, value)
                if (rv != android.bluetooth.BluetoothStatusCodes.SUCCESS) {
                    pendingCallbacks.remove("writeDesc_${remoteId}_$descriptorUuid")
                    result.error("GATT_ERROR", "writeDescriptor failed: $rv", null)
                }
            } catch (e: SecurityException) {
                pendingCallbacks.remove("writeDesc_${remoteId}_$descriptorUuid")
                result.error("SECURITY", e.message, null)
            }
        } else {
            @Suppress("DEPRECATION")
            try {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            } catch (e: SecurityException) {
                pendingCallbacks.remove("writeDesc_${remoteId}_$descriptorUuid")
                result.error("SECURITY", e.message, null)
            }
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
                    // 连接时请求了 MTU — 延迟 350ms 再发起，等待外设主动下发的 MTU 更新完成
                    // I8: 避免 predelay 期间与外设自动 MTU 更新混淆导致后续 discoverServices 失败
                    scope.launch {
                        delay(MTU_REQUEST_PREDELAY_MS)
                        // N12: predelay 期间用户可能已 disconnect，gatt 已从 gattMap 移除
                        if (!gattMap.containsKey(address)) {
                            return@launch
                        }
                        try {
                            gatt.requestMtu(pendingConnect.desiredMtu ?: 512)
                            // 保存连接信息供 onMtuChanged 回调使用
                            pendingConnects["mtu_pending_$address"] = pendingConnect
                        } catch (e: SecurityException) {
                            // MTU 请求失败，仍然报告已连接
                            pendingConnect.result.success(null)
                            pendingConnect.onConnected(address, gatt)
                        }
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
                val isAutoConnect = autoConnectMap[address] == true
                // I7: autoConnect 设备跳过 gatt.close()，保留句柄供系统后台重连
                if (!isAutoConnect) {
                    try { gatt.close() } catch (_: Exception) {}
                }
                autoConnectMap.remove(address)
                // B5: 上报断开原因（status + HCI 状态字符串），便于 Dart 端诊断
                onDeviceDisconnected(address, status, hciStatusString(status))
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

        // B3: Android 13+ 新签名 onCharacteristicRead（带 byte[] value）
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            onCharacteristicReadReceived(gatt, characteristic, value, status)
        }

        // B3: Android 13- 旧签名，委托给新签名
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            onCharacteristicReadReceived(gatt, characteristic, characteristic.value ?: ByteArray(0), status)
        }

        private fun onCharacteristicReadReceived(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            val key = "read_${gatt.device.address}_${FlutterBluetoothPlugin.uuidToStandardString(characteristic.uuid)}"
            val result = pendingCallbacks.remove(key)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                result?.success(value.toList())
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
                if (nextChar == null) {
                    // T2: 特征不可用，清理队列并报错后直接 return，避免后续重复 success
                    pendingChunkedWrites.remove(writeKey)
                    chunkedWrite.result.error("GATT_ERROR", "Characteristic not found for chunked write", null)
                    return
                }
                // T2: 写入下一分包；writeSingleChunk 内部失败时 failChunkedWrite 会清理队列并报错
                writeSingleChunk(gatt, nextChar, nextChunk, chunkedWrite.withoutResponse,
                    gatt.device.address, chunkedWrite.characteristicUuid, null)
                // T2: 若 writeSingleChunk 已通过 failChunkedWrite 清理队列（S1 场景），直接 return
                if (!pendingChunkedWrites.containsKey(writeKey)) return
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
            // R6: 取消超时协程，避免泄漏
            writeTimeoutJobs.remove(key)?.cancel()
            val result = pendingCallbacks.remove(key)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                result?.success(null)
            } else {
                result?.error("GATT_ERROR", "Write characteristic failed: $status", null)
            }
        }

        // B3: Android 13+ 新签名 onCharacteristicChanged（带 byte[] value）
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onCharacteristicReceived(gatt, characteristic, value)
        }

        // B3: Android 13- 旧签名，委托给新签名
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            onCharacteristicReceived(gatt, characteristic, characteristic.value ?: ByteArray(0))
        }

        private fun onCharacteristicReceived(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            sendEvent(mapOf(
                "type" to "characteristicNotified",
                "remoteId" to gatt.device.address,
                "serviceUuid" to FlutterBluetoothPlugin.uuidToStandardString(characteristic.service.uuid),
                "characteristicUuid" to FlutterBluetoothPlugin.uuidToStandardString(characteristic.uuid),
                "value" to value.toList()
            ))
        }

        // B3: Android 13+ 新签名 onDescriptorRead（带 byte[] value）
        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
            value: ByteArray
        ) {
            onDescriptorReadReceived(gatt, descriptor, value, status)
        }

        // B3: Android 13- 旧签名，委托给新签名
        @Suppress("DEPRECATION")
        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            onDescriptorReadReceived(gatt, descriptor, descriptor.value ?: ByteArray(0), status)
        }

        private fun onDescriptorReadReceived(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            value: ByteArray,
            status: Int
        ) {
            val key = "readDesc_${gatt.device.address}_${FlutterBluetoothPlugin.uuidToStandardString(descriptor.uuid)}"
            val result = pendingCallbacks.remove(key)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                result?.success(value.toList())
            } else {
                result?.error("GATT_ERROR", "Read descriptor failed: $status", null)
            }
        }

        // B10: 先判 status 再 success，避免失败时 Dart 端拿到成功
        // N7: Android 13+ notify 路径已直接 success，此回调主要服务 Android 13- 的 notify 和所有版本的 writeDescriptor
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val key = "writeDesc_${gatt.device.address}_${FlutterBluetoothPlugin.uuidToStandardString(descriptor.uuid)}"
            val characteristicUuid = FlutterBluetoothPlugin.uuidToStandardString(descriptor.characteristic.uuid)
            val notifyKey = "notify_${gatt.device.address}_$characteristicUuid"

            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingCallbacks.remove(key)?.success(null)
                pendingCallbacks.remove(notifyKey)?.success(true)
            } else {
                pendingCallbacks.remove(key)?.error("GATT_ERROR", "Write descriptor failed: $status", null)
                pendingCallbacks.remove(notifyKey)?.error("GATT_ERROR", "Write descriptor failed: $status", null)
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

    /** B5: 将 GATT/HCI status 码映射为可读字符串，便于 Dart 端诊断断开原因。 */
    private fun hciStatusString(status: Int): String = when (status) {
        BluetoothGatt.GATT_SUCCESS -> "SUCCESS"
        8 -> "CONNECTION_TIMEOUT"           // HCI 错误码：连接超时
        19 -> "REMOTE_USER_TERMINATED"      // 远端用户主动断开
        22 -> "LOCAL_HOST_TERMINATED"       // 本地主机关闭
        62 -> "CONN_FAIL_ESTABLISH"         // 连接建立失败
        128 -> "INTERNAL_ERROR"             // Android 蓝牙栈内部错误（常见于连接失败）
        133 -> "GATT_ERROR"                 // 通用 GATT 错误（常见于连接失败）
        137 -> "AUTH_FAIL_ENC_KEY_MISSING"  // 认证失败/密钥缺失
        143 -> "CONN_LMP_TIMEOUT"           // 链路管理协议超时
        else -> "UNKNOWN($status)"
    }

    companion object {
        /** 连接后请求 MTU 前的预延迟（毫秒），等待外设主动 MTU 更新完成。 */
        private const val MTU_REQUEST_PREDELAY_MS = 350L

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
