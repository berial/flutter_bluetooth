part of '../flutter_bluetooth.dart';

/// 表示一个远程蓝牙设备（经典蓝牙或 BLE）。
///
/// API 模仿 `flutter_blue_plus` BluetoothDevice。
class BluetoothDevice {
  static final _devices = <DeviceIdentifier, BluetoothDevice>{};

  /// 该设备的唯一硬件地址（例如 "AA:BB:CC:11:22:33"）。
  final DeviceIdentifier remoteId;

  /// 平台为该设备广播的名称。
  String get platformName => _platformName;
  String _platformName;

  /// 扫描期间发现的名称（广播名称）。
  String get advName => _advName;
  String _advName;

  /// 设备类型："classic"、"ble" 或 "dual"
  String get type => _type;
  String _type;

  /// 该设备已发现的服务缓存列表。
  List<BluetoothService> _servicesList = [];
  List<BluetoothService> get servicesList => List.unmodifiable(_servicesList);

  /// ── 连接状态 ──────────────────────────────────────────────────────────

  /// 当前连接状态（含中间态）。
  BluetoothConnectionState _connectionState = BluetoothConnectionState.disconnected;

  /// 当前连接状态（同步，含中间态）。
  BluetoothConnectionState get connectionStateNow => _connectionState;

  /// 是否处于连接中（中间态）。
  bool get isConnecting => _connectionState.isConnecting;

  /// 是否处于断开中（中间态）。
  bool get isDisconnecting => _connectionState.isDisconnecting;

  /// 是否已稳定连接（终态）。
  bool get isConnected => _connectionState.isConnected;

  /// 是否已稳定断开（终态）。
  bool get isDisconnected => _connectionState.isDisconnected;

  /// 分别跟踪 BLE GATT 和 RFCOMM 连接状态，避免互相覆盖。
  bool _isBleConnected = false;
  bool _isRfcommConnected = false;
  bool get isRfcommConnected => _isRfcommConnected;

  /// 最近一次断开原因（断开后才有值）。
  DisconnectReason? _disconnectReason;
  DisconnectReason? get disconnectReason => _disconnectReason;

  final StreamController<BluetoothConnectionState> _connectionStateController =
      StreamController<BluetoothConnectionState>.broadcast();
  Stream<BluetoothConnectionState> get connectionState =>
      _connectionStateController.stream;

  /// 连接中用于取消的 Completer。null 表示当前无进行中的连接尝试。
  Completer<void>? _connectCompleter;

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

  /// 获取同一地址的设备实例，连接状态与其他入口共享。
  factory BluetoothDevice({
    required DeviceIdentifier remoteId,
    String platformName = '',
    String advName = '',
    String? type,
  }) {
    final address = remoteId.toUpperCase();
    final device =
        _devices.putIfAbsent(address, () => BluetoothDevice._(address));
    if (platformName.isNotEmpty) device._platformName = platformName;
    if (advName.isNotEmpty) device._advName = advName;
    if (type != null) device._type = type;
    return device;
  }

  BluetoothDevice._(this.remoteId)
      : _platformName = '',
        _advName = '',
        _type = 'ble';

  /// 从 map（原生平台数据）创建。
  factory BluetoothDevice.fromMap(Map<String, dynamic> map) {
    return BluetoothDevice(
      remoteId: map['remoteId'] as String,
      platformName: map['platformName'] as String? ?? '',
      advName: map['advName'] as String? ?? '',
      type: map['type'] as String?,
    );
  }

  /// 从远程 ID 字符串创建的便捷工厂方法。
  factory BluetoothDevice.fromId(String remoteId) {
    return BluetoothDevice(remoteId: remoteId);
  }

  /// ── 连接 ──────────────────────────────────────────────────────────────

