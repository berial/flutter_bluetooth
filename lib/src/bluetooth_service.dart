part of '../flutter_bluetooth.dart';

/// BLE 设备上的远程 GATT 服务。
class BluetoothService {
  final DeviceIdentifier remoteId;

  /// 对于次要服务，这是其所属的主要服务 UUID。
  final Guid? primaryServiceUuid;

  /// 该服务的 UUID。
  final Guid serviceUuid;

  /// 属于该服务的特征列表。
  final List<BluetoothCharacteristic> characteristics;

  BluetoothService({
    required this.remoteId,
    this.primaryServiceUuid,
    required this.serviceUuid,
    this.characteristics = const [],
  });

  /// 便捷属性：与 [serviceUuid] 相同。
  Guid get uuid => serviceUuid;

  /// 主要服务没有 [primaryServiceUuid]。
  bool get isPrimary => primaryServiceUuid == null;

  /// 次要服务有 [primaryServiceUuid]。
  bool get isSecondary => primaryServiceUuid != null;

  factory BluetoothService.fromMap(Map<String, dynamic> map) {
    final chars = <BluetoothCharacteristic>[];
    if (map['characteristics'] != null) {
      for (final c in (map['characteristics'] as List)) {
        chars.add(BluetoothCharacteristic.fromMap(Map<String, dynamic>.from(c as Map)));
      }
    }
    return BluetoothService(
      remoteId: map['remoteId'] as String,
      primaryServiceUuid: map['primaryServiceUuid'] != null
          ? Guid.fromString(map['primaryServiceUuid'] as String)
          : null,
      serviceUuid: Guid.fromString(map['serviceUuid'] as String),
      characteristics: chars,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'remoteId': remoteId,
      'primaryServiceUuid': primaryServiceUuid?.str,
      'serviceUuid': serviceUuid.str,
      'characteristics': characteristics.map((c) => c.toMap()).toList(),
    };
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is BluetoothService &&
          remoteId == other.remoteId &&
          primaryServiceUuid == other.primaryServiceUuid &&
          serviceUuid == other.serviceUuid;

  @override
  int get hashCode =>
      Object.hash(remoteId, primaryServiceUuid, serviceUuid);

  @override
  String toString() =>
      'BluetoothService(remoteId: $remoteId, serviceUuid: $serviceUuid, '
      'isPrimary: $isPrimary, characteristics: ${characteristics.length})';
}
