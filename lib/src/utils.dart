part of '../flutter_bluetooth.dart';

/// 行为流包装器：监听时若有缓存值则立即重发，再转发原流事件。
class _BehaviorStream<T> extends Stream<T> {
  _BehaviorStream(this._source, this._initialValue);

  final Stream<T> _source;
  final T? Function() _initialValue;

  @override
  StreamSubscription<T> listen(void Function(T event)? onData,
      {Function? onError, void Function()? onDone, bool? cancelOnError}) {
    // 先发缓存值
    final initial = _initialValue();
    if (initial != null && onData != null) {
      // 异步发送以保证订阅顺序
      scheduleMicrotask(() => onData(initial));
    }
    return _source.listen(onData,
        onError: onError, onDone: onDone, cancelOnError: cancelOnError);
  }
}

/// 单设备串行操作队列。
///
/// BLE GATT 协议要求操作串行执行，并发调用会触发 GATT_BUSY (133)
/// 或操作丢失。此队列保证同一设备的 GATT 操作按调用顺序依次执行，
/// 前一个完成（成功或失败）后才开始下一个。不同设备的队列相互独立，
/// 可并行执行。
///
/// 入队操作不会阻塞调用线程 — [enqueue] 立即返回一个 [Future]，
/// 实际执行排在队列尾，结果通过返回的 Future 传递。
class _OperationQueue {
  Future<void> _tail = Future.value();

  /// 将异步任务加入队列尾部，返回任务结果的 Future。
  ///
  /// 任务 [task] 会在前一个任务完成后才开始执行。
  /// 即使前一个任务失败，后续任务仍会执行（失败不会阻塞队列）。
  Future<T> enqueue<T>(Future<T> Function() task) {
    final completer = Completer<T>();
    _tail = _tail.then((_) async {
      try {
        final result = await task();
        completer.complete(result);
      } catch (e, st) {
        completer.completeError(e, st);
      }
    });
    return completer.future;
  }

  /// 清空队列（设备断开时调用）。
  void clear() {
    _tail = Future.value();
  }
}

// ─── Guid ───────────────────────────────────────────────────────────────────

/// 表示蓝牙 UUID（16位、32位或128位）。
class Guid {
  final String _uuid;

  const Guid(this._uuid);

  /// 从16位短 UUID 创建 Guid（例如 "1800"）。
  factory Guid.short(String shortUuid) {
    // 确保小写且无连字符
    final hex = shortUuid.replaceAll('-', '').toLowerCase();
    return Guid('0000$hex-0000-1000-8000-00805f9b34fb');
  }

  /// 从原始 UUID 字符串创建 Guid。
  factory Guid.fromString(String uuid) {
    return Guid(uuid.toLowerCase());
  }

  /// 完整的128位 UUID 字符串。
  String get str => _uuid;

  @override
  bool operator ==(Object other) =>
      identical(this, other) || other is Guid && _uuid == other._uuid;

  @override
  int get hashCode => _uuid.hashCode;

  @override
  String toString() => 'Guid($_uuid)';
}

// ─── ScanResult ─────────────────────────────────────────────────────────────

/// 蓝牙扫描结果。
class ScanResult {
  final BluetoothDevice device;
  final AdvertisementData advertisementData;
  final int rssi;
  final DateTime timeStamp;

  const ScanResult({
    required this.device,
    required this.advertisementData,
    required this.rssi,
    required this.timeStamp,
  });

  /// 从原生 Android 平台返回的 map 进行反序列化。
  factory ScanResult.fromMap(Map<String, dynamic> map) {
    return ScanResult(
      device: BluetoothDevice.fromMap(Map<String, dynamic>.from(map['device'] as Map)),
      advertisementData: AdvertisementData.fromMap(
          Map<String, dynamic>.from(map['advertisementData'] as Map)),
      rssi: map['rssi'] as int,
      timeStamp: DateTime.fromMillisecondsSinceEpoch(map['timeStamp'] as int),
    );
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is ScanResult &&
          device.remoteId == other.device.remoteId &&
          rssi == other.rssi;

  @override
  int get hashCode => Object.hash(device.remoteId, rssi);

  @override
  String toString() =>
      'ScanResult(device: ${device.platformName}, rssi: $rssi)';
}

// ─── AdvertisementData ──────────────────────────────────────────────────────

/// 从扫描结果中解析的广播数据。
class AdvertisementData {
  final String advName;
  final int? txPowerLevel;
  final bool connectable;
  final Map<int, Uint8List> manufacturerData;
  final Map<Guid, Uint8List> serviceData;
  final List<Guid> serviceUuids;

  const AdvertisementData({
    required this.advName,
    this.txPowerLevel,
    required this.connectable,
    required this.manufacturerData,
    required this.serviceData,
    required this.serviceUuids,
  });