  /// 通过 BLE GATT 连接到该设备。
  ///
  /// 调用时立即推送 [BluetoothConnectionState.connecting] 中间态，
  /// 原生确认连接就绪后推送 [BluetoothConnectionState.connected]。
  /// 连接失败或被 [cancelConnect] 取消则推送 [BluetoothConnectionState.disconnected]。
  ///
  /// [autoConnect] — 如果为 true，Android 将在设备可用时自动重连。
  /// [timeout] — 连接超时，默认 35 秒。超时自动取消并推送断开。
  Future<void> connect({
    bool autoConnect = false,
    Duration timeout = const Duration(seconds: 35),
    int? mtu,
  }) async {
    if (_connectCompleter != null || _isBleConnected || isDisconnecting) return;
    final bluetooth = FlutterBluetooth.instance;
    final canceled = _connectCompleter = Completer<void>();
    _disconnectReason = null;
    _setConnectionState(BluetoothConnectionState.connecting);
    try {
      final attempt = Future.any<void>([
        bluetooth._connect(
            remoteId: remoteId, autoConnect: autoConnect, mtu: mtu),
        canceled.future,
      ]);
      if (autoConnect) {
        await attempt;
      } else {
        await attempt.timeout(timeout, onTimeout: () {
          final error = _connectError(FbpErrorCode.timeout, 'Connect timeout');
          unawaited(bluetooth._disconnectBle(remoteId).catchError((Object _) {}));
          throw error;
        });
      }
      _isBleConnected = true;
      _setConnectionState(BluetoothConnectionState.connected);
    } catch (e) {
      _disconnectReason ??= DisconnectReason(
        platform: ErrorPlatform.android,
        code: FbpErrorCode.unknown.index,
        description: 'Connect failed: $e',
      );
      if (!isDisconnecting) {
        _setConnectionState(_isRfcommConnected
            ? BluetoothConnectionState.connected
            : BluetoothConnectionState.disconnected);
      }
      rethrow;
    } finally {
      _connectCompleter = null;
    }
  }

  /// 取消进行中的连接尝试。
  ///
  /// 正在等待 [connect] 时，以 `connectionCanceled` 结束该次尝试。
  void cancelConnect() {
    final canceled = _connectCompleter;
    if (canceled == null || canceled.isCompleted) return;
    canceled.completeError(_connectError(
        FbpErrorCode.connectionCanceled, 'Connection canceled by user'));
    unawaited(FlutterBluetooth.instance._disconnectBle(remoteId)
        .catchError((Object _) {}));
  }

  /// 记录由 Dart 发起的连接取消或超时。
  FlutterBluetoothException _connectError(FbpErrorCode code, String message) {
    _disconnectReason = DisconnectReason(
      platform: ErrorPlatform.fbp,
      code: code.index,
      description: message,
    );
    return FlutterBluetoothException(
      platform: ErrorPlatform.fbp,
      function: 'connect',
      code: code.name,
      description: message,
    );
  }

  /// 断开与该设备的连接（包括 BLE GATT 和 RFCOMM）。
  ///
  /// 调用时立即推送 [BluetoothConnectionState.disconnecting] 中间态，
  /// 底层断开完成后由原生事件推送 [BluetoothConnectionState.disconnected]。
  Future<void> disconnect({int timeout = 35}) async {
    if (isDisconnecting) return;

    _setConnectionState(BluetoothConnectionState.disconnecting);
    final canceled = _connectCompleter;
    if (canceled != null && !canceled.isCompleted) {
      canceled.completeError(_connectError(
          FbpErrorCode.connectionCanceled, 'Connection canceled by user'));
    }
    try {
      await FlutterBluetooth.instance._disconnect(
        remoteId: remoteId,
        timeout: timeout,
      );
      _isBleConnected = false;
      _clearBleServices();
      _isRfcommConnected = false;
      _setConnectionState(BluetoothConnectionState.disconnected);
    } catch (_) {
      _setConnectionState(_isBleConnected || _isRfcommConnected
          ? BluetoothConnectionState.connected
          : BluetoothConnectionState.disconnected);
      rethrow;
    }
  }

