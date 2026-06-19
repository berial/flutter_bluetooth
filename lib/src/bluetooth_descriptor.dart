part of '../flutter_bluetooth.dart';

/// BLE 特征中的 GATT 描述符。
class BluetoothDescriptor {
  final DeviceIdentifier remoteId;
  final Guid? primaryServiceUuid;
  final Guid serviceUuid;
  final Guid characteristicUuid;
  final int instanceId;
  final Guid descriptorUuid;

  /// 便捷属性。
  Guid get uuid => descriptorUuid;
  BluetoothDevice get device => BluetoothDevice(remoteId: remoteId);

  Uint8List _lastValue = Uint8List(0);
  Uint8List get lastValue => _lastValue;

  final StreamController<Uint8List> _lastValueController =
      StreamController<Uint8List>.broadcast();
  Stream<Uint8List> get lastValueStream => _lastValueController.stream;

  final StreamController<Uint8List> _onValueReceivedController =
      StreamController<Uint8List>.broadcast();
  Stream<Uint8List> get onValueReceived =>
      _onValueReceivedController.stream;

  BluetoothDescriptor({
    required this.remoteId,
    this.primaryServiceUuid,
    required this.serviceUuid,
    required this.characteristicUuid,
    this.instanceId = 0,
    required this.descriptorUuid,
  });

  factory BluetoothDescriptor.fromMap(Map<String, dynamic> map) {
    return BluetoothDescriptor(
      remoteId: map['remoteId'] as String,
      primaryServiceUuid: map['primaryServiceUuid'] != null
          ? Guid.fromString(map['primaryServiceUuid'] as String)
          : null,
      serviceUuid: Guid.fromString(map['serviceUuid'] as String),
      characteristicUuid:
          Guid.fromString(map['characteristicUuid'] as String),
      instanceId: map['instanceId'] as int? ?? 0,
      descriptorUuid: Guid.fromString(map['descriptorUuid'] as String),
    );
  }

  /// 读取描述符值。
  Future<Uint8List> read({int timeout = 15}) async {
    final value = await FlutterBluetooth.instance._readDescriptor(
      remoteId: remoteId,
      primaryServiceUuid: primaryServiceUuid,
      serviceUuid: serviceUuid,
      characteristicUuid: characteristicUuid,
      instanceId: instanceId,
      descriptorUuid: descriptorUuid,
      timeout: timeout,
    );
    _lastValue = value;
    _lastValueController.add(value);
    _onValueReceivedController.add(value);
    return value;
  }

  /// 向描述符写入值。
  Future<void> write(List<int> value, {int timeout = 15}) async {
    await FlutterBluetooth.instance._writeDescriptor(
      remoteId: remoteId,
      primaryServiceUuid: primaryServiceUuid,
      serviceUuid: serviceUuid,
      characteristicUuid: characteristicUuid,
      instanceId: instanceId,
      descriptorUuid: descriptorUuid,
      value: value,
      timeout: timeout,
    );
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is BluetoothDescriptor &&
          remoteId == other.remoteId &&
          serviceUuid == other.serviceUuid &&
          characteristicUuid == other.characteristicUuid &&
          instanceId == other.instanceId &&
          descriptorUuid == other.descriptorUuid;

  @override
  int get hashCode => Object.hash(
        remoteId, serviceUuid, characteristicUuid, instanceId, descriptorUuid);

  @override
  String toString() =>
      'BluetoothDescriptor(remoteId: $remoteId, descriptorUuid: $descriptorUuid)';
}
