/// 平台通道回归测试覆盖设备身份、连接取消、扫描和通知。
import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_bluetooth/flutter_bluetooth.dart';
import 'package:flutter_test/flutter_test.dart';

const _methods = MethodChannel('io.github.berial.flutter_bluetooth/methods');
const _events = 'io.github.berial.flutter_bluetooth/events';
const _codec = StandardMethodCodec();

TestDefaultBinaryMessenger get _messenger =>
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

Future<void> _sendEvent(Map<String, Object?> event) =>
    _messenger.handlePlatformMessage(
      _events,
      _codec.encodeSuccessEnvelope(event),
      null,
    );

Future<void> _flush() => Future<void>.delayed(Duration.zero);

Map<String, Object?> _scanEvent(String address, int rssi) => {
      'type': 'scanResult',
      'device': {'remoteId': address, 'platformName': 'Meter', 'type': 'ble'},
      'advertisementData': <String, Object?>{},
      'rssi': rssi,
      'timeStamp': 0,
    };

Map<String, Object?> _connectionEvent(String address, String state) => {
      'type': 'connectionStateChanged',
      'remoteId': address,
      'state': state,
    };

/// 验证蓝牙插件在原生回复与事件交错到达时的公开 API 行为。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() {
    _messenger.setMockMethodCallHandler(const MethodChannel(_events),
        (_) async {
      return null;
    });
    FlutterBluetooth.instance;
  });

  tearDown(() async {
    await FlutterBluetooth.instance.stopScan();
    _messenger.setMockMethodCallHandler(_methods, null);
  });

  group('设备与连接', () {
    test('同一地址通过 fromId、重复扫描和已配对列表返回同一设备', () async {
      const address = '00:11:22:33:44:AA';
      final bluetooth = FlutterBluetooth.instance;
      final device = BluetoothDevice.fromId(address.toLowerCase());
      addTearDown(device.dispose);
      final scans = <ScanResult>[];
      final subscription = bluetooth.onScanResults.listen(scans.add);
      addTearDown(subscription.cancel);

      await _sendEvent(_scanEvent(address, -60));
      await _sendEvent(_scanEvent(address, -45));
      await _flush();
      expect(scans, hasLength(2));
      expect(scans.map((scan) => scan.device), everyElement(same(device)));
      expect(device.platformName, 'Meter');

      _messenger.setMockMethodCallHandler(_methods, (call) async {
        if (call.method == 'getBondedDevices') {
          return [
            {'remoteId': address}
          ];
        }
        if (call.method == 'connect') {
          await _sendEvent(_connectionEvent(address, 'connected'));
        }
        return null;
      });
      expect((await bluetooth.getBondedDevices()).single, same(device));
      await device.connect(timeout: const Duration(milliseconds: 100));
      expect(device.isConnected, isTrue);
      expect(bluetooth.connectedDevices, contains(same(device)));
      await device.disconnect();
      expect(device.isDisconnected, isTrue);
    });

    testWidgets('原生回复挂起时连接按时超时，随后可以重试', (tester) async {
      final device = BluetoothDevice.fromId('00:11:22:33:44:BB');
      addTearDown(device.dispose);
      final lateReply = Completer<Object?>();
      final calls = <String>[];
      _messenger.setMockMethodCallHandler(_methods, (call) async {
        calls.add(call.method);
        if (call.method == 'connect' &&
            calls.where((method) => method == 'connect').length == 1) {
          return lateReply.future;
        }
        return null;
      });

      final first =
          device.connect(timeout: const Duration(seconds: 2), mtu: 512);
      final failure = expectLater(
        first,
        throwsA(isA<FlutterBluetoothException>().having(
          (error) => error.code,
          'code',
          'timeout',
        )),
      );
      await tester.pump(const Duration(seconds: 2));
      await failure;
      expect(calls, ['connect', 'disconnect']);
      expect(device.isDisconnected, isTrue);

      await device.connect();
      lateReply.completeError(PlatformException(code: 'DISCONNECTED'));
      await tester.pump();
      expect(device.isConnected, isTrue);
    });

    test('取消 autoConnect 时立即完成，且只断开 BLE', () async {
      final device = BluetoothDevice.fromId('00:11:22:33:44:CC');
      addTearDown(device.dispose);
      final lateReply = Completer<Object?>();
      final calls = <String>[];
      _messenger.setMockMethodCallHandler(_methods, (call) async {
        calls.add(call.method);
        return call.method == 'connect' ? lateReply.future : null;
      });
      final attempt = device.connect(autoConnect: true);
      final failure = expectLater(
        attempt,
        throwsA(isA<FlutterBluetoothException>().having(
          (error) => error.code,
          'code',
          'connectionCanceled',
        )),
      );
      device.cancelConnect();
      await failure;
      expect(device.isDisconnected, isTrue);
      expect(calls, ['connect', 'disconnect']);
      lateReply.complete(null);
      await _flush();
      expect(device.isDisconnected, isTrue);
    });

    test('RFCOMM 断开后仍可使用同一设备的 BLE 链路', () async {
      const address = '00:11:22:33:44:DD';
      final device = BluetoothDevice.fromId(address);
      addTearDown(device.dispose);
      _messenger.setMockMethodCallHandler(_methods, (_) async => null);
      await device.connect();
      for (final state in ['connected', 'disconnected']) {
        await _sendEvent({
          'type': 'rfcommConnectionStateChanged',
          'remoteId': address,
          'state': state,
        });
      }
      expect(device.isConnected, isTrue);
      expect(device.isRfcommConnected, isFalse);
    });
  });

  group('扫描', () {
    testWidgets('停止后重新扫描，旧定时器不会停止新扫描', (tester) async {
      final bluetooth = FlutterBluetooth.instance;
      var stops = 0;
      _messenger.setMockMethodCallHandler(_methods, (call) async {
        if (call.method == 'stopScan') stops++;
        return null;
      });

      await bluetooth.startScan(timeout: const Duration(seconds: 2));
      await bluetooth.stopScan();
      await bluetooth.startScan(timeout: const Duration(seconds: 5));
      await tester.pump(const Duration(seconds: 2));
      expect(bluetooth.isScanningNow, isTrue);
      expect(stops, 1);
      await tester.pump(const Duration(seconds: 3));
      expect(bluetooth.isScanningNow, isFalse);
      expect(stops, 2);
    });

    testWidgets('启动失败后清除扫描状态和定时器', (tester) async {
      final bluetooth = FlutterBluetooth.instance;
      _messenger.setMockMethodCallHandler(_methods, (call) async {
        throw PlatformException(code: 'PERMISSION_DENIED');
      });
      await expectLater(
        bluetooth.startScan(timeout: const Duration(seconds: 2)),
        throwsA(isA<PlatformException>()),
      );
      expect(bluetooth.isScanningNow, isFalse);
    });
  });

  test('重复获取同一特征数据流共用转发订阅', () async {
    const address = '00:11:22:33:44:EE';
    final device = BluetoothDevice.fromId(address);
    addTearDown(device.dispose);
    final service = Guid.short('ffe0');
    final characteristic = Guid.short('ffe1');
    var enables = 0;
    _messenger.setMockMethodCallHandler(_methods, (call) async {
      if (call.method == 'discoverServices') {
        return [
          {
            'remoteId': address,
            'serviceUuid': service.str,
            'characteristics': [
              {
                'remoteId': address,
                'serviceUuid': service.str,
                'characteristicUuid': characteristic.str,
              }
            ],
          }
        ];
      }
      if (call.method == 'setNotifyValue') {
        enables++;
        return true;
      }
      return null;
    });

    final received = <int>[];
    final secondReceived = <int>[];
    final first = device
        .getBleDataStream(
            serviceUuid: service, characteristicUuid: characteristic)
        .listen((bytes) => received.add(bytes.single));
    final second = device
        .getBleDataStream(
            serviceUuid: service, characteristicUuid: characteristic)
        .listen((bytes) => secondReceived.add(bytes.single));
    addTearDown(first.cancel);
    addTearDown(second.cancel);
    await _flush();
    await _sendEvent({
      'type': 'characteristicNotified',
      'remoteId': address,
      'serviceUuid': service.str,
      'characteristicUuid': characteristic.str,
      'value': Uint8List.fromList([42]),
    });
    await _flush();

    expect(enables, 1);
    expect(received, [42]);
    expect(secondReceived, [42]);
  });
}
