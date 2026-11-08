package io.github.berial.flutter_bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
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

/**
 * 管理 BLE 扫描和 GATT 操作。
 */
class BleManager(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val sendEvent: (Map<String, Any?>) -> Unit,
    private val onDeviceConnected: (String) -> Unit,
    private val onDeviceDisconnected: (String, Int, String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var leScanner: BluetoothLeScanner? = null

    // 连接状态和所有操作回调在主线程处理。
    private class Connection(
        val gatt: BluetoothGatt,
        val autoConnect: Boolean,
        var connectResult: MethodChannel.Result?,
        val desiredMtu: Int?
    ) {
        var connected = false
        var mtu = DEFAULT_MTU
        var mtuNegotiated = false
        var mtuJob: Job? = null
        var initialMtuInFlight = false
        var operation: Operation? = null
    }

    private class Operation(
        val key: String,
        val result: MethodChannel.Result,
        val advance: (Any?) -> Boolean
    ) {
        var timeout: Job? = null
    }

    private val connections = mutableMapOf<String, Connection>()

    // ─── BLE 扫描 ──────────────────────────────────────────────────────

    // I3: 扫描结果去重 — remoteId -> 广播包指纹，相同指纹直接丢弃
    private val advSeen = mutableMapOf<String, String>()

    private var bleScanCallback: ScanCallback? = null

    private fun newScanCallback() = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (bleScanCallback !== this) return
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
                "txPowerLevel" to record.txPowerLevel,
                // shortcut: API 21–25 does not expose this flag, use the native value on API 26+.
                "connectable" to (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || result.isConnectable),
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
            if (bleScanCallback !== this) return
            bleScanCallback = null
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
        val callback = newScanCallback()
        bleScanCallback = callback
        try {
            leScanner?.startScan(
                if (nativeFilters.isEmpty()) null else nativeFilters,
                settings,
                callback
            )
            // N2: 原生兜底超时，防止 Dart 端 crash 后永久扫描
            scanHandler.postDelayed(scanTimeoutRunnable, scanTimeoutMs)
        } catch (e: SecurityException) {
            isScanning = false
            bleScanCallback = null
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
        val callback = bleScanCallback
        bleScanCallback = null
        scanHandler.removeCallbacks(scanTimeoutRunnable)
        try {
            if (callback != null) leScanner?.stopScan(callback)
        } catch (e: SecurityException) {}
        // I4: 推送 scanStopped 事件，Dart 端统一处理（与经典蓝牙侧一致）
        sendEvent(mapOf(
            "type" to "scanStopped",
            "source" to "ble"
        ))
    }

    // ─── 连接管理 ────────────────────────────────────────────────────────

    fun connect(device: BluetoothDevice, autoConnect: Boolean, result: MethodChannel.Result,
                desiredMtu: Int? = null) {
        val address = device.address
        val existing = connections[address]
        if (existing?.connected == true) {
            result.error("ALREADY_CONNECTED", "Device already connected", null)
            return
        }
        if (existing != null) closeConnection(address, existing, "Connection replaced", notify = false)

        try {
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, autoConnect, gattCallback)
            }
            if (gatt == null) {
                result.error("CONNECT_FAILED", "Unable to open GATT client", null)
                return
            }
            connections[address] = Connection(gatt, autoConnect, result, desiredMtu)
        } catch (e: SecurityException) {
            result.error("SECURITY", "Permission denied: ${e.message}", null)
        } catch (e: Exception) {
            result.error("CONNECT_FAILED", e.message, null)
        }
    }

    fun disconnect(remoteId: String) {
        val connection = connections[remoteId] ?: return
        closeConnection(remoteId, connection, "Disconnected by caller")
    }

    fun dispose() {
        stopScan()
        for ((address, connection) in connections.toMap()) {
            closeConnection(address, connection, "Plugin detached", notify = false)
        }
        scope.cancel()
    }

    private fun closeConnection(
        address: String, connection: Connection, message: String, notify: Boolean = true
    ) {
        connections.remove(address)
        resetLink(connection, message)
        try { connection.gatt.disconnect() } catch (_: Exception) {}
        try { connection.gatt.close() } catch (_: Exception) {}
        if (notify) reportDisconnected(address, 0, message)
    }

    private fun resetLink(connection: Connection, message: String) {
        connection.connected = false
        connection.mtu = DEFAULT_MTU
        connection.mtuNegotiated = false
        connection.initialMtuInFlight = false
        connection.mtuJob?.cancel()
        connection.mtuJob = null
        connection.connectResult?.error("DISCONNECTED", message, null)
        connection.connectResult = null
        takeOperation(connection)?.result?.error("DISCONNECTED", message, null)
    }

    private fun reportDisconnected(address: String, status: Int, message: String) {
        sendMtu(address, DEFAULT_MTU)
        onDeviceDisconnected(address, status, message)
    }

    private fun connected(remoteId: String, result: MethodChannel.Result): Connection? {
        val connection = connections[remoteId]
        if (connection?.connected == true && connection.connectResult == null) return connection
        result.error("NOT_CONNECTED", "Device not ready", null)
        return null
    }

    private fun completeConnect(address: String, connection: Connection) {
        connection.mtuJob?.cancel()
        connection.mtuJob = null
        val result = connection.connectResult
        connection.connectResult = null
        result?.success(null)
        onDeviceConnected(address)
    }

    // ─── GATT 操作 ─────────────────────────────────────────────────────

    private fun startOperation(
        connection: Connection,
        key: String,
        timeout: Int,
        result: MethodChannel.Result,
        advance: (Any?) -> Boolean = { true },
        start: () -> Boolean
    ) {
        if (connection.operation != null) {
            result.error("GATT_BUSY", "Another GATT operation is in progress", null)
            return
        }
        val operation = Operation(key, result, advance)
        connection.operation = operation
        try {
            if (!start()) throw IllegalStateException("Android rejected $key")
            operation.timeout = scope.launch {
                delay(timeout.coerceAtLeast(1) * 1000L)
                takeOperation(connection)?.result?.error("TIMEOUT", "$key timed out", null)
                // Android callbacks carry no request ID. Retire this link before accepting another operation.
                closeConnection(connection.gatt.device.address, connection, "$key timed out")
            }
        } catch (e: Exception) {
            failOperation(connection, e)
        }
    }

    private fun takeOperation(connection: Connection): Operation? {
        val operation = connection.operation
        connection.operation = null
        operation?.timeout?.cancel()
        return operation
    }

    private fun failOperation(connection: Connection, error: Exception) {
        val code = if (error is SecurityException) "SECURITY" else "GATT_ERROR"
        takeOperation(connection)?.result?.error(code, error.message, null)
    }

    private fun completeOperation(connection: Connection, key: String, status: Int, value: Any? = null) {
        val operation = connection.operation ?: return
        if (operation.key != key) return
        try {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                throw IllegalStateException("$key failed: $status")
            }
            if (operation.advance(value)) takeOperation(connection)?.result?.success(value)
        } catch (e: Exception) {
            failOperation(connection, e)
        }
    }

    fun discoverServices(remoteId: String, timeout: Int, result: MethodChannel.Result) {
        val connection = connected(remoteId, result) ?: return
        startOperation(connection, "discover", timeout, result) { connection.gatt.discoverServices() }
    }

    fun readRssi(remoteId: String, timeout: Int, result: MethodChannel.Result) {
        val connection = connected(remoteId, result) ?: return
        startOperation(connection, "rssi", timeout, result) { connection.gatt.readRemoteRssi() }
    }

    fun requestMtu(remoteId: String, desiredMtu: Int, timeout: Int, result: MethodChannel.Result) {
        val connection = connected(remoteId, result) ?: return
        if (mtuAlreadyNegotiated(connection)) {
            result.success(connection.mtu)
            return
        }
        if (connection.initialMtuInFlight) {
            result.error("GATT_BUSY", "Initial MTU request is still pending", null)
            return
        }
        startOperation(connection, "mtu", timeout, result) { connection.gatt.requestMtu(desiredMtu) }
    }

    private fun mtuAlreadyNegotiated(connection: Connection): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && connection.mtuNegotiated

    fun readCharacteristic(remoteId: String, serviceUuid: String, characteristicUuid: String,
                           timeout: Int, result: MethodChannel.Result) {
        val connection = connected(remoteId, result) ?: return
        val characteristic = findCharacteristic(connection.gatt, serviceUuid, characteristicUuid)
        if (characteristic == null) {
            result.error("CHAR_NOT_FOUND", "Characteristic not found", null)
            return
        }
        startOperation(connection, "read:${characteristic.instanceId}", timeout, result) {
            connection.gatt.readCharacteristic(characteristic)
        }
    }

    fun writeCharacteristic(remoteId: String, serviceUuid: String, characteristicUuid: String,
                            value: ByteArray, withoutResponse: Boolean, timeout: Int,
                            result: MethodChannel.Result) {
        val connection = connected(remoteId, result) ?: return
        val characteristic = findCharacteristic(connection.gatt, serviceUuid, characteristicUuid)
        if (characteristic == null) {
            result.error("CHAR_NOT_FOUND", "Characteristic not found", null)
            return
        }
        val maxPayload = (connection.mtu - 3).coerceIn(20, 512)
        val supportsResponse = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        val writeType = if (withoutResponse && (value.size <= maxPayload || !supportsResponse)) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        var offset = 0
        fun writeNext(): Boolean {
            val end = minOf(offset + maxPayload, value.size)
            val chunk = if (value.size <= maxPayload) value else value.copyOfRange(offset, end)
            offset = end
            return writeChunk(connection.gatt, characteristic, chunk, writeType)
        }
        startOperation(connection, "write:${characteristic.instanceId}", timeout, result, advance = {
            if (offset == value.size) {
                true
            } else {
                check(writeNext()) { "Android rejected a write chunk" }
                false
            }
        }, start = ::writeNext)
    }

    @Suppress("DEPRECATION")
    private fun writeChunk(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
                           value: ByteArray, writeType: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return gatt.writeCharacteristic(characteristic, value, writeType) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        }
        characteristic.value = value
        characteristic.writeType = writeType
        return gatt.writeCharacteristic(characteristic)
    }

    fun setNotifyValue(remoteId: String, serviceUuid: String, characteristicUuid: String,
                       enable: Boolean, forceIndications: Boolean, timeout: Int,
                       result: MethodChannel.Result) {
        val connection = connected(remoteId, result) ?: return
        val characteristic = findCharacteristic(connection.gatt, serviceUuid, characteristicUuid)
        if (characteristic == null) {
            result.error("CHAR_NOT_FOUND", "Characteristic not found", null)
            return
        }
        val properties = characteristic.properties
        val supportsNotify = (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
        val supportsIndicate = (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        val value = when {
            !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            supportsNotify && !forceIndications -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            supportsIndicate -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> {
                result.error("UNSUPPORTED", "Characteristic does not support the requested notification mode", null)
                return
            }
        }
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            result.success(false)
            return
        }
        startOperation(connection, descriptorKey("notify", descriptor, enable), timeout, result) {
            connection.gatt.setCharacteristicNotification(characteristic, enable) &&
                writeDescriptorValue(connection.gatt, descriptor, value)
        }
    }

    fun readDescriptor(remoteId: String, serviceUuid: String, characteristicUuid: String,
                       descriptorUuid: String, timeout: Int, result: MethodChannel.Result) {
        val connection = connected(remoteId, result) ?: return
        val descriptor = findDescriptor(connection.gatt, serviceUuid, characteristicUuid, descriptorUuid)
        if (descriptor == null) {
            result.error("DESC_NOT_FOUND", "Descriptor not found", null)
            return
        }
        startOperation(connection, descriptorKey("readDesc", descriptor), timeout, result) {
            connection.gatt.readDescriptor(descriptor)
        }
    }

    fun writeDescriptor(remoteId: String, serviceUuid: String, characteristicUuid: String,
                        descriptorUuid: String, value: ByteArray, timeout: Int, result: MethodChannel.Result) {
        val connection = connected(remoteId, result) ?: return
        val descriptor = findDescriptor(connection.gatt, serviceUuid, characteristicUuid, descriptorUuid)
        if (descriptor == null) {
            result.error("DESC_NOT_FOUND", "Descriptor not found", null)
            return
        }
        startOperation(connection, descriptorKey("writeDesc", descriptor), timeout, result) {
            writeDescriptorValue(connection.gatt, descriptor, value)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptorValue(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor,
                                     value: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return gatt.writeDescriptor(descriptor, value) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        }
        descriptor.value = value
        return gatt.writeDescriptor(descriptor)
    }

    private fun descriptorKey(action: String, descriptor: BluetoothGattDescriptor, enable: Boolean? = null): String {
        val key = "$action:${descriptor.characteristic.instanceId}:${descriptor.uuid}"
        return if (enable == null) key else "$key:$enable"
    }

    fun requestConnectionPriority(remoteId: String, priority: Int, result: MethodChannel.Result) {
        val connection = connected(remoteId, result) ?: return
        val connPriority = when (priority) {
            1 -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
            2 -> BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
            else -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        }
        result.success(connection.gatt.requestConnectionPriority(connPriority))
    }

    fun clearGattCache(remoteId: String, result: MethodChannel.Result) {
        val connection = connected(remoteId, result) ?: return
        val method = connection.gatt.javaClass.getMethod("refresh")
        if (method.invoke(connection.gatt) == true) {
            result.success(null)
        } else {
            result.error("GATT_ERROR", "GATT cache refresh failed", null)
        }
    }

    // ─── GATT 回调 ──────────────────────────────────────────────────────

    private fun forConnection(gatt: BluetoothGatt, callback: (Connection) -> Unit) {
        scope.launch {
            val connection = connections[gatt.device.address]
            if (connection?.gatt === gatt) callback(connection)
        }
    }

    private fun sendMtu(address: String, mtu: Int) {
        sendEvent(mapOf("type" to "mtuChanged", "remoteId" to address, "mtu" to mtu))
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) =
            forConnection(gatt) { connection ->
                val address = gatt.device.address
                if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    val message = hciStatusString(status)
                    val keepForReconnect = connection.autoConnect && connection.connectResult == null
                    if (keepForReconnect) {
                        resetLink(connection, message)
                    } else {
                        closeConnection(address, connection, message, notify = false)
                    }
                    reportDisconnected(address, status, message)
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connection.connected = true
                    val desiredMtu = connection.desiredMtu
                    if (connection.connectResult != null && desiredMtu != null && desiredMtu > DEFAULT_MTU) {
                        connection.mtuJob = scope.launch {
                            delay(MTU_REQUEST_PREDELAY_MS)
                            connection.initialMtuInFlight = try {
                                !mtuAlreadyNegotiated(connection) && gatt.requestMtu(desiredMtu)
                            } catch (_: Exception) {
                                false
                            }
                            if (connection.initialMtuInFlight) delay(INITIAL_MTU_TIMEOUT_MS)
                            completeConnect(address, connection)
                        }
                    } else {
                        completeConnect(address, connection)
                    }
                }
            }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) =
            forConnection(gatt) { connection ->
                val services = if (status == BluetoothGatt.GATT_SUCCESS) {
                    gatt.services.map { serviceToMap(gatt.device.address, it) }
                } else null
                completeOperation(connection, "discover", status, services)
            }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) =
            forConnection(gatt) { completeOperation(it, "rssi", status, rssi) }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) =
            forConnection(gatt) { connection ->
                if (!connection.connected) return@forConnection
                val address = gatt.device.address
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    connection.mtu = mtu
                    connection.mtuNegotiated = true
                    sendMtu(address, mtu)
                }
                if (connection.initialMtuInFlight) {
                    connection.initialMtuInFlight = false
                    if (connection.connectResult != null) completeConnect(address, connection)
                } else {
                    completeOperation(connection, "mtu", status, mtu)
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
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            onCharacteristicReadReceived(gatt, characteristic, characteristic.value?.copyOf() ?: ByteArray(0), status)
        }

        private fun onCharacteristicReadReceived(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            forConnection(gatt) {
                completeOperation(it, "read:${characteristic.instanceId}", status, value)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
                                           status: Int) = forConnection(gatt) {
            completeOperation(it, "write:${characteristic.instanceId}", status)
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
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            onCharacteristicReceived(gatt, characteristic, characteristic.value?.copyOf() ?: ByteArray(0))
        }

        private fun onCharacteristicReceived(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            forConnection(gatt) {
                if (!it.connected) return@forConnection
                sendEvent(mapOf(
                    "type" to "characteristicNotified",
                    "remoteId" to gatt.device.address,
                    "serviceUuid" to FlutterBluetoothPlugin.uuidToStandardString(characteristic.service.uuid),
                    "characteristicUuid" to FlutterBluetoothPlugin.uuidToStandardString(characteristic.uuid),
                    "value" to value
                ))
            }
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
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            onDescriptorReadReceived(gatt, descriptor, descriptor.value?.copyOf() ?: ByteArray(0), status)
        }

        private fun onDescriptorReadReceived(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            value: ByteArray,
            status: Int
        ) {
            forConnection(gatt) { completeOperation(it, descriptorKey("readDesc", descriptor), status, value) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            forConnection(gatt) { connection ->
                val key = connection.operation?.key
                when (key) {
                    descriptorKey("notify", descriptor, true) -> completeOperation(connection, key, status, true)
                    descriptorKey("notify", descriptor, false) -> completeOperation(connection, key, status, false)
                    else -> completeOperation(connection, descriptorKey("writeDesc", descriptor), status)
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
        private const val INITIAL_MTU_TIMEOUT_MS = 5_000L
        private const val DEFAULT_MTU = 23
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

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
