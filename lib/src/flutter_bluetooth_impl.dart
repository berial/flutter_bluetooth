part of '../flutter_bluetooth.dart';

/// Flutter 蓝牙插件的主入口。
///
/// API 模仿 `flutter_blue_plus` FlutterBluePlus。
class FlutterBluetooth {
  static const _channel = MethodChannel('io.github.berial.flutter_bluetooth/methods');
  static const _eventChannel =
      EventChannel('io.github.berial.flutter_bluetooth/events');

  // 单例
  static final FlutterBluetooth instance = FlutterBluetooth._();
  FlutterBluetooth._() {
    _setupEventStream();
  }

  // ─── 状态 ──────────────────────────────────────────────────────────────

  BluetoothAdapterState _adapterState = BluetoothAdapterState.unknown;
  final StreamController<BluetoothAdapterState> _adapterStateController =
      StreamController<BluetoothAdapterState>.broadcast();

  /// 适配器状态变化流。
  Stream<BluetoothAdapterState> get adapterState =>
      _adapterStateController.stream;

  /// 当前适配器状态（同步）。
  BluetoothAdapterState get adapterStateNow => _adapterState;

  String _adapterName = '';
  String get adapterName => _adapterName;

  // ─── 扫描 ──────────────────────────────────────────────────────────────

  bool _isScanning = false;
  bool get isScanningNow => _isScanning;

  final StreamController<bool> _isScanningController =
      StreamController<bool>.broadcast();
  Stream<bool> get isScanning => _isScanningController.stream;

  final List<ScanResult> _lastScanResults = [];
  List<ScanResult> get lastScanResults => List.unmodifiable(_lastScanResults);

  final StreamController<List<ScanResult>> _scanResultsController =
      StreamController<List<ScanResult>>.broadcast();
  Stream<List<ScanResult>> get scanResults => _scanResultsController.stream;

  // ─── 设备 ──────────────────────────────────────────────────────────────

  final Map<DeviceIdentifier, BluetoothDevice> _knownDevices = {};

  /// 当前应用已连接的设备列表。
  List<BluetoothDevice> get connectedDevices =>
      _knownDevices.values.where((d) => d.isConnected).toList();

  /// ── 事件流设置 ─────────────────────────────────────────────────────────

  StreamSubscription? _eventSubscription;

  void _setupEventStream() {
    _eventSubscription = _eventChannel.receiveBroadcastStream().listen(
      _onPlatformEvent,
      onError: (Object error) {
        print('[FlutterBluetooth] Event stream error: $error');
      },
    );
  }

  void _onPlatformEvent(dynamic event) {
    if (event is! Map) return;
    final map = Map<String, dynamic>.from(event);
    final type = map['type'] as String?;

    switch (type) {
      case 'adapterStateChanged':
        _handleAdapterStateChanged(map);
        break;
      case 'scanResult':
        _handleScanResult(map);
        break;
      case 'connectionStateChanged':
        _handleConnectionStateChanged(map);
        break;
      case 'bondStateChanged':
        _handleBondStateChanged(map);
        break;
      case 'mtuChanged':
        _handleMtuChanged(map);
        break;
      case 'characteristicNotified':
        _handleCharacteristicNotification(map);
        break;
      case 'rfcommConnectionStateChanged':
        _handleRfcommConnectionStateChanged(map);
        break;
      case 'rfcommDataReceived':
        _handleRfcommDataReceived(map);
        break;
    }
  }

  void _handleAdapterStateChanged(Map<String, dynamic> map) {
    final stateStr = map['state'] as String;
    final state = _parseAdapterState(stateStr);
    _adapterState = state;
    _adapterStateController.add(state);
  }

  void _handleScanResult(Map<String, dynamic> map) {
    final result = ScanResult.fromMap(map);
    // 更新或添加设备
    if (!_knownDevices.containsKey(result.device.remoteId)) {
      _knownDevices[result.device.remoteId] = result.device;
    }
    // 移除同一设备的旧扫描结果
    _lastScanResults.removeWhere(
        (r) => r.device.remoteId == result.device.remoteId);
    _lastScanResults.add(result);
    _scanResultsController.add(List.unmodifiable(_lastScanResults));
  }

  void _handleConnectionStateChanged(Map<String, dynamic> map) {
    final remoteId = map['remoteId'] as String;
    final stateStr = map['state'] as String;
    final state = stateStr == 'connected'
        ? BluetoothConnectionState.connected
        : BluetoothConnectionState.disconnected;

    final device = _knownDevices[remoteId];
    if (device != null) {
      device._updateConnectionState(state);
    }
  }

