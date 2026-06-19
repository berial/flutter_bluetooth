part of '../flutter_bluetooth.dart';

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
enum BluetoothAdapterState { unknown, unavailable, on, off, turningOn, turningOff }

/// 蓝牙连接状态。
enum BluetoothConnectionState { disconnected, connected }

/// 蓝牙配对状态（仅 Android）。
enum BluetoothBondState { none, bonding, bonded }

/// 连接优先级（仅 Android）。
enum ConnectionPriority { balanced, high, lowPower }

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
