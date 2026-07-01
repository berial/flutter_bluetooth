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

  // ─── 日志 ──────────────────────────────────────────────────────────────

  LogLevel _logLevel = LogLevel.warning;
  LogLevel get logLevel => _logLevel;

  final StreamController<String> _logsController =
      StreamController<String>.broadcast();
  /// 日志流。受 [setLogLevel] 控制的级别过滤。
  Stream<String> get logs => _logsController.stream;

  // ─── 扫描 ──────────────────────────────────────────────────────────────

  bool _isScanning = false;
  bool get isScanningNow => _isScanning;

  // N1: 分别跟踪 BLE 和 Classic 两侧扫描状态，两侧都结束才推 false
  bool _isBleScanning = false;
  bool _isClassicScanning = false;

  final StreamController<bool> _isScanningController =
      StreamController<bool>.broadcast();
  Stream<bool> get isScanning => _isScanningController.stream;

  final List<ScanResult> _lastScanResults = [];
  List<ScanResult> get lastScanResults => List.unmodifiable(_lastScanResults);

  final StreamController<List<ScanResult>> _scanResultsController =
      StreamController<List<ScanResult>>.broadcast();
  List<ScanResult>? _lastScanResultsSnapshot;
  /// 扫描结果流（累积）。重新监听会重发上次结果。
  Stream<List<ScanResult>> get scanResults => _BehaviorStream(
      _scanResultsController.stream, () => _lastScanResultsSnapshot);

  // 单条扫描结果流，不重发历史。
  final StreamController<ScanResult> _onScanResultController =
      StreamController<ScanResult>.broadcast();
  /// 单条扫描结果流（不重发历史）。每个新结果推送一次。
  Stream<ScanResult> get onScanResults => _onScanResultController.stream;

  // ─── RFCOMM 服务器状态 ──────────────────────────────────────────────────

  final StreamController<RfcommServerState> _rfcommServerStateController =
      StreamController<RfcommServerState>.broadcast();
  /// RFCOMM 服务器状态变化流。
  Stream<RfcommServerState> get rfcommServerState =>
      _rfcommServerStateController.stream;

  bool _isServerRunning = false;
  /// 当前 RFCOMM 服务器是否正在运行（同步）。
  bool get isServerRunning => _isServerRunning;

  String? _serverUuid;
  /// 当前服务器使用的 UUID（运行后有效）。
  String? get serverUuid => _serverUuid;

  // ─── 配对请求处理 ────────────────────────────────────────────────────────

  final StreamController<PairingRequest> _pairingRequestController =
      StreamController<PairingRequest>.broadcast();
  /// 配对请求事件流。
  ///
  /// 启用配对请求处理后，系统发起配对时推送事件。
  /// Dart 端需在 [PairingVariant.needsResponse] 为 true 时调用
  /// [respondPairingRequest] 响应，否则配对会超时失败。
  Stream<PairingRequest> get pairingRequest => _pairingRequestController.stream;

  /// 启用配对请求处理。
  ///
  /// 启用后，所有配对请求将拦截并推送至 [pairingRequest] 流，
  /// 系统默认配对 UI 不会出现。
  Future<bool> enablePairingRequestHandling() async {
    return await _channel.invokeMethod<bool>('enablePairingRequestHandling') ?? false;
  }

  /// 禁用配对请求处理，恢复系统默认配对 UI。
  Future<void> disablePairingRequestHandling() async {
    await _channel.invokeMethod('disablePairingRequestHandling');
  }

  /// 响应配对请求 — 设置 PIN。
  ///
  /// 适用于 [PairingVariant.pin] 变体。
  /// 返回是否成功设置（设备未在 pending 列表中返回 false）。
  Future<bool> respondPairingPin({
    required String remoteId,
    required String pin,
  }) async {
    return await _channel.invokeMethod<bool>('respondPairingRequest', {
      'remoteId': remoteId,
      'responseType': 'pin',
      'pin': pin,
    }) ?? false;
  }

  /// 响应配对请求 — 确认或拒绝。
  ///
  /// 适用于 [PairingVariant.passkeyConfirmation] 和 [PairingVariant.consent] 变体。
  Future<bool> respondPairingConfirmation({
    required String remoteId,
    required bool confirm,
  }) async {
    return await _channel.invokeMethod<bool>('respondPairingRequest', {
      'remoteId': remoteId,
      'responseType': 'confirmation',
      'confirm': confirm,
    }) ?? false;
  }

  // ─── 设备 ──────────────────────────────────────────────────────────────

  final Map<DeviceIdentifier, BluetoothDevice> _knownDevices = {};

  /// 每设备的串行操作队列（perDevice 模式）。
  /// 同一设备的 GATT 操作串行执行，避免 GATT_BUSY (133) 错误。
  final Map<DeviceIdentifier, _OperationQueue> _deviceQueues = {};

  /// 获取（或创建）指定设备的操作队列。
  _OperationQueue _getQueue(DeviceIdentifier remoteId) {
    return _deviceQueues.putIfAbsent(remoteId, () => _OperationQueue());
  }

  /// 移除设备队列（设备彻底断开后清理）。
  void _removeQueue(DeviceIdentifier remoteId) {
    _deviceQueues[remoteId]?.clear();
    _deviceQueues.remove(remoteId);
  }

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
      case 'rfcommServerStateChanged':
        _handleRfcommServerStateChanged(map);
        break;
      case 'pairingRequest':
        _handlePairingRequest(map);
        break;
      case 'scanStopped':
        _handleScanStopped(map);
        break;
      case 'scanError':
        _handleScanError(map);
        break;
      case 'log':
        _handleLog(map);
        break;
    }
  }

  void _handleLog(Map<String, dynamic> map) {
    final levelStr = map['level'] as String? ?? 'INFO';
    final message = map['message'] as String? ?? '';
    final timestamp = map['timestamp'] as int? ?? 0;
    final time = DateTime.fromMillisecondsSinceEpoch(timestamp);
    final line = '[${time.toIso8601String()}] [$levelStr] $message';
    _logsController.add(line);
  }

  void _handleRfcommServerStateChanged(Map<String, dynamic> map) {
    final stateStr = map['state'] as String? ?? 'stopped';
    final running = stateStr == 'started';
    _isServerRunning = running;
    final uuid = map['uuid'] as String?;
    if (running) {
      _serverUuid = uuid;
    } else {
      _serverUuid = null;
    }
    _rfcommServerStateController.add(
      RfcommServerState(isRunning: running, uuid: _serverUuid),
    );
  }

  void _handlePairingRequest(Map<String, dynamic> map) {
    final remoteId = map['remoteId'] as String;
    final variantStr = map['variant'] as String? ?? 'unknown';
    final variantCode = (map['variantCode'] as num?)?.toInt() ?? -1;
    final pairingKey = (map['pairingKey'] as num?)?.toInt() ?? -1;

    final request = PairingRequest(
      remoteId: remoteId,
      variant: PairingVariant.fromString(variantStr),
      variantCode: variantCode,
      pairingKey: pairingKey,
    );
    _pairingRequestController.add(request);
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
    final snapshot = List<ScanResult>.unmodifiable(_lastScanResults);
    _lastScanResultsSnapshot = snapshot;
    _scanResultsController.add(snapshot);
    // 同时推送单条流
    _onScanResultController.add(result);
  }

  void _handleConnectionStateChanged(Map<String, dynamic> map) {
    final remoteId = map['remoteId'] as String;
    final stateStr = map['state'] as String;
    final state = stateStr == 'connected'
        ? BluetoothConnectionState.connected
        : BluetoothConnectionState.disconnected;

    // B5: 接收原生上报的断开原因（status + HCI 状态字符串）
    final disconnectReasonCode = map['disconnectReasonCode'] as int?;
    final disconnectReasonString = map['disconnectReasonString'] as String?;

    final device = _knownDevices[remoteId];
    if (device != null) {
      if (state == BluetoothConnectionState.disconnected &&
          disconnectReasonCode != null &&
          disconnectReasonCode != 0) {
        device._setDisconnectReason(disconnectReasonCode, disconnectReasonString);
      }
      device._updateConnectionState(state);
    }
    // 设备完全断开（BLE + RFCOMM 都断）时清理操作队列
    if (state == BluetoothConnectionState.disconnected) {
      final d = _knownDevices[remoteId];
      if (d == null || (!d._isBleConnected && !d._isRfcommConnected)) {
        _removeQueue(remoteId);
      }
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

    // 服务器模式接受的传入连接可能来自未扫描设备，自动创建并注册
    final device = _knownDevices.putIfAbsent(
      remoteId,
      () => BluetoothDevice(remoteId: remoteId),
    );
    device._updateRfcommConnection(connected);
  }

  void _handleRfcommDataReceived(Map<String, dynamic> map) {
    final remoteId = map['remoteId'] as String;
    // 原生直接传 ByteArray，StandardMessageCodec 解码为 Uint8List
    final data = map['data'] as Uint8List;

    // 服务器模式接受的设备可能尚未注册，自动创建
    final device = _knownDevices.putIfAbsent(
      remoteId,
      () => BluetoothDevice(remoteId: remoteId),
    );
    device._addRfcommData(data);
  }

  void _handleScanError(Map<String, dynamic> map) {
    final errorCode = map['errorCode'] as int? ?? -1;
    final message = map['message'] as String? ?? '';
    print('[FlutterBluetooth] Scan failed (code=$errorCode): $message');
    // Q2: scanError 事件需回滚扫描状态。按 source 区分（与 scanStopped 一致）
    // 原生未带 source 时默认 ble（BLE 侧 onScanFailed 不带 source）
    final source = map['source'] as String? ?? 'ble';
    if (source == 'classic') {
      _isClassicScanning = false;
    } else {
      _isBleScanning = false;
    }
    if (!_isBleScanning && !_isClassicScanning && _isScanning) {
      _isScanning = false;
      _isScanningController.add(false);
    }
  }

  void _handleScanStopped(Map<String, dynamic> map) {
    // N1: 按 source 区分，两侧都结束才推 false
    final source = map['source'] as String? ?? 'ble';
    if (source == 'classic') {
      _isClassicScanning = false;
    } else {
      _isBleScanning = false;
    }
    if (!_isBleScanning && !_isClassicScanning && _isScanning) {
      _isScanning = false;
      _isScanningController.add(false);
    }
  }

  // ─── 公共 API ──────────────────────────────────────────────────────────

  /// 检查设备是否支持蓝牙硬件。
  Future<bool> get isSupported async {
    try {
      final result = await _channel.invokeMethod<bool>('isSupported');
      return result ?? false;
    } catch (e, st) {
      print('[FlutterBluetooth] isSupported error: $e\n$st');
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
    } catch (e, st) {
      print('[FlutterBluetooth] getAdapterName error: $e\n$st');
      return '';
    }
  }

  /// 开启蓝牙（仅 Android）。
  Future<void> turnOn({int timeout = 60}) async {
    await _channel.invokeMethod('turnOn', {'timeout': timeout});
  }

  /// 关闭蓝牙（仅 Android，Android 13+ 已弃用直接关闭）。
  Future<void> turnOff() async {
    await _channel.invokeMethod('turnOff');
  }

  /// 设置日志级别。仅 [LogLevel.error] 及以上级别会通过 [logs] 流输出。
  Future<void> setLogLevel(LogLevel level, {bool color = true}) async {
    _logLevel = level;
    await _channel.invokeMethod('setLogLevel', {'level': level.index});
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
    // N1: 两侧分别跟踪
    _isBleScanning = true;
    _isClassicScanning = scanClassic;
    _isScanningController.add(true);

    final args = <String, dynamic>{
      'withServices': withServices.map((g) => g.str).toList(),
      'withRemoteIds': withRemoteIds,
      'withNames': withNames,
      'withKeywords': withKeywords,
      'scanMode': scanMode,
      'scanClassic': scanClassic,
    };

    // Q2: 原生 startScan 是 fire-and-forget，错误通过 scanError 事件回滚状态
    // （见 _handleScanError），此处不需 try-catch
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
    // N1: 主动停止时两侧都清，避免原生 scanStopped 事件延迟到达
    _isBleScanning = false;
    _isClassicScanning = false;
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
    } catch (e, st) {
      print('[FlutterBluetooth] getSystemDevices error: $e\n$st');
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
    } catch (e, st) {
      print('[FlutterBluetooth] getBondedDevices error: $e\n$st');
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
    String? pin,
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
    // Q4: 未连接时原生抛 PlatformException(NOT_CONNECTED)，调用方需 try-catch
    // 写入失败返回 false，区分两种错误状态
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

  Future<Uint8List?> _readRfcommData({
    required String remoteId,
    int maxSize = 1024,
  }) async {
    final result = await _channel.invokeMethod<List>('readRfcommData', {
      'remoteId': remoteId,
      'maxSize': maxSize,
    });
    if (result == null) return null;
    return Uint8List.fromList(result.cast<int>());
  }

  // ─── RFCOMM 服务器模式 ────────────────────────────────────────────────

  /// 启动 RFCOMM 服务器，监听传入连接（默认 SPP UUID）。
  ///
  /// 启动后通过 [rfcommServerState] 流推送状态变化，
  /// 接受到的连接会以普通 RFCOMM 连接形式出现（通过各 [BluetoothDevice]
  /// 的 [BluetoothDevice.onRfcommDataReceived] 接收数据）。
  Future<bool> startServer({String? uuid, String? name}) async {
    final result = await _channel.invokeMethod<bool>('startServer', {
      'uuid': uuid,
      'name': name,
    });
    return result ?? false;
  }

  /// 停止 RFCOMM 服务器。已建立的连接不受影响。
  Future<void> stopServer() async {
    await _channel.invokeMethod('stopServer');
  }

  // ─── 订阅生命周期辅助 ─────────────────────────────────────────────────

  /// 在扫描结束时自动取消订阅。
  ///
  /// 返回的 [StreamSubscription] 在 [isScanning] 变为 false 时自动取消。
  /// 适用于绑定到单次扫描生命周期的临时监听。
  StreamSubscription<T> cancelWhenScanComplete<T>(StreamSubscription<T> sub) {
    final scanSub = isScanning.listen((scanning) {
      if (!scanning) {
        sub.cancel();
      }
    });
    sub.onDone(() => scanSub.cancel());
    return sub;
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
      case 'unauthorized':
        return BluetoothAdapterState.unauthorized;
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
    _adapterStateController.close();
    _isScanningController.close();
    _scanResultsController.close();
    _onScanResultController.close();
    _logsController.close();
    _rfcommServerStateController.close();
    _pairingRequestController.close();
  }
}