  void _handleBondStateChanged(Map<String, dynamic> map) {
    final remoteId = map['remoteId'] as String;
    final stateStr = map['bondState'] as String;
    final state = _parseBondState(stateStr);

    final device = _knownDevices[remoteId];
    if (device != null) {
      device._updateBondState(state);
    }
  }

  void _handleMtuChanged(Map<String, dynamic> map) {
    final remoteId = map['remoteId'] as String;
    final mtu = map['mtu'] as int;

    final device = _knownDevices[remoteId];
    if (device != null) {
      device._updateMtu(mtu);
    }
  }

  void _handleCharacteristicNotification(Map<String, dynamic> map) {
    final remoteId = map['remoteId'] as String;
    final serviceUuid = Guid.fromString(map['serviceUuid'] as String);
    final characteristicUuid =
        Guid.fromString(map['characteristicUuid'] as String);
    final value = Uint8List.fromList(List<int>.from(map['value'] as List));

    final device = _knownDevices[remoteId];
    if (device != null) {
      for (final service in device.servicesList) {
        if (service.serviceUuid == serviceUuid) {
          for (final char in service.characteristics) {
            if (char.characteristicUuid == characteristicUuid) {
              char._updateValue(value);
            }
          }
        }
      }
    }
  }

  void _handleRfcommConnectionStateChanged(Map<String, dynamic> map) {
    final remoteId = map['remoteId'] as String;
    final stateStr = map['state'] as String;
    final connected = stateStr == 'connected';

    final device = _knownDevices[remoteId];
    device?._updateRfcommConnection(connected);
  }

  void _handleRfcommDataReceived(Map<String, dynamic> map) {
    final remoteId = map['remoteId'] as String;
    final data = Uint8List.fromList(List<int>.from(map['data'] as List));

    final device = _knownDevices[remoteId];
    device?._addRfcommData(data);
  }

  // ─── 公共 API ──────────────────────────────────────────────────────────

  /// 检查设备是否支持蓝牙硬件。
  Future<bool> get isSupported async {
    try {
      final result = await _channel.invokeMethod<bool>('isSupported');
      return result ?? false;
    } catch (e) {
      return false;
    }
  }

  /// 获取本地适配器的友好名称。
  Future<String> getAdapterName() async {
    try {
      final name =
          await _channel.invokeMethod<String>('getAdapterName');
      _adapterName = name ?? '';
      return _adapterName;
    } catch (e) {
      return '';
    }
  }

  /// 开启蓝牙（仅 Android）。
  Future<void> turnOn({int timeout = 60}) async {
    await _channel.invokeMethod('turnOn', {'timeout': timeout});
  }

  /// 开始扫描经典蓝牙和 BLE 设备。
  ///
  /// [withServices] — 按广播服务 UUID 过滤扫描结果。
  /// [withRemoteIds] — 按已知设备地址过滤。
  /// [withNames] — 按精确设备名称过滤。
  /// [withKeywords] — 按设备名称子串过滤。
  /// [timeout] — 超过此时间后自动停止扫描。
  /// [scanMode] — "lowPower"、"balanced" 或 "lowLatency"。
  /// [scanClassic] — 如果为 true，同时扫描经典蓝牙（BR/EDR）设备。
  Future<void> startScan({
    List<Guid> withServices = const [],
    List<String> withRemoteIds = const [],
    List<String> withNames = const [],
    List<String> withKeywords = const [],
    Duration? timeout,
    String scanMode = 'lowLatency',
    bool scanClassic = true,
  }) async {
    if (_isScanning) {
      await stopScan();
      // 短暂延迟让适配器稳定
      await Future.delayed(const Duration(milliseconds: 100));
    }

    _lastScanResults.clear();
    _isScanning = true;
    _isScanningController.add(true);

    final args = <String, dynamic>{
      'withServices': withServices.map((g) => g.str).toList(),
      'withRemoteIds': withRemoteIds,
      'withNames': withNames,
      'withKeywords': withKeywords,
      'scanMode': scanMode,
      'scanClassic': scanClassic,
    };

    await _channel.invokeMethod('startScan', args);

    if (timeout != null) {
      Future.delayed(timeout, () {
        if (_isScanning) {
          stopScan();
        }
      });
    }
  }

  /// 停止扫描蓝牙设备。
  Future<void> stopScan() async {
    if (!_isScanning) return;
    _isScanning = false;
    _isScanningController.add(false);
    await _channel.invokeMethod('stopScan');
  }

