/// Flutter 蓝牙插件
///
/// 一个支持经典蓝牙（BR/EDR）和蓝牙低功耗（BLE）扫描与通信的
/// Android Flutter 插件。
///
/// API 设计模仿 flutter_blue_plus，便于使用。
library flutter_bluetooth;

import 'dart:async';

import 'package:flutter/services.dart';

part 'src/utils.dart';
part 'src/bluetooth_device.dart';
part 'src/bluetooth_service.dart';
part 'src/bluetooth_characteristic.dart';
part 'src/bluetooth_descriptor.dart';
part 'src/flutter_bluetooth_impl.dart';

// 重新导出主入口类以方便使用。
// 所有公共类型已通过 parts 可见。
