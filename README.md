# flutter_bluetooth

Android 蓝牙 Flutter 插件，支持**经典蓝牙（BR/EDR）**和 **BLE** 两种扫描与数据通信。

API 设计仿照 [flutter_blue_plus](https://github.com/chipweinberger/flutter_blue_plus)，提供熟悉的开发体验。

## 功能特性

- ✅ 扫描**经典蓝牙**（BR/EDR）设备
- ✅ 扫描 **BLE** 设备
- ✅ BLE GATT 连接、服务发现、读写、通知订阅
- ✅ 经典蓝牙 RFCOMM SPP 串口通信（发送 + 持续接收）
- ✅ RSSI 信号强度读取、MTU 协商
- ✅ 设备配对/绑定管理
- ✅ 适配器状态监听
- ✅ 仅 Android（Android 5.0+ / API 21+）

## 快速开始

### 1. 添加依赖

```yaml
dependencies:
  flutter_bluetooth:
    path: /path/to/flutter_bluetooth
```

### 2. Android 权限配置

在应用的 `AndroidManifest.xml` 中添加：

```xml
<!-- 蓝牙基础 -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- 位置权限（Android 12 以下 BLE 扫描需要） -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
    android:maxSdkVersion="30" />
```

Android 12+（API 31+）需要运行时权限：

```dart
// 运行时请求蓝牙权限
await [
  Permission.bluetoothScan,
  Permission.bluetoothConnect,
].request();
```

### 3. 扫描设备

```dart
import 'package:flutter_bluetooth/flutter_bluetooth.dart';

// 检查是否支持蓝牙
bool supported = await FlutterBluetooth.instance.isSupported;
String name = await FlutterBluetooth.instance.getAdapterName();

// 监听扫描结果
FlutterBluetooth.instance.scanResults.listen((results) {
  for (var result in results) {
    print('发现设备: ${result.device.platformName}（${result.device.remoteId}）');
    print('  RSSI: ${result.rssi} dBm');
    print('  类型: ${result.device.type}'); // "classic" 或 "ble"
  }
});

// 开始扫描（同时扫描经典蓝牙和 BLE）
await FlutterBluetooth.instance.startScan(
  scanClassic: true,
  timeout: Duration(seconds: 30),
);

// 停止扫描
await FlutterBluetooth.instance.stopScan();
```

### 4. BLE 连接与数据通信

```dart
var device = result.device;

// 连接
await device.connect();

// 发现服务
List<BluetoothService> services = await device.discoverServices();

for (var service in services) {
  for (var characteristic in service.characteristics) {
    // 读取
    var value = await characteristic.read();
    
    // 写入（发送数据）
    await characteristic.write([0x01, 0x02]);
    
    // 订阅通知（持续接收数据）
    await characteristic.setNotifyValue(true);
    characteristic.onValueReceived.listen((value) {
      print('收到通知: $value');
    });
  }
}

// 断开连接
await device.disconnect();
```

也可以使用便捷方法：

```dart
// 发送数据到指定特征
await device.sendBleData(
  Uint8List.fromList([0x01, 0x02, 0x03]),
  serviceUuid: Guid.short('ffe0'),
  characteristicUuid: Guid.short('ffe1'),
);

// 持续接收数据
final stream = device.getBleDataStream(
  serviceUuid: Guid.short('ffe0'),
  characteristicUuid: Guid.short('ffe1'),
);
stream.listen((value) => print('收到: $value'));
```

### 5. 经典蓝牙（SPP 串口）数据通信

```dart
// 建立 RFCOMM SPP 连接
await device.connectRfcomm();

// 持续接收数据
device.onRfcommDataReceived.listen((data) {
  print('收到: ${utf8.decode(data)}');
});

// 发送数据
await device.sendRfcommData(
  Uint8List.fromList(utf8.encode('Hello!')),
);

// 断开连接
await device.disconnectRfcomm();
```

## API 参考

### FlutterBluetooth（主入口类）

| 方法 | 说明 |
|------|------|
| `isSupported` | 检查设备是否支持蓝牙 |
| `getAdapterName()` | 获取本机蓝牙适配器名称 |
| `turnOn()` | 开启蓝牙 |
| `startScan()` | 开始扫描经典蓝牙 + BLE 设备 |
| `stopScan()` | 停止扫描 |
| `scanResults` | 扫描结果流 |
| `adapterState` | 适配器开/关状态流 |
| `getBondedDevices()` | 获取已配对设备列表 |
| `getSystemDevices()` | 获取系统已连接的 BLE 设备 |

### BluetoothDevice（设备模型）

| 方法 | 说明 |
|------|------|
| `connect()` | BLE GATT 连接 |
| `disconnect()` | 断开连接（同时断开 BLE 和 RFCOMM） |
| `connectRfcomm()` | 建立 RFCOMM SPP 经典蓝牙连接 |
| `disconnectRfcomm()` | 断开 RFCOMM 连接 |
| `sendRfcommData(List<int>)` | 通过 RFCOMM 发送数据 |
| `onRfcommDataReceived` | RFCOMM 数据接收流 |
| `discoverServices()` | 发现 BLE GATT 服务 |
| `sendBleData()` | 便捷：发送 BLE 数据到指定特征 |
| `getBleDataStream()` | 便捷：订阅 BLE 特征通知 |
| `readRssi()` | 读取信号强度 |
| `requestMtu()` | 协商 MTU 大小 |
| `createBond()` | 配对设备 |
| `removeBond()` | 解除配对 |

### BluetoothCharacteristic（BLE 特征）

| 方法 | 说明 |
|------|------|
| `read()` | 读取特征值 |
| `write()` | 写入特征值 |
| `setNotifyValue()` | 启用/禁用通知 |
| `onValueReceived` | 通知值流 |

## 项目结构

```
flutter_bluetooth/
├── lib/
│   ├── flutter_bluetooth.dart              # 库入口
│   └── src/
│       ├── flutter_bluetooth_impl.dart      # FlutterBluetooth 主类
│       ├── bluetooth_device.dart            # BluetoothDevice 设备模型
│       ├── bluetooth_service.dart           # GATT 服务模型
│       ├── bluetooth_characteristic.dart    # GATT 特征（read/write/notify）
│       ├── bluetooth_descriptor.dart        # GATT 描述符
│       └── utils.dart                       # Guid、ScanResult、枚举
├── android/src/main/kotlin/io/github/berial/flutter_bluetooth/
│   ├── FlutterBluetoothPlugin.kt            # MethodChannel 处理器
│   ├── BleManager.kt                        # BLE 扫描 + GATT 管理
│   ├── ClassicBluetoothManager.kt           # 经典蓝牙扫描
│   └── RfcommManager.kt                    # RFCOMM SPP 串口通信
└── example/
    └── lib/main.dart                        # 示例应用
```

## 通信机制

- **MethodChannel**: `io.github.berial.flutter_bluetooth/methods` — 所有同步/异步调用
- **EventChannel**: `io.github.berial.flutter_bluetooth/events` — 事件推送（扫描结果、连接状态、适配器状态、特征通知、RFCOMM 数据）

## 许可证

MIT
