# flutter_bluetooth

> 🤖 **This library is AI-generated** — code and documentation are produced with AI assistance. Please test thoroughly before production use.

[中文](./README.md) | English

An Android Bluetooth Flutter plugin supporting both **Classic Bluetooth (BR/EDR)** and **BLE** scanning and data communication.

The API design mimics [flutter_blue_plus](https://github.com/chipweinberger/flutter_blue_plus), providing a familiar development experience.

## Features

- ✅ Scan **Classic Bluetooth** (BR/EDR) devices
- ✅ Scan **BLE** devices
- ✅ BLE GATT connection, service discovery, read/write, notification subscription
- ✅ Classic Bluetooth RFCOMM SPP serial communication (send + continuous receive)
- ✅ RSSI signal strength reading, MTU negotiation
- ✅ Device pairing/bonding management
- ✅ Adapter state monitoring
- ✅ Android only (Android 5.0+ / API 21+)

## Quick Start

### 1. Add Dependency

```yaml
dependencies:
  flutter_bluetooth:
    path: /path/to/flutter_bluetooth
```

### 2. Android Permissions

Add the following to your app's `AndroidManifest.xml`:

```xml
<!-- Bluetooth basics -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- Location permission (required for BLE scanning on Android < 12) -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
    android:maxSdkVersion="30" />
```

Android 12+ (API 31+) requires runtime permissions:

```dart
// Request Bluetooth runtime permissions
await [
  Permission.bluetoothScan,
  Permission.bluetoothConnect,
].request();
```

### 3. Scan Devices

```dart
import 'package:flutter_bluetooth/flutter_bluetooth.dart';

// Check if Bluetooth is supported
bool supported = await FlutterBluetooth.instance.isSupported;
String name = await FlutterBluetooth.instance.getAdapterName();

// Listen to scan results
FlutterBluetooth.instance.scanResults.listen((results) {
  for (var result in results) {
    print('Device found: ${result.device.platformName}（${result.device.remoteId}）');
    print('  RSSI: ${result.rssi} dBm');
    print('  Type: ${result.device.type}'); // "classic" or "ble"
  }
});

// Start scanning (both classic and BLE simultaneously)
await FlutterBluetooth.instance.startScan(
  scanClassic: true,
  timeout: Duration(seconds: 30),
);

// Stop scanning
await FlutterBluetooth.instance.stopScan();
```

### 4. BLE Connection & Data Communication

```dart
var device = result.device;

// Connect
await device.connect();

// Discover services
List<BluetoothService> services = await device.discoverServices();

for (var service in services) {
  for (var characteristic in service.characteristics) {
    // Read
    var value = await characteristic.read();
    
    // Write (send data)
    await characteristic.write([0x01, 0x02]);
    
    // Subscribe to notifications (continuous data reception)
    await characteristic.setNotifyValue(true);
    characteristic.onValueReceived.listen((value) {
      print('Notification received: $value');
    });
  }
}

// Disconnect
await device.disconnect();
```

Convenience methods are also available:

```dart
// Send data to a specific characteristic
await device.sendBleData(
  Uint8List.fromList([0x01, 0x02, 0x03]),
  serviceUuid: Guid.short('ffe0'),
  characteristicUuid: Guid.short('ffe1'),
);

// Continuously receive data
final stream = device.getBleDataStream(
  serviceUuid: Guid.short('ffe0'),
  characteristicUuid: Guid.short('ffe1'),
);
stream.listen((value) => print('Received: $value'));
```

### 5. Classic Bluetooth (SPP Serial) Data Communication

```dart
// Establish RFCOMM SPP connection
await device.connectRfcomm();

// Continuously receive data
device.onRfcommDataReceived.listen((data) {
  print('Received: ${utf8.decode(data)}');
});

// Send data
await device.sendRfcommData(
  Uint8List.fromList(utf8.encode('Hello!')),
);

// Disconnect
await device.disconnectRfcomm();
```

## API Reference

### FlutterBluetooth (Main Entry Class)

| Method | Description |
|------|------|
| `isSupported` | Check if device supports Bluetooth |
| `getAdapterName()` | Get local Bluetooth adapter name |
| `turnOn()` | Turn on Bluetooth |
| `startScan()` | Start scanning classic + BLE devices |
| `stopScan()` | Stop scanning |
| `scanResults` | Scan results stream |
| `adapterState` | Adapter on/off state stream |
| `getBondedDevices()` | Get list of paired devices |
| `getSystemDevices()` | Get system-connected BLE devices |

### BluetoothDevice (Device Model)

| Method | Description |
|------|------|
| `connect()` | BLE GATT connection |
| `disconnect()` | Disconnect (both BLE and RFCOMM) |
| `connectRfcomm()` | Establish RFCOMM SPP classic Bluetooth connection |
| `disconnectRfcomm()` | Disconnect RFCOMM connection |
| `sendRfcommData(List<int>)` | Send data via RFCOMM |
| `onRfcommDataReceived` | RFCOMM data reception stream |
| `discoverServices()` | Discover BLE GATT services |
| `sendBleData()` | Convenience: send BLE data to specified characteristic |
| `getBleDataStream()` | Convenience: subscribe to BLE characteristic notifications |
| `readRssi()` | Read signal strength |
| `requestMtu()` | Negotiate MTU size |
| `createBond()` | Pair with device |
| `removeBond()` | Remove bonding |

### BluetoothCharacteristic (BLE Characteristic)

| Method | Description |
|------|------|
| `read()` | Read characteristic value |
| `write()` | Write characteristic value |
| `setNotifyValue()` | Enable/disable notifications |
| `onValueReceived` | Notification value stream |

## Project Structure

```
flutter_bluetooth/
├── lib/
│   ├── flutter_bluetooth.dart              # Library entry point
│   └── src/
│       ├── flutter_bluetooth_impl.dart      # FlutterBluetooth main class
│       ├── bluetooth_device.dart            # BluetoothDevice device model
│       ├── bluetooth_service.dart           # GATT service model
│       ├── bluetooth_characteristic.dart    # GATT characteristic (read/write/notify)
│       ├── bluetooth_descriptor.dart        # GATT descriptor
│       └── utils.dart                       # Guid, ScanResult, enums
├── android/src/main/kotlin/io/github/berial/flutter_bluetooth/
│   ├── FlutterBluetoothPlugin.kt            # MethodChannel handler
│   ├── BleManager.kt                        # BLE scanning + GATT management
│   ├── ClassicBluetoothManager.kt           # Classic Bluetooth scanning
│   └── RfcommManager.kt                    # RFCOMM SPP serial communication
└── example/
    └── lib/main.dart                        # Example app
```

## Communication Mechanism

- **MethodChannel**: `io.github.berial.flutter_bluetooth/methods` — all synchronous/async calls
- **EventChannel**: `io.github.berial.flutter_bluetooth/events` — event streaming (scan results, connection state, adapter state, characteristic notifications, RFCOMM data)

## License

MIT
