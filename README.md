# flutter_bluetooth

> 🤖 **本库由 AI 生成** — 代码、文档均通过 AI 辅助完成，请在实际使用前充分测试验证。

[English](./README_EN.md) | 中文

Android 蓝牙 Flutter 插件，支持**经典蓝牙（BR/EDR）**和 **BLE** 两种扫描与数据通信。

API 设计仿照 [flutter_blue_plus](https://github.com/chipweinberger/flutter_blue_plus)，提供熟悉的开发体验。

面向 **LDAR（泄漏检测与修复）** 仪器连接场景设计，针对多设备并发、老旧仪器配对、稳定连接做了深度优化。

## 功能特性

- ✅ 扫描**经典蓝牙**（BR/EDR）设备
- ✅ 扫描 **BLE** 设备
- ✅ BLE GATT 连接、服务发现、读写、通知订阅
- ✅ 经典蓝牙 RFCOMM SPP 串口通信（发送 + 持续接收）
- ✅ **RFCOMM 服务器模式**（接受传入连接）
- ✅ **配对请求处理**（PIN / Passkey / Consent，拦截系统默认 UI）
- ✅ RSSI 信号强度读取、MTU 协商、连接优先级
- ✅ 设备配对/绑定管理
- ✅ 适配器状态监听
- ✅ **Per-device 操作队列**（同设备 GATT/RFCOMM 操作串行，防 GATT_BUSY 133）
- ✅ **连接中间态**（connecting / disconnecting，防重复连接）
- ✅ **autoConnect 自动重连**
- ✅ **Android 13+ (API 33+) 新 API 适配**（writeCharacteristic/writeDescriptor 新签名）
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

// 连接（支持 autoConnect 自动重连、MTU 协商、超时取消）
await device.connect(
  autoConnect: false,
  timeout: Duration(seconds: 35),
  mtu: 512,
);

// 发现服务
List<BluetoothService> services = await device.discoverServices();

for (var service in services) {
  for (var characteristic in service.characteristics) {
    // 读取
    var value = await characteristic.read();

    // 写入（发送数据，支持 withoutResponse 模式）
    await characteristic.write([0x01, 0x02], withoutResponse: false);

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
// 返回值：true=成功，false=写入失败（IOException），
//         抛 PlatformException(NOT_CONNECTED)=设备未连接
final ok = await device.sendRfcommData(
  Uint8List.fromList(utf8.encode('Hello!')),
);
if (!ok) {
  print('发送失败');
}

// 断开连接
await device.disconnectRfcomm();
```

### 6. RFCOMM 服务器模式

```dart
// 启动服务器，接受传入的 RFCOMM 连接
final ok = await FlutterBluetooth.instance.startRfcommServer(
  uuid: '00001101-0000-1000-8000-00805F9B34FB',
  name: 'FlutterBluetooth',
);
if (ok) {
  print('服务器已启动，UUID: ${FlutterBluetooth.instance.serverUuid}');
}

// 监听服务器状态
FlutterBluetooth.instance.rfcommServerState.listen((state) {
  print('服务器: ${state.isRunning ? "运行中" : "已停止"}');
});

// 传入连接会通过 rfcommConnectionStateChanged 事件推送到对应设备
// 使用 device.onRfcommDataReceived 接收数据

// 停止服务器（已建立的连接不受影响）
await FlutterBluetooth.instance.stopRfcommServer();
```

### 7. 配对请求处理

用于支持需要 PIN/Passkey 的老旧 LDAR 仪器。启用后拦截系统默认配对 UI，由 Dart 端响应：

```dart
// 启用配对请求处理
await FlutterBluetooth.instance.enablePairingRequestHandling();

// 监听配对请求
FlutterBluetooth.instance.pairingRequest.listen((request) {
  print('配对请求: ${request.remoteId}, 变体: ${request.variant}');

  if (request.variant == PairingVariant.pin) {
    // PIN 变体：设置 PIN
    FlutterBluetooth.instance.respondPairingPin(
      remoteId: request.remoteId,
      pin: '1234',
    );
  } else if (request.variant.needsResponse) {
    // PasskeyConfirmation / Consent：确认或拒绝
    FlutterBluetooth.instance.respondPairingConfirmation(
      remoteId: request.remoteId,
      confirm: true,
    );
  }
  // displayPasskey / displayPin 变体仅通知，无需响应
});

// 禁用配对请求处理，恢复系统默认 UI
await FlutterBluetooth.instance.disablePairingRequestHandling();
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
| `startRfcommServer()` | 启动 RFCOMM 服务器（接受传入连接） |
| `stopRfcommServer()` | 停止 RFCOMM 服务器 |
| `isServerRunning` | 服务器是否正在运行 |
| `rfcommServerState` | 服务器状态流 |
| `enablePairingRequestHandling()` | 启用配对请求处理（拦截系统 UI） |
| `disablePairingRequestHandling()` | 禁用配对请求处理 |
| `respondPairingPin()` | 响应 PIN 配对请求 |
| `respondPairingConfirmation()` | 响应 Passkey/Consent 配对请求 |
| `pairingRequest` | 配对请求事件流 |

### BluetoothDevice（设备模型）

| 方法 | 说明 |
|------|------|
| `connect()` | BLE GATT 连接（支持 autoConnect / timeout / mtu） |
| `disconnect()` | 断开连接（同时断开 BLE 和 RFCOMM） |
| `cancelConnect()` | 取消进行中的连接尝试 |
| `connectionState` | 连接状态流（含 connecting/disconnecting 中间态） |
| `isConnected` / `isConnecting` / `isDisconnecting` | 当前状态查询 |
| `connectRfcomm()` | 建立 RFCOMM SPP 经典蓝牙连接 |
| `disconnectRfcomm()` | 断开 RFCOMM 连接 |
| `sendRfcommData(List<int>)` | 通过 RFCOMM 发送数据（见返回值契约） |
| `readRfcommData()` | 同步读取一次 RFCOMM 数据 |
| `onRfcommDataReceived` | RFCOMM 数据接收流 |
| `discoverServices()` | 发现 BLE GATT 服务 |
| `sendBleData()` | 便捷：发送 BLE 数据到指定特征 |
| `getBleDataStream()` | 便捷：订阅 BLE 特征通知 |
| `readRssi()` | 读取信号强度 |
| `requestMtu()` | 协商 MTU 大小 |
| `requestConnectionPriority()` | 请求连接优先级 |
| `clearGattCache()` | 清除 GATT 缓存 |
| `createBond()` | 配对设备（可预设 PIN） |
| `removeBond()` | 解除配对 |

### BluetoothCharacteristic（BLE 特征）

| 方法 | 说明 |
|------|------|
| `read()` | 读取特征值 |
| `write()` | 写入特征值（支持 withoutResponse） |
| `setNotifyValue()` | 启用/禁用通知（自动选择 notify/indicate） |
| `onValueReceived` | 通知值流 |

## 稳定性设计

本插件针对 LDAR 仪器连接场景做了以下稳定性优化：

- **Per-device 操作队列**：同一设备的 GATT/RFCOMM 操作串行执行，跨设备并行，避免 GATT_BUSY (133) 错误
- **连接中间态**：connecting/disconnecting 由 Dart 端合成，原生只推终态，防止重复连接尝试
- **RFCOMM 三级回退**：insecure socket → secure socket → 反射 createRfcommSocket(1)，兼容顽固设备
- **RFCOMM 写入互斥**：per-device Mutex 串行化写入，自动 512 字节分块，避免字节错乱
- **BLE 分包写入**：按 MTU-3 自动分块，withoutResponse + 大包自动退化为 withResponse 避免死锁
- **autoConnect 重连**：autoConnect 设备断开时跳过 gatt.close() 保留句柄
- **扫描状态分离**：BLE 和 Classic 分别跟踪，两侧都结束才推全局停止
- **配对请求拦截**：abortBroadcast 阻止系统 UI，Dart 端程序化响应
- **超时兜底**：连接 / 写入 / 扫描 / 配对请求均有超时机制，避免 Future 挂死
- **Android 13+ 适配**：writeCharacteristic/writeDescriptor 新签名 + 旧 API false 返回值显式报错

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
│       └── utils.dart                       # Guid、ScanResult、枚举、操作队列
├── android/src/main/kotlin/io/github/berial/flutter_bluetooth/
│   ├── FlutterBluetoothPlugin.kt            # MethodChannel 处理器
│   ├── BleManager.kt                        # BLE 扫描 + GATT 管理
│   ├── ClassicBluetoothManager.kt           # 经典蓝牙扫描
│   ├── RfcommManager.kt                     # RFCOMM SPP 串口通信 + 服务器
│   └── PairingRequestManager.kt             # 配对请求处理
└── example/
    └── lib/main.dart                        # 示例应用
```

## 通信机制

- **MethodChannel**: `io.github.berial.flutter_bluetooth/methods` — 所有同步/异步调用
- **EventChannel**: `io.github.berial.flutter_bluetooth/events` — 事件推送（扫描结果、连接状态、适配器状态、特征通知、RFCOMM 数据、配对请求、服务器状态）

## 许可证

MIT