  factory AdvertisementData.fromMap(Map<String, dynamic> map) {
    final mfrData = <int, Uint8List>{};
    if (map['manufacturerData'] != null) {
      for (final entry in (map['manufacturerData'] as Map).entries) {
        mfrData[int.parse(entry.key.toString())] =
            Uint8List.fromList(List<int>.from(entry.value as List));
      }
    }

    final svcData = <Guid, Uint8List>{};
    if (map['serviceData'] != null) {
      for (final entry in (map['serviceData'] as Map).entries) {
        svcData[Guid.fromString(entry.key.toString())] =
            Uint8List.fromList(List<int>.from(entry.value as List));
      }
    }

    final svcUuids = <Guid>[];
    if (map['serviceUuids'] != null) {
      for (final uuid in (map['serviceUuids'] as List)) {
        svcUuids.add(Guid.fromString(uuid.toString()));
      }
    }

    return AdvertisementData(
      advName: map['advName'] as String? ?? '',
      txPowerLevel: map['txPowerLevel'] as int?,
      connectable: map['connectable'] as bool? ?? false,
      manufacturerData: mfrData,
      serviceData: svcData,
      serviceUuids: svcUuids,
    );
  }

  @override
  String toString() =>
      'AdvertisementData(name: $advName, connectable: $connectable, '
      'services: ${serviceUuids.length})';
}

// ─── ScanFilter ─────────────────────────────────────────────────────────────

/// 按厂商特定数据过滤扫描结果。
class MsdFilter {
  final int manufacturerId;
  final List<int> data;
  final List<int> mask;

  const MsdFilter(this.manufacturerId,
      {this.data = const [], this.mask = const []});
}

/// 按服务数据过滤扫描结果。
class ServiceDataFilter {
  final Guid service;
  final List<int> data;
  final List<int> mask;

  const ServiceDataFilter(this.service,
      {this.data = const [], this.mask = const []});
}

// ─── 类型别名与枚举 ───────────────────────────────────────────────────────

/// 蓝牙 GUID/UUID 类型。
typedef DeviceIdentifier = String;

/// 蓝牙适配器状态。
enum BluetoothAdapterState {
  unknown,
  unavailable,
  unauthorized,
  on,
  off,
  turningOn,
  turningOff,
}

/// 蓝牙连接状态。
enum BluetoothConnectionState {
  /// 已断开（终态）。
  disconnected,

  /// 连接中（中间态，仅 Dart 端合成推送，原生不推送）。
  connecting,

  /// 断开中（中间态，仅 Dart 端合成推送，原生不推送）。
  disconnecting,

  /// 已连接（终态，由原生推送）。
  connected,
}

/// [BluetoothConnectionState] 的便捷扩展。
extension BluetoothConnectionStateX on BluetoothConnectionState {
  /// 是否处于连接建立过程中（含 connecting）。
  bool get isConnecting => this == BluetoothConnectionState.connecting;

  /// 是否处于断开过程中（含 disconnecting）。
  bool get isDisconnecting => this == BluetoothConnectionState.disconnecting;

  /// 是否已稳定连接。
  bool get isConnected => this == BluetoothConnectionState.connected;

  /// 是否已稳定断开。
  bool get isDisconnected => this == BluetoothConnectionState.disconnected;

  /// 是否处于任何中间态。
  bool get isTransitioning => isConnecting || isDisconnecting;
}

/// 蓝牙配对状态（仅 Android）。
enum BluetoothBondState { none, bonding, bonded }

/// 配对请求变体。
enum PairingVariant {
  /// 4 位数字 PIN 输入。
  pin,

  /// Passkey 确认（用户比对 6 位数字是否一致）。
  passkeyConfirmation,

  /// 简单同意/拒绝确认。
  consent,

  /// 显示 Passkey（仅通知，无需响应）。
  displayPasskey,

  /// 显示 PIN（仅通知，无需响应）。
  displayPin,

  /// 未知变体。
  unknown;

  static PairingVariant fromString(String s) => switch (s) {
        'pin' => pin,
        'passkeyConfirmation' => passkeyConfirmation,
        'consent' => consent,
        'displayPasskey' => displayPasskey,
        'displayPin' => displayPin,
        _ => unknown,
      };

  /// 是否需要 Dart 端响应。
  bool get needsResponse =>
      this == pin || this == passkeyConfirmation || this == consent;
}

/// 配对请求事件。
///
/// 当启用配对请求处理后，系统发起配对时会推送此事件。
/// Dart 端需根据 [variant] 调用 [FlutterBluetooth.respondPairingRequest] 响应。
class PairingRequest {
  /// 设备 MAC 地址。
  final String remoteId;