  /// ── 服务 ──────────────────────────────────────────────────────────────

  /// 发现服务、特征和描述符。
  ///
  /// 入队执行，保证与同设备其他 GATT 操作串行。
  Future<List<BluetoothService>> discoverServices({int timeout = 15}) async {
    return FlutterBluetooth.instance._getQueue(remoteId).enqueue(() async {
      final services = await FlutterBluetooth.instance._discoverServices(
        remoteId: remoteId,
        timeout: timeout,
      );
      _servicesList = services;
      return services;
    });
  }

  /// ── RSSI ──────────────────────────────────────────────────────────────

  /// 读取已连接设备的当前 RSSI。
  ///
  /// 入队执行，保证与同设备其他 GATT 操作串行。
  Future<int> readRssi({int timeout = 15}) async {
    return FlutterBluetooth.instance._getQueue(remoteId).enqueue(() async {
      return FlutterBluetooth.instance._readRssi(
        remoteId: remoteId,
        timeout: timeout,
      );
    });
  }

  /// ── MTU ──────────────────────────────────────────────────────────────

  /// 请求新的 MTU 大小（仅 Android）。
  ///
  /// 入队执行，保证与同设备其他 GATT 操作串行。
  Future<int> requestMtu(int desiredMtu, {int timeout = 15}) async {
    return FlutterBluetooth.instance._getQueue(remoteId).enqueue(() async {
      final result = await FlutterBluetooth.instance._requestMtu(
        remoteId: remoteId,
        desiredMtu: desiredMtu,
        timeout: timeout,
      );
      _mtuNow = result;
      _mtuController.add(result);
      return result;
    });
  }

  /// ── 配对/绑定（仅 Android）────────────────────────────────────────────

  /// 发起与该设备的配对（绑定）。
  ///
  /// **配对请求处理**：
  /// - 若 [pin] 非空，会自动启用配对请求处理；Dart 端需额外监听
  ///   [FlutterBluetooth.pairingRequest] 流并在 PIN 变体请求到达时
  ///   调用 [FlutterBluetooth.respondPairingPin] 响应。
  /// - 若 [pin] 为空且未启用 [FlutterBluetooth.enablePairingRequestHandling]，
  ///   配对由系统默认 UI 处理。
  ///
  /// 入队执行，保证与同设备其他 GATT 操作串行。
  Future<void> createBond({int timeout = 90, String? pin}) async {
    return FlutterBluetooth.instance._getQueue(remoteId).enqueue(() async {
      return FlutterBluetooth.instance._createBond(
        remoteId: remoteId,
        timeout: timeout,
        pin: pin,
      );
    });
  }

  /// 移除现有绑定（取消配对）。
  ///
  /// 入队执行，保证与同设备其他 GATT 操作串行。
  Future<void> removeBond({int timeout = 30}) async {
    return FlutterBluetooth.instance._getQueue(remoteId).enqueue(() async {
      return FlutterBluetooth.instance._removeBond(
        remoteId: remoteId,
        timeout: timeout,
      );
    });
  }

  /// 清除该设备的 GATT 缓存（仅 Android）。
  ///
  /// 入队执行，保证与同设备其他 GATT 操作串行。
  Future<void> clearGattCache() async {
    return FlutterBluetooth.instance._getQueue(remoteId).enqueue(() async {
      await FlutterBluetooth.instance._clearGattCache(remoteId);
      _clearBleServices();
    });
  }

  /// ── 连接优先级（仅 Android）───────────────────────────────────────────

  /// 请求更改连接优先级。
  ///
  /// 入队执行，保证与同设备其他 GATT 操作串行。
  Future<void> requestConnectionPriority({
    required ConnectionPriority connectionPriorityRequest,
  }) async {
    return FlutterBluetooth.instance._getQueue(remoteId).enqueue(() async {
      return FlutterBluetooth.instance._requestConnectionPriority(
        remoteId: remoteId,
        priority: connectionPriorityRequest,
      );
    });
  }

