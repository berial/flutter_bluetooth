part of '../flutter_bluetooth.dart';

/// 表示一个远程蓝牙设备（经典蓝牙或 BLE）。
///
/// API 模仿 `flutter_blue_plus` BluetoothDevice。
class BluetoothDevice {
  /// 该设备的唯一硬件地址（例如 "AA:BB:CC:11:22:33"）。
  final DeviceIdentifier remoteId;

  /// 平台为该设备广播的名称。
  final String platformName;

  /// 扫描期间发现的名称（广播名称）。
  final String advName;

  /// 设备类型："classic"、"ble" 或 "dual"
  final String type;

  /// 该设备已发现的服务缓存列表。
  List<BluetoothService> _servicesList = [];
  List<BluetoothService> get servicesList => List.unmodifiable(_servicesList);

  /// ── 连接状态 ──────────────────────────────────────────────────────────

  bool _isConnected = false;
  bool get isConnected => _isConnected;
  bool get isDisconnected => !_isConnected;

  /// 分别跟踪 BLE GATT 和 RFCOMM 连接状态，避免互相覆盖。
  bool _isBleConnected = false;
  bool _isRfcommConnected = false;
  bool get isRfcommConnected => _isRfcommConnected;

  final StreamController<BluetoothConnectionState> _connectionStateController =
      StreamController<BluetoothConnectionState>.broadcast();
  Stream<BluetoothConnectionState> get connectionState =>
      _connectionStateController.stream;

  /// ── 配对状态（仅 Android）────────────────────────────────────────────

  BluetoothBondState? _prevBondState;
  BluetoothBondState? get prevBondState => _prevBondState;

  final StreamController<BluetoothBondState> _bondStateController =
      StreamController<BluetoothBondState>.broadcast();
  Stream<BluetoothBondState> get bondState => _bondStateController.stream;

  /// ── MTU ──────────────────────────────────────────────────────────────

  int _mtuNow = 23;

  /// 当前协商的 MTU 大小（字节）。
  int get mtuNow => _mtuNow;

  final StreamController<int> _mtuController = StreamController<int>.broadcast();
  Stream<int> get mtu => _mtuController.stream;

  /// ── RFCOMM（经典蓝牙串口数据通信）──────────────────────────────────────

  final StreamController<Uint8List> _rfcommDataController =
      StreamController<Uint8List>.broadcast();

  /// 通过 RFCOMM（SPP）套接字接收的数据流。
  /// 每个事件是从远程设备接收的一段字节数据。
  Stream<Uint8List> get onRfcommDataReceived =>
      _rfcommDataController.stream;

  // ────────────────────────────────────────────────────────────────────────

  BluetoothDevice({
    required this.remoteId,
    this.platformName = '',
    this.advName = '',
    this.type = 'ble',
  });

  /// 从 map（原生平台数据）创建。
  factory BluetoothDevice.fromMap(Map<String, dynamic> map) {
    return BluetoothDevice(
      remoteId: map['remoteId'] as String,
      platformName: map['platformName'] as String? ?? '',
      advName: map['advName'] as String? ?? '',
      type: map['type'] as String? ?? 'ble',
    );
  }

  /// 从远程 ID 字符串创建的便捷工厂方法。
  factory BluetoothDevice.fromId(String remoteId) {
    return BluetoothDevice(remoteId: remoteId);
  }

  /// ── 连接 ──────────────────────────────────────────────────────────────

  /// 通过 BLE GATT 连接到该设备。
  ///
  /// [autoConnect] — 如果为 true，Android 将在设备可用时自动重连。
  /// [timeout] — 连接超时，默认 35 秒。
  Future<void> connect({
    bool autoConnect = false,
    Duration timeout = const Duration(seconds: 35),
    int? mtu,
  }) async {
    return FlutterBluetooth.instance._connect(
      remoteId: remoteId,
      autoConnect: autoConnect,
      timeout: timeout,
      mtu: mtu,
    );
  }

  /// 断开与该设备的连接（包括 BLE GATT 和 RFCOMM）。
  Future<void> disconnect({int timeout = 35}) async {
    return FlutterBluetooth.instance._disconnect(
      remoteId: remoteId,
      timeout: timeout,
    );
  }

