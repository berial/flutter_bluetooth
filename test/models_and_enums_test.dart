import 'dart:typed_data';
import 'package:flutter_bluetooth/flutter_bluetooth.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('Model & Enum Serialization Tests', () {
    test('BluetoothConnectionState extensions', () {
      expect(BluetoothConnectionState.connecting.isConnecting, isTrue);
      expect(BluetoothConnectionState.connecting.isTransitioning, isTrue);
      expect(BluetoothConnectionState.connected.isConnected, isTrue);
      expect(BluetoothConnectionState.disconnected.isDisconnected, isTrue);
      expect(BluetoothConnectionState.disconnecting.isDisconnecting, isTrue);
      expect(BluetoothConnectionState.disconnecting.isTransitioning, isTrue);
    });

    test('PairingVariant parsing and needsResponse', () {
      expect(PairingVariant.fromString('pin'), equals(PairingVariant.pin));
      expect(PairingVariant.pin.needsResponse, isTrue);

      expect(PairingVariant.fromString('passkeyConfirmation'),
          equals(PairingVariant.passkeyConfirmation));
      expect(PairingVariant.passkeyConfirmation.needsResponse, isTrue);

      expect(PairingVariant.fromString('consent'), equals(PairingVariant.consent));
      expect(PairingVariant.consent.needsResponse, isTrue);

      expect(PairingVariant.fromString('displayPasskey'),
          equals(PairingVariant.displayPasskey));
      expect(PairingVariant.displayPasskey.needsResponse, isFalse);

      expect(PairingVariant.fromString('displayPin'),
          equals(PairingVariant.displayPin));
      expect(PairingVariant.displayPin.needsResponse, isFalse);

      expect(PairingVariant.fromString('unknown_other'),
          equals(PairingVariant.unknown));
      expect(PairingVariant.unknown.needsResponse, isFalse);
    });

    test('ScanResult and AdvertisementData fromMap', () {
      final map = {
        'device': {
          'remoteId': 'AA:BB:CC:11:22:33',
          'platformName': 'Test Device',
          'advName': 'Test Device',
          'type': 'ble',
        },
        'advertisementData': {
          'advName': 'Test Device',
          'txPowerLevel': -12,
          'connectable': true,
          'manufacturerData': {
            '76': [1, 2, 3, 4],
          },
          'serviceData': {
            '00001800-0000-1000-8000-00805f9b34fb': [10, 20],
          },
          'serviceUuids': [
            '00001800-0000-1000-8000-00805f9b34fb',
          ],
        },
        'rssi': -65,
        'timeStamp': 1700000000000,
      };

      final result = ScanResult.fromMap(map);
      expect(result.device.remoteId, equals('AA:BB:CC:11:22:33'));
      expect(result.device.platformName, equals('Test Device'));
      expect(result.rssi, equals(-65));
      expect(result.advertisementData.advName, equals('Test Device'));
      expect(result.advertisementData.txPowerLevel, equals(-12));
      expect(result.advertisementData.connectable, isTrue);
      expect(result.advertisementData.manufacturerData[76], equals(Uint8List.fromList([1, 2, 3, 4])));
      expect(result.advertisementData.serviceUuids.first, equals(Guid.short('1800')));
    });

    test('BluetoothService, Characteristic, and Descriptor mapping', () {
      final serviceMap = {
        'remoteId': 'AA:BB:CC:11:22:33',
        'serviceUuid': '0000ffe0-0000-1000-8000-00805f9b34fb',
        'primaryServiceUuid': null,
        'characteristics': [
          {
            'remoteId': 'AA:BB:CC:11:22:33',
            'serviceUuid': '0000ffe0-0000-1000-8000-00805f9b34fb',
            'characteristicUuid': '0000ffe1-0000-1000-8000-00805f9b34fb',
            'instanceId': 0,
            'properties': {
              'read': true,
              'write': true,
              'notify': true,
            },
            'descriptors': [
              {
                'remoteId': 'AA:BB:CC:11:22:33',
                'serviceUuid': '0000ffe0-0000-1000-8000-00805f9b34fb',
                'characteristicUuid': '0000ffe1-0000-1000-8000-00805f9b34fb',
                'descriptorUuid': '00002902-0000-1000-8000-00805f9b34fb',
                'instanceId': 0,
              }
            ],
          }
        ],
      };

      final service = BluetoothService.fromMap(serviceMap);
      expect(service.remoteId, equals('AA:BB:CC:11:22:33'));
      expect(service.serviceUuid, equals(Guid.short('ffe0')));
      expect(service.isPrimary, isTrue);
      expect(service.characteristics.length, equals(1));

      final char = service.characteristics.first;
      expect(char.characteristicUuid, equals(Guid.short('ffe1')));
      expect(char.properties.read, isTrue);
      expect(char.properties.write, isTrue);
      expect(char.properties.notify, isTrue);
      expect(char.properties.indicate, isFalse);
      expect(char.descriptors.length, equals(1));

      final desc = char.descriptors.first;
      expect(desc.descriptorUuid, equals(Guid.short('2902')));
    });
  });
}