  /// ── 数据通信 ──────────────────────────────────────────────────────────

  /// **经典蓝牙** — 通过 RFCOMM SPP 套接字连接进行串口数据传输。
  ///
  /// 连接成功后，使用 [sendRfcommData] 发送字节，
  /// 监听 [onRfcommDataReceived] 接收传入数据。
  ///
  /// [uuid] — 可选的自定义 SPP 服务 UUID。
  ///          默认使用标准 `00001101-0000-1000-8000-00805F9B34FB`。
  /// [androidDelay] — 连接前等待的延迟（毫秒），用于规避 Android
  ///                  蓝牙栈在快速 connect/disconnect 时的竞态。默认 2000ms。
  Future<bool> connectRfcomm({String? uuid, int androidDelay = 2000}) async {
    if (androidDelay > 0) {
      await Future.delayed(Duration(milliseconds: androidDelay));
    }
    return FlutterBluetooth.instance._connectRfcomm(
      remoteId: remoteId,
      uuid: uuid,
    );
  }

  /// **经典蓝牙** — 通过 RFCOMM 套接字发送原始字节数据。
  ///
  /// 入队执行，保证与同设备其他 GATT/RFCOMM 操作串行。
  /// 返回值：
  /// - `true`：写入成功
  /// - `false`：写入失败（IOException）
  /// - 抛 `PlatformException(NOT_CONNECTED)`：设备未连接
  Future<bool> sendRfcommData(List<int> data) async {
    return FlutterBluetooth.instance._getQueue(remoteId).enqueue(() async {
      return FlutterBluetooth.instance._sendRfcommData(
        remoteId: remoteId,
        data: data,
      );
    });
  }