  /// ── 服务 ──────────────────────────────────────────────────────────────

  /// 发现服务、特征和描述符。
  Future<List<BluetoothService>> discoverServices({int timeout = 15}) async {
    final services = await FlutterBluetooth.instance._discoverServices(
      remoteId: remoteId,
      timeout: timeout,
    );
    _servicesList = services;
    return services;
  }

  /// ── RSSI ──────────────────────────────────────────────────────────────

  /// 读取已连接设备的当前 RSSI。
  Future<int> readRssi({int timeout = 15}) async {
    return FlutterBluetooth.instance._readRssi(
      remoteId: remoteId,
      timeout: timeout,
    );
  }

  /// ── MTU ──────────────────────────────────────────────────────────────

  /// 请求新的 MTU 大小（仅 Android）。
  Future<int> requestMtu(int desiredMtu, {int timeout = 15}) async {
    final result = await FlutterBluetooth.instance._requestMtu(
      remoteId: remoteId,
      desiredMtu: desiredMtu,
      timeout: timeout,
    );
    _mtuNow = result;
    _mtuController.add(result);
    return result;
  }

  /// ── 配对/绑定（仅 Android）────────────────────────────────────────────

  /// 发起与该设备的配对（绑定）。
  Future<void> createBond({int timeout = 90, List<int>? pin}) async {
    return FlutterBluetooth.instance._createBond(
      remoteId: remoteId,
      timeout: timeout,
      pin: pin,
    );
  }

  /// 移除现有绑定（取消配对）。
  Future<void> removeBond({int timeout = 30}) async {
    return FlutterBluetooth.instance._removeBond(
      remoteId: remoteId,
      timeout: timeout,
    );
  }

  /// 清除该设备的 GATT 缓存（仅 Android）。
  Future<void> clearGattCache() async {
    return FlutterBluetooth.instance._clearGattCache(remoteId);
  }

  /// ── 连接优先级（仅 Android）───────────────────────────────────────────

  /// 请求更改连接优先级。
  Future<void> requestConnectionPriority({
    required ConnectionPriority connectionPriorityRequest,
  }) async {
    return FlutterBluetooth.instance._requestConnectionPriority(
      remoteId: remoteId,
      priority: connectionPriorityRequest,
    );
  }

  /// ── 数据通信 ──────────────────────────────────────────────────────────

  /// **经典蓝牙** — 通过 RFCOMM SPP 套接字连接进行串口数据传输。
  ///
  /// 连接成功后，使用 [sendRfcommData] 发送字节，
  /// 监听 [onRfcommDataReceived] 接收传入数据。
  ///
  /// [uuid] — 可选的自定义 SPP 服务 UUID。
  ///          默认使用标准 `00001101-0000-1000-8000-00805F9B34FB`。
  Future<bool> connectRfcomm({String? uuid}) async {
    return FlutterBluetooth.instance._connectRfcomm(
      remoteId: remoteId,
      uuid: uuid,
    );
  }

  /// **经典蓝牙** — 通过 RFCOMM 套接字发送原始字节数据。
  ///
  /// 成功返回 `true`，设备未连接返回 `false`。
  Future<bool> sendRfcommData(List<int> data) async {
    return FlutterBluetooth.instance._sendRfcommData(
      remoteId: remoteId,
      data: data,
    );
  }

  /// **经典蓝牙** — 断开 RFCOMM 套接字连接。
  Future<void> disconnectRfcomm() async {
    return FlutterBluetooth.instance._disconnectRfcomm(
      remoteId: remoteId,
    );
  }

  /// **经典蓝牙** — 检查 RFCOMM 套接字是否当前已连接。
  Future<bool> checkRfcommConnected() async {
    return FlutterBluetooth.instance._isRfcommConnected(
      remoteId: remoteId,
    );
  }