  /// 获取系统已连接的蓝牙设备列表。
  Future<List<BluetoothDevice>> getSystemDevices(
      {List<Guid> withServices = const []}) async {
    try {
      final result = await _channel.invokeMethod<List>('getSystemDevices', {
        'withServices': withServices.map((g) => g.str).toList(),
      });
      if (result == null) return [];

      return result.map((d) {
        final device = BluetoothDevice.fromMap(
            Map<String, dynamic>.from(d as Map));
        _knownDevices[device.remoteId] = device;
        return device;
      }).toList();
    } catch (e) {
      return [];
    }
  }

  /// 获取已绑定（已配对）设备列表（仅 Android）。
  Future<List<BluetoothDevice>> getBondedDevices() async {
    try {
      final result =
          await _channel.invokeMethod<List>('getBondedDevices');
      if (result == null) return [];

      return result.map((d) {
        final device = BluetoothDevice.fromMap(
            Map<String, dynamic>.from(d as Map));
        _knownDevices[device.remoteId] = device;
        return device;
      }).toList();
    } catch (e) {
      return [];
    }
  }

  // ─── 内部方法（由 device/characteristic/descriptor 调用）────────────────

  Future<void> _connect({
    required String remoteId,
    bool autoConnect = false,
    Duration timeout = const Duration(seconds: 35),
    int? mtu,
  }) async {
    await _channel.invokeMethod('connect', {
      'remoteId': remoteId,
      'autoConnect': autoConnect,
      'timeout': timeout.inSeconds,
      'mtu': mtu,
    });
  }

  Future<void> _disconnect({
    required String remoteId,
    int timeout = 35,
  }) async {
    // 同时断开 BLE 和 RFCOMM 连接
    await _channel.invokeMethod('disconnect', {
      'remoteId': remoteId,
      'timeout': timeout,
    });
    await _channel.invokeMethod('disconnectRfcomm', {
      'remoteId': remoteId,
    });
  }

  Future<List<BluetoothService>> _discoverServices({
    required String remoteId,
    int timeout = 15,
  }) async {
    final result = await _channel.invokeMethod<List>('discoverServices', {
      'remoteId': remoteId,
      'timeout': timeout,
    });
    if (result == null) return [];

    final services = result
        .map((s) => BluetoothService.fromMap(
            Map<String, dynamic>.from(s as Map)))
        .toList();

    final device = _knownDevices[remoteId];
    device?._setServices(services);

    return services;
  }

  Future<int> _readRssi({
    required String remoteId,
    int timeout = 15,
  }) async {
    final result = await _channel.invokeMethod<int>('readRssi', {
      'remoteId': remoteId,
      'timeout': timeout,
    });
    return result ?? -127;
  }

  Future<int> _requestMtu({
    required String remoteId,
    required int desiredMtu,
    int timeout = 15,
  }) async {
    final result = await _channel.invokeMethod<int>('requestMtu', {
      'remoteId': remoteId,
      'desiredMtu': desiredMtu,
      'timeout': timeout,
    });
    return result ?? 23;
  }

  Future<void> _createBond({
    required String remoteId,
    int timeout = 90,
    List<int>? pin,
  }) async {
    await _channel.invokeMethod('createBond', {
      'remoteId': remoteId,
      'timeout': timeout,
      'pin': pin,
    });
  }

  Future<void> _removeBond({
    required String remoteId,
    int timeout = 30,
  }) async {
    await _channel.invokeMethod('removeBond', {
      'remoteId': remoteId,
      'timeout': timeout,
    });
  }

  Future<void> _clearGattCache(String remoteId) async {
    await _channel.invokeMethod('clearGattCache', {'remoteId': remoteId});
  }

  Future<void> _requestConnectionPriority({
    required String remoteId,
    required ConnectionPriority priority,
  }) async {
    final value = priority.index; // 0=balanced, 1=high, 2=lowPower
    await _channel.invokeMethod('requestConnectionPriority', {
      'remoteId': remoteId,
      'priority': value,
    });
  }

  Future<Uint8List> _readCharacteristic({
    required String remoteId,
    Guid? primaryServiceUuid,
    required Guid serviceUuid,
    required Guid characteristicUuid,
    int instanceId = 0,
    int timeout = 15,
  }) async {
    final result =
        await _channel.invokeMethod<List>('readCharacteristic', {
      'remoteId': remoteId,
      'primaryServiceUuid': primaryServiceUuid?.str,
      'serviceUuid': serviceUuid.str,
      'characteristicUuid': characteristicUuid.str,
      'instanceId': instanceId,
      'timeout': timeout,
    });
    return Uint8List.fromList(result?.cast<int>() ?? []);
  }

