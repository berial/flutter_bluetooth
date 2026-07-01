part of '../flutter_bluetooth.dart';

/// BLE 服务中的 GATT 特征。
class BluetoothCharacteristic {
  final DeviceIdentifier remoteId;
  final Guid? primaryServiceUuid;
  final Guid serviceUuid;
  final Guid characteristicUuid;
  final int instanceId;

  /// 便捷属性。
  Guid get uuid => characteristicUuid;
  BluetoothDevice get device => BluetoothDevice(remoteId: remoteId);

  CharacteristicProperties? _properties;
  CharacteristicProperties get properties => _properties ?? const CharacteristicProperties();

  List<BluetoothDescriptor>? _descriptors;
  List<BluetoothDescriptor> get descriptors =>
      _descriptors ?? const [];

  /// 最新的读/写/通知值。
  Uint8List _lastValue = Uint8List(0);
  Uint8List get lastValue => _lastValue;

  final StreamController<Uint8List> _lastValueController =
      StreamController<Uint8List>.broadcast();
  Stream<Uint8List> get lastValueStream => _lastValueController.stream;

  final StreamController<Uint8List> _onValueReceivedController =
      StreamController<Uint8List>.broadcast();
  Stream<Uint8List> get onValueReceived =>
      _onValueReceivedController.stream;

  bool _isNotifying = false;
  bool get isNotifying => _isNotifying;

  BluetoothCharacteristic({
    required this.remoteId,
    this.primaryServiceUuid,
    required this.serviceUuid,
    required this.characteristicUuid,
    this.instanceId = 0,
  });

  factory BluetoothCharacteristic.fromMap(Map<String, dynamic> map) {
    final char = BluetoothCharacteristic(
      remoteId: map['remoteId'] as String,
      primaryServiceUuid: map['primaryServiceUuid'] != null
          ? Guid.fromString(map['primaryServiceUuid'] as String)
          : null,
      serviceUuid: Guid.fromString(map['serviceUuid'] as String),
      characteristicUuid:
          Guid.fromString(map['characteristicUuid'] as String),
      instanceId: map['instanceId'] as int? ?? 0,
    );

    if (map['properties'] != null) {
      char._properties = CharacteristicProperties.fromMap(
          Map<String, dynamic>.from(map['properties'] as Map));
    }

    if (map['descriptors'] != null) {
      char._descriptors = (map['descriptors'] as List)
          .map((d) =>
              BluetoothDescriptor.fromMap(Map<String, dynamic>.from(d as Map)))
          .toList();
    }

    return char;
  }

  Map<String, dynamic> toMap() {
    return {
      'remoteId': remoteId,
      'primaryServiceUuid': primaryServiceUuid?.str,
      'serviceUuid': serviceUuid.str,
      'characteristicUuid': characteristicUuid.str,
      'instanceId': instanceId,
    };
  }

  /// 读取特征值。
  ///
  /// 入队执行，保证与同设备其他 GATT 操作串行，避免 GATT_BUSY (133)。
  Future<Uint8List> read({int timeout = 15}) async {
    return FlutterBluetooth.instance._getQueue(remoteId).enqueue(() async {
      final value = await FlutterBluetooth.instance._readCharacteristic(
        remoteId: remoteId,
        primaryServiceUuid: primaryServiceUuid,
        serviceUuid: serviceUuid,
        characteristicUuid: characteristicUuid,
        instanceId: instanceId,
        timeout: timeout,
      );
      _lastValue = value;
      _lastValueController.add(value);
      _onValueReceivedController.add(value);
      return value;
    });
  }

  /// 向特征写入值。
  ///
  /// 入队执行，保证与同设备其他 GATT 操作串行，避免 GATT_BUSY (133)。
  Future<void> write(
    List<int> value, {
    bool withoutResponse = false,
    int timeout = 15,
  }) async {
    return FlutterBluetooth.instance._getQueue(remoteId).enqueue(() async {
      await FlutterBluetooth.instance._writeCharacteristic(
        remoteId: remoteId,
        primaryServiceUuid: primaryServiceUuid,
        serviceUuid: serviceUuid,
        characteristicUuid: characteristicUuid,
        instanceId: instanceId,
        value: value,
        withoutResponse: withoutResponse,
        timeout: timeout,
      );
    });
  }

  /// 启用或禁用通知/指示。
  ///
  /// 入队执行，保证与同设备其他 GATT 操作串行，避免 GATT_BUSY (133)。
  Future<bool> setNotifyValue(
    bool notify, {
    int timeout = 15,
    bool forceIndications = false,
  }) async {
    return FlutterBluetooth.instance._getQueue(remoteId).enqueue(() async {
      final result = await FlutterBluetooth.instance._setNotifyValue(
        remoteId: remoteId,
        primaryServiceUuid: primaryServiceUuid,
        serviceUuid: serviceUuid,
        characteristicUuid: characteristicUuid,
        instanceId: instanceId,
        enable: notify,
        forceIndications: forceIndications,
        timeout: timeout,
      );
      _isNotifying = result;
      return result;
    });
  }

  void _updateValue(Uint8List value) {
    _lastValue = value;
    _lastValueController.add(value);
    _onValueReceivedController.add(value);
  }

  /// 释放该特征持有的流控制器资源。
  void dispose() {
    _lastValueController.close();
    _onValueReceivedController.close();
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is BluetoothCharacteristic &&
          remoteId == other.remoteId &&
          serviceUuid == other.serviceUuid &&
          characteristicUuid == other.characteristicUuid &&
          instanceId == other.instanceId;

  @override
  int get hashCode => Object.hash(
        remoteId, serviceUuid, characteristicUuid, instanceId);

  @override
  String toString() =>
      'BluetoothCharacteristic('
      'remoteId: $remoteId, '
      'serviceUuid: $serviceUuid, '
      'characteristicUuid: $characteristicUuid, '
      'isNotifying: $isNotifying)';
}
