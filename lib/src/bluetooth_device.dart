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
  /// 调用时立即推送 [BluetoothConnectionState.connecting] 中间态，
  /// 连接成功后由原生事件推送 [BluetoothConnectionState.connected]。
  /// 连接失败或被 [cancelConnect] 取消则推送 [BluetoothConnectionState.disconnected]。
  ///
  /// [autoConnect] — 如果为 true，Android 将在设备可用时自动重连。
  /// [timeout] — 连接超时，默认 35 秒。超时自动取消并推送断开。
  Future<void> connect({
    bool autoConnect = false,
    Duration timeout = const Duration(seconds: 35),
    int? mtu,
  }) async {
    // 防止重复连接
    if (isConnecting || isConnected) return;

    _setConnectionState(BluetoothConnectionState.connecting);
    _connectCompleter = Completer<void>();

    // 超时定时器
    Timer? timeoutTimer;
    bool timedOut = false;
    if (!autoConnect) {
      timeoutTimer = Timer(timeout, () {
        if (_connectCompleter?.isCompleted == false) {
          timedOut = true;
          _disconnectReason = DisconnectReason(
            platform: ErrorPlatform.fbp,
            code: FbpErrorCode.timeout.index,
            description: 'Connect timeout after ${timeout.inSeconds}s',
          );
          cancelConnect();
        }
      });
    }

    try {
      await FlutterBluetooth.instance._connect(
        remoteId: remoteId,
        autoConnect: autoConnect,
        timeout: timeout,
        mtu: mtu,
      );
      // 成功 — 等待原生推送 connected 事件，由 _setConnectionState 完成 Completer
      await _connectCompleter!.future;
    } catch (e) {
      // 原生调用本身失败（权限/参数等）
      _setConnectionState(BluetoothConnectionState.disconnected);
      _disconnectReason = DisconnectReason(
        platform: ErrorPlatform.android,
        code: FbpErrorCode.unknown.index,
        description: 'Connect failed: $e',
      );
      if (!_connectCompleter!.isCompleted) _connectCompleter!.complete();
      rethrow;
    } finally {
      timeoutTimer?.cancel();
    }

    if (timedOut) {
      throw FlutterBluetoothException(
        platform: ErrorPlatform.fbp,
        function: 'connect',
        code: FbpErrorCode.timeout.name,
        description: 'Connect timeout',
      );
    }
  }

  /// 取消进行中的连接尝试。
  ///
  /// 仅在 [isConnecting] 时有效，立即推送 [BluetoothConnectionState.disconnected]，
  /// 并触发 [connect] 调用方的异常或正常返回（取决于底层是否已连上）。
  void cancelConnect() {
    if (!isConnecting) return;
    _disconnectReason = DisconnectReason(
      platform: ErrorPlatform.fbp,
      code: FbpErrorCode.connectionCanceled.index,
      description: 'Connection canceled by user',
    );
    // 主动断开底层 GATT（若已建立）
    FlutterBluetooth.instance._disconnect(remoteId: remoteId, timeout: 5);
    _setConnectionState(BluetoothConnectionState.disconnected);
    if (_connectCompleter?.isCompleted == false) {
      _connectCompleter!.complete();
    }
  }

  /// 断开与该设备的连接（包括 BLE GATT 和 RFCOMM）。
  ///
  /// 调用时立即推送 [BluetoothConnectionState.disconnecting] 中间态，
  /// 底层断开完成后由原生事件推送 [BluetoothConnectionState.disconnected]。
  Future<void> disconnect({int timeout = 35}) async {
    if (isDisconnected || isDisconnecting) return;

    _setConnectionState(BluetoothConnectionState.disconnecting);
    try {
      await FlutterBluetooth.instance._disconnect(
        remoteId: remoteId,
        timeout: timeout,
      );
    } catch (e) {
      // 即使断开失败也强制推送 disconnected 终态
      _setConnectionState(BluetoothConnectionState.disconnected);
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
      return FlutterBluetooth.instance._clearGattCache(remoteId);
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
  /// 设备未连接或读取超时返回 null。
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
    _bleDataStreamController?.close();
    _connectionStateController.close();
    _bondStateController.close();
    _mtuController.close();
    _rfcommDataController.close();
  }

  /// ── 内部状态更新 ──────────────────────────────────────────────────────

  /// 统一的状态切换入口。处理 completer 完成、断开原因、流推送。
  void _setConnectionState(BluetoothConnectionState state) {
    _connectionState = state;
    _connectionStateController.add(state);

    // 连接成功 — 完成等待中的 completer
    if (state == BluetoothConnectionState.connected) {
      if (_connectCompleter?.isCompleted == false) {
        _connectCompleter!.complete();
      }
    }
    // 断开终态 — 若有等待中的连接 completer，标记失败
    else if (state == BluetoothConnectionState.disconnected) {
      if (_connectCompleter?.isCompleted == false) {
        _disconnectReason ??= const DisconnectReason(
          platform: ErrorPlatform.android,
          code: -1,
          description: 'Connection lost',
        );
        _connectCompleter!.complete();
      }
    }
  }

  /// 原生 BLE 连接状态事件入口。
  void _updateConnectionState(BluetoothConnectionState state) {
    _isBleConnected = state == BluetoothConnectionState.connected;
    if (state == BluetoothConnectionState.disconnected && !_isRfcommConnected) {
      _disconnectReason ??= const DisconnectReason(
        platform: ErrorPlatform.android,
        code: -1,
        description: 'Connection lost',
      );
    }
    _setConnectionState(state);
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