  /// **BLE** — 便捷方法：向指定特征发送数据。
  ///
  /// 如需要会自动发现服务。
  Future<void> sendBleData(
    List<int> value, {
    required Guid serviceUuid,
    required Guid characteristicUuid,
    bool withoutResponse = false,
  }) async {
    // 确保已发现服务
    if (_servicesList.isEmpty) {
      await discoverServices();
    }

    // 查找特征
    BluetoothCharacteristic? char;
    for (final service in _servicesList) {
      if (service.serviceUuid == serviceUuid) {
        for (final c in service.characteristics) {
          if (c.characteristicUuid == characteristicUuid) {
            char = c;
            break;
          }
        }
      }
      if (char != null) break;
    }

    if (char == null) {
      throw Exception(
          'Characteristic not found: $characteristicUuid in service $serviceUuid');
    }

    await char.write(value, withoutResponse: withoutResponse);
  }

  /// **BLE** — 便捷方法：获取指定特征的数据流。
  ///
  /// 如需要会自动发现服务并启用通知。
  Stream<Uint8List> getBleDataStream({
    required Guid serviceUuid,
    required Guid characteristicUuid,
    bool forceIndications = false,
  }) {
    // 如果已有活跃的数据流，先清理旧的
    _bleDataStreamController?.close();
    _bleDataStreamController = StreamController<Uint8List>();

    final controller = _bleDataStreamController!;

    // 异步初始化：发现服务、查找特征、启用通知
    () async {
      try {
        if (_servicesList.isEmpty) {
          await discoverServices();
        }

        BluetoothCharacteristic? char;
        for (final service in _servicesList) {
          if (service.serviceUuid == serviceUuid) {
            for (final c in service.characteristics) {
              if (c.characteristicUuid == characteristicUuid) {
                char = c;
                break;
              }
            }
          }
          if (char != null) break;
        }

        if (char == null) {
          controller.addError(Exception(
              'Characteristic not found: $characteristicUuid'));
          return;
        }

        // 启用通知
        await char.setNotifyValue(true, forceIndications: forceIndications);

        // 转发数据
        char.onValueReceived.listen(
          (value) => controller.add(value),
          onError: controller.addError,
          onDone: controller.close,
        );
      } catch (e) {
        controller.addError(e);
      }
    }();

    return controller.stream;
  }

  /// 缓存的 BLE 数据流控制器，防止多次调用 getBleDataStream 泄漏。
  StreamController<Uint8List>? _bleDataStreamController;

  /// ── 生命周期 ──────────────────────────────────────────────────────────

  /// 释放该设备持有的所有资源（流控制器等）。
  void dispose() {
    _bleDataStreamController?.close();
    _connectionStateController.close();
    _bondStateController.close();
    _mtuController.close();
    _rfcommDataController.close();
  }

  /// ── 内部状态更新 ──────────────────────────────────────────────────────

  void _updateConnectionState(BluetoothConnectionState state) {
    _isBleConnected = state == BluetoothConnectionState.connected;
    _syncCombinedConnectionState();
    _connectionStateController.add(state);
  }

  void _updateBondState(BluetoothBondState state) {
    _prevBondState = state;
    _bondStateController.add(state);
  }

  void _updateMtu(int newMtu) {
    _mtuNow = newMtu;
    _mtuController.add(newMtu);
  }

  void _setServices(List<BluetoothService> services) {
    _servicesList = services;
  }

  void _updateRfcommConnection(bool connected) {
    _isRfcommConnected = connected;
    _syncCombinedConnectionState();
    if (connected) {
      _connectionStateController.add(BluetoothConnectionState.connected);
    } else if (!_isBleConnected) {
      _connectionStateController.add(BluetoothConnectionState.disconnected);
    }
  }

  void _syncCombinedConnectionState() {
    _isConnected = _isBleConnected || _isRfcommConnected;
  }

  void _addRfcommData(Uint8List data) {
    _rfcommDataController.add(data);
  }

  // ────────────────────────────────────────────────────────────────────────

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is BluetoothDevice && remoteId == other.remoteId;

  @override
  int get hashCode => remoteId.hashCode;

  @override
  String toString() =>
      'BluetoothDevice(remoteId: $remoteId, name: $platformName, '
      'connected: $isConnected, type: $type)';
}