  /// 配对变体。
  final PairingVariant variant;

  /// 原始变体码（用于调试）。
  final int variantCode;

  /// 配对密钥（Passkey/Consent 变体下为 6 位数字，PIN 变体下为 -1）。
  final int pairingKey;

  const PairingRequest({
    required this.remoteId,
    required this.variant,
    required this.variantCode,
    required this.pairingKey,
  });

  @override
  String toString() =>
      'PairingRequest(remoteId: $remoteId, variant: $variant, key: $pairingKey)';
}

/// 连接优先级（仅 Android）。
enum ConnectionPriority { balanced, high, lowPower }

/// Android BLE/经典蓝牙扫描模式。
enum AndroidScanMode {
  /// 被动扫描，仅接收主动广播的设备。
  opportunistic(-1),

  /// 低功耗扫描（默认）。
  lowPower(0),

  /// 平衡扫描。
  balanced(1),

  /// 低延迟扫描，最高频率。
  lowLatency(2);

  final int value;
  const AndroidScanMode(this.value);
}

// ─── 日志级别 ─────────────────────────────────────────────────────────────

/// 日志级别，控制 [FlutterBluetooth.logs] 流输出的详细程度。
enum LogLevel {
  none,
  error,
  warning,
  info,
  debug,
  verbose;

  /// 级别数字越大越详细。用于过滤输出。
  int get severity => index;
}

// ─── 错误码与异常 ─────────────────────────────────────────────────────────

/// 错误来源平台。
enum ErrorPlatform { fbp, android }

/// 统一错误码。
enum FbpErrorCode {
  success,
  timeout,
  androidOnly,
  deviceIsDisconnected,
  adapterIsOff,
  connectionCanceled,
  userRejected,
  invalidArgument,
  notSupported,
  unknown;

  String get name => toString().split('.').last;
}

/// 蓝牙异常。所有原生调用失败均以此异常抛出，便于上层统一捕获。
class FlutterBluetoothException implements Exception {
  final ErrorPlatform platform;
  final String function;
  final String code;
  final String description;

  const FlutterBluetoothException({
    required this.platform,
    required this.function,
    required this.code,
    required this.description,
  });

  @override
  String toString() =>
      'FlutterBluetoothException($platform, $function, $code, $description)';
}

/// 设备断开原因。
class DisconnectReason {
  final ErrorPlatform platform;
  final int code;
  final String description;

  const DisconnectReason({
    required this.platform,
    required this.code,
    required this.description,
  });

  @override
  String toString() =>
      'DisconnectReason($platform, $code, $description)';
}

/// RFCOMM 服务器状态事件。
class RfcommServerState {
  /// 是否正在运行。
  final bool isRunning;

  /// 启动时使用的服务 UUID（停止时为 null）。
  final String? uuid;

  const RfcommServerState({required this.isRunning, this.uuid});

  @override
  String toString() =>
      'RfcommServerState(isRunning: $isRunning, uuid: $uuid)';
}

// ─── 特征属性 ────────────────────────────────────────────────────────────

/// BLE 特征属性位掩码。
class CharacteristicProperties {
  final bool broadcast;
  final bool read;
  final bool writeWithoutResponse;
  final bool write;
  final bool notify;
  final bool indicate;
  final bool authenticatedSignedWrites;
  final bool extendedProperties;
  final bool notifyEncryptionRequired;
  final bool indicateEncryptionRequired;

  const CharacteristicProperties({
    this.broadcast = false,
    this.read = false,
    this.writeWithoutResponse = false,
    this.write = false,
    this.notify = false,
    this.indicate = false,
    this.authenticatedSignedWrites = false,
    this.extendedProperties = false,
    this.notifyEncryptionRequired = false,
    this.indicateEncryptionRequired = false,
  });

  factory CharacteristicProperties.fromMap(Map<String, dynamic> map) {
    return CharacteristicProperties(
      broadcast: map['broadcast'] as bool? ?? false,
      read: map['read'] as bool? ?? false,
      writeWithoutResponse: map['writeWithoutResponse'] as bool? ?? false,
      write: map['write'] as bool? ?? false,
      notify: map['notify'] as bool? ?? false,
      indicate: map['indicate'] as bool? ?? false,
      authenticatedSignedWrites:
          map['authenticatedSignedWrites'] as bool? ?? false,
      extendedProperties: map['extendedProperties'] as bool? ?? false,
      notifyEncryptionRequired:
          map['notifyEncryptionRequired'] as bool? ?? false,
      indicateEncryptionRequired:
          map['indicateEncryptionRequired'] as bool? ?? false,
    );
  }

  @override
  String toString() => 'CharacteristicProperties('
      'read: $read, write: $write, notify: $notify, indicate: $indicate)';
}