  /// **经典蓝牙** — 同步读取一次数据（阻塞直到读到数据或超时）。
  ///
  /// 数据由后台 [readLoop] 派发，不直接读取 InputStream，
  /// 因此不会与 [onRfcommDataReceived] 流抢数据。
  /// 超过 [maxSize] 的余量由后续调用读取。没有余量且设备未连接，
  /// 或读取超时时，返回 null。
  Future<Uint8List?> readRfcommData({int maxSize = 1024}) async {
    return FlutterBluetooth.instance._getQueue(remoteId).enqueue(() async {
      return FlutterBluetooth.instance._readRfcommData(
        remoteId: remoteId,
        maxSize: maxSize,
      );
    });
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
  /// 同一服务和特征共用通知订阅，首个订阅决定 [forceIndications]。
  /// BLE 断开后数据流关闭，重连后可重新获取。
  Stream<Uint8List> getBleDataStream({
    required Guid serviceUuid,
    required Guid characteristicUuid,
    bool forceIndications = false,
  }) {
    final key = (serviceUuid, characteristicUuid);
    return _bleDataStreamControllers.putIfAbsent(key, () {
      StreamSubscription<Uint8List>? subscription;
      var generation = 0;
      final controller = StreamController<Uint8List>.broadcast();
      controller.onListen = () async {
        final current = ++generation;
        try {
          if (_servicesList.isEmpty) await discoverServices();
          if (current != generation || controller.isClosed) return;
          final characteristic = _servicesList
              .where((service) => service.serviceUuid == serviceUuid)
              .expand((service) => service.characteristics)
              .firstWhere((char) => char.characteristicUuid == characteristicUuid);
          subscription = characteristic.onValueReceived.listen(
            controller.add,
            onError: controller.addError,
          );
          final enabled = await characteristic.setNotifyValue(
            true,
            forceIndications: forceIndications,
          );
          if (!enabled) throw StateError('Could not enable notifications');
        } catch (error, stack) {
          if (current != generation || controller.isClosed) return;
          unawaited(subscription?.cancel());
          subscription = null;
          controller.addError(error, stack);
          _bleDataStreamControllers.remove(key);
          unawaited(controller.close());
        }
      };
      controller.onCancel = () {
        generation++;
        unawaited(subscription?.cancel());
        subscription = null;
      };
      return controller;
    }).stream;
  }

  /// 按服务和特征缓存共享的数据流。
  final _bleDataStreamControllers =
      <(Guid, Guid), StreamController<Uint8List>>{};

  /// 清理断开连接后失效的服务与通知订阅。
  void _clearBleServices() {
    _servicesList = [];
    for (final controller in _bleDataStreamControllers.values) {
      controller.onCancel?.call();
      unawaited(controller.close());
    }
    _bleDataStreamControllers.clear();
  }

  /// ── 生命周期 ──────────────────────────────────────────────────────────

  /// 在该设备断开连接时自动取消订阅。
  ///
  /// [next] — 为 true 时仅在"下一次"断开时取消，之后再断开不取消。
  /// [delayed] — 为 true 时延迟一小段时间再取消，避免在断开回调中
  ///             仍在读取流数据的竞态。
  StreamSubscription<T> cancelWhenDisconnected<T>(
      StreamSubscription<T> sub, {bool next = false, bool delayed = false}) {
    StreamSubscription? connSub;
    void onState(BluetoothConnectionState state) {
      if (state == BluetoothConnectionState.disconnected) {
        final cancel = () {
          sub.cancel();
          connSub?.cancel();
        };
        if (delayed) {
          Future.delayed(const Duration(milliseconds: 100), cancel);
        } else {
          cancel();
        }
      }
    }

    connSub = connectionState.listen(onState);
    sub.onDone(() => connSub?.cancel());
    return sub;
  }

  /// 释放该设备持有的所有资源（流控制器等）。
  void dispose() {
    if (identical(_devices[remoteId], this)) _devices.remove(remoteId);
    _clearBleServices();
    _connectionStateController.close();
    _bondStateController.close();
    _mtuController.close();
    _rfcommDataController.close();
  }

  /// ── 内部状态更新 ──────────────────────────────────────────────────────

  /// 向监听者发布发生变化的连接状态。
  void _setConnectionState(BluetoothConnectionState state) {
    if (_connectionState == state || _connectionStateController.isClosed) return;
    _connectionState = state;
    _connectionStateController.add(state);
  }

  /// 更新 BLE 链路，保留同一设备的 RFCOMM 状态。
  void _updateConnectionState(BluetoothConnectionState state) {
    final connected = state == BluetoothConnectionState.connected;
    if (connected && (_connectCompleter != null || isDisconnecting)) return;
    _isBleConnected = connected;
    if (!connected) {
      _clearBleServices();
      _disconnectReason ??= const DisconnectReason(
        platform: ErrorPlatform.android,
        code: -1,
        description: 'Connection lost',
      );
    }
    if (isDisconnecting) return;
    final canceled = _connectCompleter;
    if (!connected && canceled != null && !canceled.isCompleted) {
      final reason = _disconnectReason!;
      canceled.completeError(FlutterBluetoothException(
        platform: reason.platform,
        function: 'connect',
        code: reason.code.toString(),
        description: reason.description,
      ));
    }
    _setConnectionState(connected || _isRfcommConnected
        ? BluetoothConnectionState.connected
        : BluetoothConnectionState.disconnected);
  }

  /// B5: 设置原生上报的断开原因（status + HCI 状态字符串）。
  /// 仅在断开且 status != 0 时由 plugin 调用，覆盖默认的 'Connection lost'。
  void _setDisconnectReason(int code, String? description) {
    _disconnectReason = DisconnectReason(
      platform: ErrorPlatform.android,
      code: code,
      description: description ?? 'GATT error $code',
    );
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
    if (_connectCompleter != null || isDisconnecting) return;
    if (connected) {
      _setConnectionState(BluetoothConnectionState.connected);
    } else if (!_isBleConnected) {
      _setConnectionState(BluetoothConnectionState.disconnected);
    }
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
