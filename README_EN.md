# flutter_bluetooth

> 🤖 **This library is AI-generated** — code and documentation are produced with AI assistance. Please test thoroughly before production use.

[中文](./README.md) | English

An Android Bluetooth Flutter plugin supporting both **Classic Bluetooth (BR/EDR)** and **BLE** scanning and data communication.

The API design mimics [flutter_blue_plus](https://github.com/chipweinberger/flutter_blue_plus), providing a familiar development experience.

Designed for **LDAR (Leak Detection and Repair)** instrument connectivity scenarios, with deep optimizations for multi-device concurrency, legacy instrument pairing, and stable connections.

## Features

- ✅ Scan **Classic Bluetooth** (BR/EDR) devices
- ✅ Scan **BLE** devices
- ✅ BLE GATT connection, service discovery, read/write, notification subscription
- ✅ Classic Bluetooth RFCOMM SPP serial communication (send + continuous receive)
- ✅ **RFCOMM server mode** (accept incoming connections)
- ✅ **Pairing request handling** (PIN / Passkey / Consent, intercepts system default UI)
- ✅ RSSI signal strength reading, MTU negotiation, connection priority
- ✅ Device pairing/bonding management
- ✅ Adapter state monitoring
- ✅ **Per-device operation queue** (serializes GATT/RFCOMM ops per device, prevents GATT_BUSY 133)
- ✅ **Connection intermediate states** (connecting / disconnecting, prevents duplicate connect attempts)
- ✅ **autoConnect auto-reconnect**
- ✅ **Android 13+ (API 33+) new API adaptation** (writeCharacteristic/writeDescriptor new signatures)
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

// Connect (supports autoConnect, MTU negotiation, timeout cancellation)
await device.connect(
  autoConnect: false,
  timeout: Duration(seconds: 35),
  mtu: 512,
);

// Discover services
List<BluetoothService> services = await device.discoverServices();

for (var service in services) {
  for (var characteristic in service.characteristics) {
    // Read
    var value = await characteristic.read();

    // Write (send data, supports withoutResponse mode)
    await characteristic.write([0x01, 0x02], withoutResponse: false);

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
// Returns: true=success, false=write failed (IOException),
//          throws PlatformException(NOT_CONNECTED)=device not connected
final ok = await device.sendRfcommData(
  Uint8List.fromList(utf8.encode('Hello!')),
);
if (!ok) {
  print('Send failed');
}

// Disconnect
await device.disconnectRfcomm();
```

### 6. RFCOMM Server Mode

```dart
// Start server to accept incoming RFCOMM connections
final ok = await FlutterBluetooth.instance.startRfcommServer(
  uuid: '00001101-0000-1000-8000-00805F9B34FB',
  name: 'FlutterBluetooth',
);
if (ok) {
  print('Server started, UUID: ${FlutterBluetooth.instance.serverUuid}');
}

// Listen to server state
FlutterBluetooth.instance.rfcommServerState.listen((state) {
  print('Server: ${state.isRunning ? "running" : "stopped"}');
});

// Incoming connections are pushed to the corresponding device via
// rfcommConnectionStateChanged events. Use device.onRfcommDataReceived to receive data.

// Stop server (existing connections are not affected)
await FlutterBluetooth.instance.stopRfcommServer();
```

### 7. Pairing Request Handling

For supporting legacy LDAR instruments requiring PIN/Passkey. When enabled, intercepts the system default pairing UI and lets the Dart side respond:

```dart
// Enable pairing request handling
await FlutterBluetooth.instance.enablePairingRequestHandling();

// Listen for pairing requests
FlutterBluetooth.instance.pairingRequest.listen((request) {
  print('Pairing request: ${request.remoteId}, variant: ${request.variant}');

  if (request.variant == PairingVariant.pin) {
    // PIN variant: set PIN
    FlutterBluetooth.instance.respondPairingPin(
      remoteId: request.remoteId,
      pin: '1234',
    );
  } else if (request.variant.needsResponse) {
    // PasskeyConfirmation / Consent: confirm or reject
    FlutterBluetooth.instance.respondPairingConfirmation(
      remoteId: request.remoteId,
      confirm: true,
    );
  }
  // displayPasskey / displayPin variants are notification-only, no response needed
});

// Disable pairing request handling, restore system default UI
await FlutterBluetooth.instance.disablePairingRequestHandling();
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
| `startRfcommServer()` | Start RFCOMM server (accept incoming connections) |
| `stopRfcommServer()` | Stop RFCOMM server |
| `isServerRunning` | Whether the server is running |
| `rfcommServerState` | Server state stream |
| `enablePairingRequestHandling()` | Enable pairing request handling (intercepts system UI) |
| `disablePairingRequestHandling()` | Disable pairing request handling |
| `respondPairingPin()` | Respond to PIN pairing request |
| `respondPairingConfirmation()` | Respond to Passkey/Consent pairing request |
| `pairingRequest` | Pairing request event stream |

### BluetoothDevice (Device Model)

| Method | Description |
|------|------|
| `connect()` | BLE GATT connection (supports autoConnect / timeout / mtu) |
| `disconnect()` | Disconnect (both BLE and RFCOMM) |
| `cancelConnect()` | Cancel an in-progress connection attempt |
| `connectionState` | Connection state stream (includes connecting/disconnecting intermediate states) |
| `isConnected` / `isConnecting` / `isDisconnecting` | Current state queries |
| `connectRfcomm()` | Establish RFCOMM SPP classic Bluetooth connection |
| `disconnectRfcomm()` | Disconnect RFCOMM connection |
| `sendRfcommData(List<int>)` | Send data via RFCOMM (see return value contract) |
| `readRfcommData()` | Synchronously read one RFCOMM data chunk |
| `onRfcommDataReceived` | RFCOMM data reception stream |
| `discoverServices()` | Discover BLE GATT services |
| `sendBleData()` | Convenience: send BLE data to specified characteristic |
| `getBleDataStream()` | Convenience: subscribe to BLE characteristic notifications |
| `readRssi()` | Read signal strength |
| `requestMtu()` | Negotiate MTU size |
| `requestConnectionPriority()` | Request connection priority |
| `clearGattCache()` | Clear GATT cache |
| `createBond()` | Pair with device (can preset PIN) |
| `removeBond()` | Remove bonding |

### BluetoothCharacteristic (BLE Characteristic)

| Method | Description |
|------|------|
| `read()` | Read characteristic value |
| `write()` | Write characteristic value (supports withoutResponse) |
| `setNotifyValue()` | Enable/disable notifications (auto-selects notify/indicate) |
| `onValueReceived` | Notification value stream |

## Stability Design

This plugin includes the following stability optimizations for LDAR instrument connectivity:

- **Per-device operation queue**: GATT/RFCOMM operations for the same device execute serially, cross-device parallel, preventing GATT_BUSY (133) errors
- **Connection intermediate states**: connecting/disconnecting synthesized on Dart side, native pushes only terminal states, preventing duplicate connect attempts
- **RFCOMM three-tier fallback**: insecure socket → secure socket → reflection createRfcommSocket(1), compatible with stubborn devices
- **RFCOMM write mutex**: per-device Mutex serializes writes, auto 512-byte chunking, prevents byte corruption
- **BLE chunked write**: auto-chunks by MTU-3, withoutResponse + large payload auto-degrades to withResponse to avoid deadlock
- **autoConnect reconnect**: autoConnect devices skip gatt.close() on disconnect to preserve handle for reconnect
- **Scan state separation**: BLE and Classic tracked separately, global stop pushed only when both finish
- **Pairing request interception**: abortBroadcast blocks system UI, Dart side responds programmatically
- **Timeout fallback**: connect / write / scan / pairing request all have timeouts, preventing Future hangs
- **Android 13+ adaptation**: writeCharacteristic/writeDescriptor new signatures + explicit error on old API false return

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
│       └── utils.dart                       # Guid, ScanResult, enums, operation queue
├── android/src/main/kotlin/io/github/berial/flutter_bluetooth/
│   ├── FlutterBluetoothPlugin.kt            # MethodChannel handler
│   ├── BleManager.kt                        # BLE scanning + GATT management
│   ├── ClassicBluetoothManager.kt           # Classic Bluetooth scanning
│   ├── RfcommManager.kt                     # RFCOMM SPP serial communication + server
│   └── PairingRequestManager.kt             # Pairing request handling
└── example/
    └── lib/main.dart                        # Example app
```

## Communication Mechanism

- **MethodChannel**: `io.github.berial.flutter_bluetooth/methods` — all synchronous/async calls
- **EventChannel**: `io.github.berial.flutter_bluetooth/events` — event streaming (scan results, connection state, adapter state, characteristic notifications, RFCOMM data, pairing requests, server state)

## License

MIT
