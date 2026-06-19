package io.github.berial.flutter_bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*
import java.util.UUID

/**
 * Flutter 蓝牙插件 — Android 实现。
 *
 * 支持：
 * - 经典蓝牙（BR/EDR）设备发现
 * - 经典蓝牙 RFCOMM SPP 数据通信（发送/接收）
 * - BLE 扫描
 * - BLE GATT 连接、服务发现、读/写/通知
 */
class FlutterBluetoothPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {

    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private lateinit var context: Context
    private var eventSink: EventChannel.EventSink? = null

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothManager: BluetoothManager? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 管理器
    private lateinit var bleManager: BleManager
    private lateinit var classicManager: ClassicBluetoothManager
    private lateinit var rfcommManager: RfcommManager

    // 已连接的 GATT 客户端：remoteId -> BluetoothGatt
    private val connectedGatts = mutableMapOf<String, BluetoothGatt>()

    // 适配器状态广播接收器
    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR
                )
                val stateStr = when (state) {
                    BluetoothAdapter.STATE_OFF -> "off"
                    BluetoothAdapter.STATE_TURNING_OFF -> "turningOff"
                    BluetoothAdapter.STATE_ON -> "on"
                    BluetoothAdapter.STATE_TURNING_ON -> "turningOn"
                    else -> "unknown"
                }
                sendEvent(mapOf(
                    "type" to "adapterStateChanged",
                    "state" to stateStr
                ))
            }
        }
    }

    // ─── 插件生命周期 ──────────────────────────────────────────────────

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        methodChannel = MethodChannel(binding.binaryMessenger, "io.github.berial.flutter_bluetooth/methods")
        eventChannel = EventChannel(binding.binaryMessenger, "io.github.berial.flutter_bluetooth/events")

        methodChannel.setMethodCallHandler(this)
        eventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                eventSink = events
            }
            override fun onCancel(arguments: Any?) {
                eventSink = null
            }
        })

        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        bleManager = BleManager(context, bluetoothAdapter, bluetoothManager!!, ::sendEvent, ::onDeviceDisconnected)
        classicManager = ClassicBluetoothManager(context, bluetoothAdapter, ::sendEvent)
        rfcommManager = RfcommManager(bluetoothAdapter, ::sendEvent, ::onRfcommDisconnected)

        // 注册适配器状态接收器
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(adapterStateReceiver, filter)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        bleManager.stopScan()
        classicManager.stopScan()
        rfcommManager.disconnectAll()
        connectedGatts.values.forEach { it.close() }
        connectedGatts.clear()
        scope.cancel()
        try {
            context.unregisterReceiver(adapterStateReceiver)
        } catch (_: Exception) {}
    }

    // ─── 方法通道处理器 ──────────────────────────────────────────────────

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "isSupported" -> result.success(isBluetoothSupported())
                "getAdapterName" -> result.success(getAdapterName())
                "turnOn" -> handleTurnOn(call, result)
                "startScan" -> handleStartScan(call, result)
                "stopScan" -> handleStopScan(result)
                "getSystemDevices" -> handleGetSystemDevices(call, result)
                "getBondedDevices" -> handleGetBondedDevices(result)
                "connect" -> handleConnect(call, result)
                "disconnect" -> handleDisconnect(call, result)
                "discoverServices" -> handleDiscoverServices(call, result)
                "readRssi" -> handleReadRssi(call, result)
                "requestMtu" -> handleRequestMtu(call, result)
                "createBond" -> handleCreateBond(call, result)
                "removeBond" -> handleRemoveBond(call, result)
                "clearGattCache" -> handleClearGattCache(call)
                "requestConnectionPriority" -> handleConnectionPriority(call, result)
                "readCharacteristic" -> handleReadCharacteristic(call, result)
                "writeCharacteristic" -> handleWriteCharacteristic(call, result)
                "setNotifyValue" -> handleSetNotifyValue(call, result)
                "readDescriptor" -> handleReadDescriptor(call, result)
                "writeDescriptor" -> handleWriteDescriptor(call, result)
                // RFCOMM（经典蓝牙 SPP）
                "connectRfcomm" -> handleConnectRfcomm(call, result)
                "sendRfcommData" -> handleSendRfcommData(call, result)
                "disconnectRfcomm" -> handleDisconnectRfcomm(call, result)
                "isRfcommConnected" -> handleIsRfcommConnected(call, result)
                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            result.error("BLUETOOTH_ERROR", e.message ?: "Unknown error", null)
        }
    }

    // ─── 适配器 ──────────────────────────────────────────────────────────

    private fun isBluetoothSupported(): Boolean {
        return bluetoothAdapter != null
    }

    private fun getAdapterName(): String {
        return bluetoothAdapter?.name ?: "Unknown"
    }

    private fun handleTurnOn(call: MethodCall, result: MethodChannel.Result) {
        if (bluetoothAdapter?.isEnabled == false) {
            bluetoothAdapter?.enable()
        }
        result.success(null)
    }

    // ─── 扫描 ──────────────────────────────────────────────────────────

    private fun handleStartScan(call: MethodCall, result: MethodChannel.Result) {
        val scanClassic = call.argument<Boolean>("scanClassic") ?: true
        val scanModeStr = call.argument<String>("scanMode") ?: "lowLatency"
        val withServices = call.argument<List<String>>("withServices") ?: emptyList()
        val withNames = call.argument<List<String>>("withNames") ?: emptyList()
        val withKeywords = call.argument<List<String>>("withKeywords") ?: emptyList()
        val withRemoteIds = call.argument<List<String>>("withRemoteIds") ?: emptyList()

        val filter = ScanFilter(
            serviceUuids = withServices,
            names = withNames,
            keywords = withKeywords,
            remoteIds = withRemoteIds
        )

        // 启动 BLE 扫描
        bleManager.startScan(scanModeStr, filter)

        // 如需要则启动经典蓝牙扫描
        if (scanClassic) {
            classicManager.startScan(filter)
        }

        result.success(null)
    }

    private fun handleStopScan(result: MethodChannel.Result) {
        bleManager.stopScan()
        classicManager.stopScan()
        result.success(null)
    }

    // ─── 设备 ──────────────────────────────────────────────────────────

    private fun handleGetSystemDevices(call: MethodCall, result: MethodChannel.Result) {
        val devices = bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT) ?: emptyList()
        val deviceList = devices.map { deviceToMap(it, "ble") }
        result.success(deviceList)
    }

    private fun handleGetBondedDevices(result: MethodChannel.Result) {
        val devices = bluetoothAdapter?.bondedDevices ?: emptySet()
        val deviceList = devices.map { deviceToMap(it, "classic") }
        result.success(deviceList)
    }

    // ─── 连接 ──────────────────────────────────────────────────────────

    private fun handleConnect(call: MethodCall, result: MethodChannel.Result) {
        val remoteId = call.argument<String>("remoteId") ?: run {
            result.error("INVALID_ARG", "remoteId required", null); return
        }
        val autoConnect = call.argument<Boolean>("autoConnect") ?: false
        val mtu = call.argument<Int>("mtu")

        val device = bluetoothAdapter?.getRemoteDevice(remoteId) ?: run {
            result.error("DEVICE_NOT_FOUND", "Device not found: $remoteId", null); return
        }

        scope.launch {
            bleManager.connect(device, autoConnect, result, ::onDeviceConnected, desiredMtu = mtu)
        }
    }

    private fun handleDisconnect(call: MethodCall, result: MethodChannel.Result) {
        val remoteId = call.argument<String>("remoteId") ?: run {
            result.error("INVALID_ARG", "remoteId required", null); return
        }

        bleManager.disconnect(remoteId)
        result.success(null)
    }

    // ─── GATT 操作 ─────────────────────────────────────────────────────

    private fun handleDiscoverServices(call: MethodCall, result: MethodChannel.Result) {
        val remoteId = call.argument<String>("remoteId") ?: run {
            result.error("INVALID_ARG", "remoteId required", null); return
        }
        bleManager.discoverServices(remoteId, result)
    }

    private fun handleReadRssi(call: MethodCall, result: MethodChannel.Result) {
        val remoteId = call.argument<String>("remoteId") ?: run {
            result.error("INVALID_ARG", "remoteId required", null); return
        }
        bleManager.readRssi(remoteId, result)
    }

    private fun handleRequestMtu(call: MethodCall, result: MethodChannel.Result) {
        val remoteId = call.argument<String>("remoteId") ?: run {
            result.error("INVALID_ARG", "remoteId required", null); return
        }
        val desiredMtu = call.argument<Int>("desiredMtu") ?: 512
        bleManager.requestMtu(remoteId, desiredMtu, result)
    }

    private fun handleCreateBond(call: MethodCall, result: MethodChannel.Result) {
        val remoteId = call.argument<String>("remoteId") ?: run {
            result.error("INVALID_ARG", "remoteId required", null); return
        }
        val device = bluetoothAdapter?.getRemoteDevice(remoteId)
        if (device != null) {
            val success = device.createBond()
            result.success(success)
        } else {
            result.error("DEVICE_NOT_FOUND", "Device not found: $remoteId", null)
        }
    }

    private fun handleRemoveBond(call: MethodCall, result: MethodChannel.Result) {
        val remoteId = call.argument<String>("remoteId") ?: run {
            result.error("INVALID_ARG", "remoteId required", null); return
        }
        val device = bluetoothAdapter?.getRemoteDevice(remoteId)
        if (device != null) {
            try {
                val method = device.javaClass.getMethod("removeBond")
                val success = method.invoke(device) as Boolean
                result.success(success)
            } catch (e: Exception) {
                result.error("REMOVE_BOND_FAILED", e.message, null)
            }
        } else {
            result.error("DEVICE_NOT_FOUND", "Device not found: $remoteId", null)
        }
    }

    private fun handleClearGattCache(call: MethodCall) {
        val remoteId = call.argument<String>("remoteId") ?: return
        bleManager.clearGattCache(remoteId)
    }

    private fun handleConnectionPriority(call: MethodCall, result: MethodChannel.Result) {
        val remoteId = call.argument<String>("remoteId") ?: run {
            result.error("INVALID_ARG", "remoteId required", null); return
        }
        val priority = call.argument<Int>("priority") ?: 0
        bleManager.requestConnectionPriority(remoteId, priority, result)
    }

    private fun handleReadCharacteristic(call: MethodCall, result: MethodChannel.Result) {
        val remoteId = call.argument<String>("remoteId") ?: run {
            result.error("INVALID_ARG", "remoteId required", null); return
        }
        val serviceUuid = call.argument<String>("serviceUuid") ?: run {
            result.error("INVALID_ARG", "serviceUuid required", null); return
        }
        val characteristicUuid = call.argument<String>("characteristicUuid") ?: run {
            result.error("INVALID_ARG", "characteristicUuid required", null); return
        }
        bleManager.readCharacteristic(remoteId, serviceUuid, characteristicUuid, result)
    }

    private fun handleWriteCharacteristic(call: MethodCall, result: MethodChannel.Result) {
        val remoteId = call.argument<String>("remoteId") ?: run {
            result.error("INVALID_ARG", "remoteId required", null); return
        }
        val serviceUuid = call.argument<String>("serviceUuid") ?: run {
            result.error("INVALID_ARG", "serviceUuid required", null); return
        }
        val characteristicUuid = call.argument<String>("characteristicUuid") ?: run {
            result.error("INVALID_ARG", "characteristicUuid required", null); return
        }
        val value = call.argument<List<Int>>("value")?.map { it.toByte() }?.toByteArray() ?: run {
            result.error("INVALID_ARG", "value required", null); return
        }
        val withoutResponse = call.argument<Boolean>("withoutResponse") ?: false
        bleManager.writeCharacteristic(remoteId, serviceUuid, characteristicUuid, value, withoutResponse, result)
    }

    private fun handleSetNotifyValue(call: MethodCall, result: MethodChannel.Result) {
        val remoteId = call.argument<String>("remoteId") ?: run {
            result.error("INVALID_ARG", "remoteId required", null); return
        }
        val serviceUuid = call.argument<String>("serviceUuid") ?: run {
            result.error("INVALID_ARG", "serviceUuid required", null); return
        }
        val characteristicUuid = call.argument<String>("characteristicUuid") ?: run {
            result.error("INVALID_ARG", "characteristicUuid required", null); return
        }
        val enable = call.argument<Boolean>("enable") ?: false
        bleManager.setNotifyValue(remoteId, serviceUuid, characteristicUuid, enable, result)
    }

    private fun handleReadDescriptor(call: MethodCall, result: MethodChannel.Result) {
        val remoteId = call.argument<String>("remoteId") ?: run {
            result.error("INVALID_ARG", "remoteId required", null); return
        }
        val serviceUuid = call.argument<String>("serviceUuid") ?: run {
            result.error("INVALID_ARG", "serviceUuid required", null); return
        }
        val characteristicUuid = call.argument<String>("characteristicUuid") ?: run {
            result.error("INVALID_ARG", "characteristicUuid required", null); return
        }
        val descriptorUuid = call.argument<String>("descriptorUuid") ?: run {
            result.error("INVALID_ARG", "descriptorUuid required", null); return
        }
        bleManager.readDescriptor(remoteId, serviceUuid, characteristicUuid, descriptorUuid, result)
    }

    private fun handleWriteDescriptor(call: MethodCall, result: MethodChannel.Result) {
        val remoteId = call.argument<String>("remoteId") ?: run {
            result.error("INVALID_ARG", "remoteId required", null); return
        }
        val serviceUuid = call.argument<String>("serviceUuid") ?: run {
            result.error("INVALID_ARG", "serviceUuid required", null); return
        }
        val characteristicUuid = call.argument<String>("characteristicUuid") ?: run {
            result.error("INVALID_ARG", "characteristicUuid required", null); return
        }
        val descriptorUuid = call.argument<String>("descriptorUuid") ?: run {
            result.error("INVALID_ARG", "descriptorUuid required", null); return
        }
        val value = call.argument<List<Int>>("value")?.map { it.toByte() }?.toByteArray() ?: run {
            result.error("INVALID_ARG", "value required", null); return
        }
        bleManager.writeDescriptor(remoteId, serviceUuid, characteristicUuid, descriptorUuid, value, result)
    }

    // ─── BleManager 回调 ────────────────────────────────────────────────

    private fun onDeviceDisconnected(remoteId: String) {
        connectedGatts.remove(remoteId)
        sendEvent(mapOf(
            "type" to "connectionStateChanged",
            "remoteId" to remoteId,
            "state" to "disconnected"
        ))
    }

    private fun onDeviceConnected(remoteId: String, gatt: BluetoothGatt?) {
        if (gatt != null) {
            connectedGatts[remoteId] = gatt
        }
        sendEvent(mapOf(
            "type" to "connectionStateChanged",
            "remoteId" to remoteId,
            "state" to "connected"
        ))
    }

    private fun onRfcommDisconnected(remoteId: String) {
        sendEvent(mapOf(
            "type" to "connectionStateChanged",
            "remoteId" to remoteId,
            "state" to "disconnected"
        ))
    }

    // ─── RFCOMM（经典蓝牙 SPP）──────────────────────────────────────────

    private fun handleConnectRfcomm(call: MethodCall, result: MethodChannel.Result) {
        val remoteId = call.argument<String>("remoteId") ?: run {
            result.error("INVALID_ARG", "remoteId required", null); return
        }
        val uuidString = call.argument<String>("uuid")

        val device = bluetoothAdapter?.getRemoteDevice(remoteId) ?: run {
            result.error("DEVICE_NOT_FOUND", "Device not found: $remoteId", null); return
        }

        rfcommManager.connect(device, uuidString) { success, error ->
            if (success) {
                result.success(true)
            } else {
                result.error("RFCOMM_CONNECT_FAILED", error ?: "Unknown error", null)
            }
        }
    }

    private fun handleSendRfcommData(call: MethodCall, result: MethodChannel.Result) {
        val remoteId = call.argument<String>("remoteId") ?: run {
            result.error("INVALID_ARG", "remoteId required", null); return
        }
        val data = call.argument<List<Int>>("data")?.map { it.toByte() }?.toByteArray() ?: run {
            result.error("INVALID_ARG", "data required", null); return
        }

        scope.launch {
            val success = rfcommManager.sendData(remoteId, data)
            if (success) {
                result.success(true)
            } else {
                result.error("RFCOMM_SEND_FAILED", "Device not connected or write failed", null)
            }
        }
    }

    private fun handleDisconnectRfcomm(call: MethodCall, result: MethodChannel.Result) {
        val remoteId = call.argument<String>("remoteId") ?: run {
            result.error("INVALID_ARG", "remoteId required", null); return
        }
        rfcommManager.disconnect(remoteId)
        result.success(null)
    }

    private fun handleIsRfcommConnected(call: MethodCall, result: MethodChannel.Result) {
        val remoteId = call.argument<String>("remoteId") ?: run {
            result.error("INVALID_ARG", "remoteId required", null); return
        }
        result.success(rfcommManager.isConnected(remoteId))
    }

    // ─── 辅助方法 ──────────────────────────────────────────────────────

    private fun sendEvent(event: Map<String, Any?>) {
        scope.launch(Dispatchers.Main) {
            eventSink?.success(event)
        }
    }

    companion object {
        fun deviceToMap(device: BluetoothDevice, type: String): Map<String, Any?> {
            val name = try { device.name } catch (e: SecurityException) { "Unknown" }
            return mapOf(
                "remoteId" to (device.address ?: ""),
                "platformName" to (name ?: "Unknown"),
                "advName" to (name ?: ""),
                "type" to type
            )
        }

        fun uuidToStandardString(uuid: UUID): String {
            return uuid.toString().lowercase()
        }
    }
}

data class ScanFilter(
    val serviceUuids: List<String> = emptyList(),
    val names: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val remoteIds: List<String> = emptyList()
)