  Future<void> _writeCharacteristic({
    required String remoteId,
    Guid? primaryServiceUuid,
    required Guid serviceUuid,
    required Guid characteristicUuid,
    int instanceId = 0,
    required List<int> value,
    bool withoutResponse = false,
    int timeout = 15,
  }) async {
    await _channel.invokeMethod('writeCharacteristic', {
      'remoteId': remoteId,
      'primaryServiceUuid': primaryServiceUuid?.str,
      'serviceUuid': serviceUuid.str,
      'characteristicUuid': characteristicUuid.str,
      'instanceId': instanceId,
      'value': value,
      'withoutResponse': withoutResponse,
      'timeout': timeout,
    });
  }

  Future<bool> _setNotifyValue({
    required String remoteId,
    Guid? primaryServiceUuid,
    required Guid serviceUuid,
    required Guid characteristicUuid,
    int instanceId = 0,
    required bool enable,
    bool forceIndications = false,
    int timeout = 15,
  }) async {
    final result =
        await _channel.invokeMethod<bool>('setNotifyValue', {
      'remoteId': remoteId,
      'primaryServiceUuid': primaryServiceUuid?.str,
      'serviceUuid': serviceUuid.str,
      'characteristicUuid': characteristicUuid.str,
      'instanceId': instanceId,
      'enable': enable,
      'forceIndications': forceIndications,
      'timeout': timeout,
    });
    return result ?? false;
  }

  Future<Uint8List> _readDescriptor({
    required String remoteId,
    Guid? primaryServiceUuid,
    required Guid serviceUuid,
    required Guid characteristicUuid,
    int instanceId = 0,
    required Guid descriptorUuid,
    int timeout = 15,
  }) async {
    final result =
        await _channel.invokeMethod<List>('readDescriptor', {
      'remoteId': remoteId,
      'primaryServiceUuid': primaryServiceUuid?.str,
      'serviceUuid': serviceUuid.str,
      'characteristicUuid': characteristicUuid.str,
      'instanceId': instanceId,
      'descriptorUuid': descriptorUuid.str,
      'timeout': timeout,
    });
    return Uint8List.fromList(result?.cast<int>() ?? []);
  }

  Future<void> _writeDescriptor({
    required String remoteId,
    Guid? primaryServiceUuid,
    required Guid serviceUuid,
    required Guid characteristicUuid,
    int instanceId = 0,
    required Guid descriptorUuid,
    required List<int> value,
    int timeout = 15,
  }) async {
    await _channel.invokeMethod('writeDescriptor', {
      'remoteId': remoteId,
      'primaryServiceUuid': primaryServiceUuid?.str,
      'serviceUuid': serviceUuid.str,
      'characteristicUuid': characteristicUuid.str,
      'instanceId': instanceId,
      'descriptorUuid': descriptorUuid.str,
      'value': value,
      'timeout': timeout,
    });
  }

  // ─── RFCOMM（经典蓝牙 SPP）─────────────────────────────────────────────

  Future<bool> _connectRfcomm({
    required String remoteId,
    String? uuid,
  }) async {
    final result = await _channel.invokeMethod<bool>('connectRfcomm', {
      'remoteId': remoteId,
      'uuid': uuid,
    });
    return result ?? false;
  }

  Future<bool> _sendRfcommData({
    required String remoteId,
    required List<int> data,
  }) async {
    final result = await _channel.invokeMethod<bool>('sendRfcommData', {
      'remoteId': remoteId,
      'data': data,
    });
    return result ?? false;
  }

  Future<void> _disconnectRfcomm({
    required String remoteId,
  }) async {
    await _channel.invokeMethod('disconnectRfcomm', {
      'remoteId': remoteId,
    });
  }

  Future<bool> _isRfcommConnected({
    required String remoteId,
  }) async {
    final result = await _channel.invokeMethod<bool>('isRfcommConnected', {
      'remoteId': remoteId,
    });
    return result ?? false;
  }

  // ─── 辅助方法 ──────────────────────────────────────────────────────────

  BluetoothAdapterState _parseAdapterState(String state) {
    switch (state) {
      case 'on':
        return BluetoothAdapterState.on;
      case 'off':
        return BluetoothAdapterState.off;
      case 'turningOn':
        return BluetoothAdapterState.turningOn;
      case 'turningOff':
        return BluetoothAdapterState.turningOff;
      default:
        return BluetoothAdapterState.unknown;
    }
  }

  BluetoothBondState _parseBondState(String state) {
    switch (state) {
      case 'bonding':
        return BluetoothBondState.bonding;
      case 'bonded':
        return BluetoothBondState.bonded;
      default:
        return BluetoothBondState.none;
    }
  }

  /// 释放资源。在不再需要插件时调用此方法
  /// （例如在应用的 dispose 生命周期中）。
  void dispose() {
    _eventSubscription?.cancel();
    _eventSubscription = null;
  }
}
