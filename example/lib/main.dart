import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_bluetooth/flutter_bluetooth.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const BluetoothExampleApp());
}

class BluetoothExampleApp extends StatelessWidget {
  const BluetoothExampleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Flutter Bluetooth Example',
      theme: ThemeData(
        colorSchemeSeed: Colors.blue,
        useMaterial3: true,
        brightness: Brightness.light,
      ),
      darkTheme: ThemeData(
        colorSchemeSeed: Colors.blue,
        useMaterial3: true,
        brightness: Brightness.dark,
      ),
      home: const BluetoothScanPage(),
    );
  }
}

class BluetoothScanPage extends StatefulWidget {
  const BluetoothScanPage({super.key});

  @override
  State<BluetoothScanPage> createState() => _BluetoothScanPageState();
}

class _BluetoothScanPageState extends State<BluetoothScanPage> {
  bool _isSupported = false;
  bool _isScanning = false;
  BluetoothAdapterState _adapterState = BluetoothAdapterState.unknown;
  String _adapterName = '';
  List<ScanResult> _scanResults = [];
  StreamSubscription<List<ScanResult>>? _scanSubscription;

  @override
  void initState() {
    super.initState();
    _initBluetooth();
  }

  Future<void> _initBluetooth() async {
    final supported = await FlutterBluetooth.instance.isSupported;
    final name = await FlutterBluetooth.instance.getAdapterName();

    setState(() {
      _isSupported = supported;
      _adapterName = name;
    });

    if (supported) {
      FlutterBluetooth.instance.adapterState.listen((state) {
        setState(() => _adapterState = state);
      });
    }
  }

  Future<void> _startScan() async {
    try {
      setState(() => _isScanning = true);
      _scanResults.clear();

      _scanSubscription = FlutterBluetooth.instance.scanResults.listen((results) {
        setState(() => _scanResults = List.from(results));
      });

      await FlutterBluetooth.instance.startScan(
        scanMode: 'lowLatency',
        scanClassic: true,
        timeout: const Duration(seconds: 30),
      );
    } catch (e) {
      setState(() => _isScanning = false);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Scan error: $e')),
        );
      }
    }
  }

  Future<void> _stopScan() async {
    await FlutterBluetooth.instance.stopScan();
    _scanSubscription?.cancel();
    setState(() => _isScanning = false);
  }

  Future<void> _connect(BluetoothDevice device) async {
    try {
      if (device.type == 'classic') {
        final connected = await device.connectRfcomm();
        if (connected && mounted) {
          Navigator.push(
            context,
            MaterialPageRoute(
              builder: (_) => DataCommunicationPage(device: device, mode: 'rfcomm'),
            ),
          );
        }
      } else {
        await device.connect(
          autoConnect: false,
          timeout: const Duration(seconds: 15),
        );
        final services = await device.discoverServices();
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('BLE Connected! Found ${services.length} services')),
          );
          Navigator.push(
            context,
            MaterialPageRoute(
              builder: (_) => DataCommunicationPage(device: device, mode: 'ble'),
            ),
          ).then((_) {
            device.disconnect();
          });
        }
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Connect error: $e')),
        );
      }
    }
  }

  Future<void> _disconnect(BluetoothDevice device) async {
    await device.disconnect();
  }

  IconData _deviceTypeIcon(String type) {
    switch (type) {
      case 'classic':
        return Icons.bluetooth;
      case 'ble':
        return Icons.bluetooth_connected;
      default:
        return Icons.devices_other;
    }
  }

  String _deviceTypeLabel(String type) {
    switch (type) {
      case 'classic':
        return 'Classic';
      case 'ble':
        return 'BLE';
      case 'dual':
        return 'Dual';
      default:
        return type;
    }
  }

  @override
  void dispose() {
    _scanSubscription?.cancel();
    FlutterBluetooth.instance.stopScan();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Flutter Bluetooth'),
        actions: [
          if (_isSupported) ...[
            if (_isScanning)
              IconButton(
                icon: const Icon(Icons.stop),
                tooltip: 'Stop scan',
                onPressed: _stopScan,
              )
            else
              IconButton(
                icon: const Icon(Icons.search),
                tooltip: 'Start scan',
                onPressed: _startScan,
              ),
          ],
        ],
      ),
      body: Column(
        children: [
          _buildStatusCard(),
          Expanded(
            child: _isScanning && _scanResults.isEmpty
                ? const Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        CircularProgressIndicator(),
                        SizedBox(height: 16),
                        Text('Scanning for devices...'),
                      ],
                    ),
                  )
                : _scanResults.isEmpty
                    ? const Center(
                        child: Text('Tap search to scan for devices'),
                      )
                    : ListView.separated(
                        itemCount: _scanResults.length,
                        separatorBuilder: (_, __) => const Divider(height: 1),
                        itemBuilder: (context, index) {
                          final result = _scanResults[index];
                          return _buildDeviceTile(result);
                        },
                      ),
          ),
        ],
      ),
    );
  }

  Widget _buildStatusCard() {
    return Card(
      margin: const EdgeInsets.all(12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  _isSupported ? Icons.check_circle : Icons.error,
                  color: _isSupported ? Colors.green : Colors.red,
                ),
                const SizedBox(width: 8),
                Text(
                  _isSupported ? 'Bluetooth Supported' : 'Bluetooth Not Supported',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
              ],
            ),
            const SizedBox(height: 8),
            if (_adapterName.isNotEmpty) Text('Adapter: $_adapterName'),
            Text('State: ${_adapterState.name}'),
          ],
        ),
      ),
    );
  }

  Widget _buildDeviceTile(ScanResult result) {
    final device = result.device;
    return ListTile(
      leading: CircleAvatar(
        backgroundColor: device.type == 'classic' ? Colors.blue.shade100 : Colors.green.shade100,
        child: Icon(
          _deviceTypeIcon(device.type),
          color: device.type == 'classic' ? Colors.blue : Colors.green,
        ),
      ),
      title: Text(
        device.platformName.isNotEmpty ? device.platformName : 'Unknown',
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(device.remoteId),
          Row(
            children: [
              Chip(
                label: Text(
                  _deviceTypeLabel(device.type),
                  style: const TextStyle(fontSize: 10),
                ),
                visualDensity: VisualDensity.compact,
                padding: EdgeInsets.zero,
                materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
              ),
              const SizedBox(width: 4),
              Text('RSSI: ${result.rssi} dBm', style: Theme.of(context).textTheme.bodySmall),
            ],
          ),
        ],
      ),
      trailing: device.isConnected
          ? IconButton(
              icon: const Icon(Icons.link_off),
              onPressed: () => _disconnect(device),
              tooltip: 'Disconnect',
            )
          : IconButton(
              icon: const Icon(Icons.link),
              onPressed: () => _connect(device),
              tooltip: 'Connect',
            ),
      isThreeLine: true,
    );
  }
}

// ────────────────────────────────────────────────────────────────────────
// Data Communication Page — send & continuously receive data
// ────────────────────────────────────────────────────────────────────────

class DataCommunicationPage extends StatefulWidget {
  final BluetoothDevice device;
  final String mode;

  const DataCommunicationPage({
    super.key,
    required this.device,
    required this.mode,
  });

  @override
  State<DataCommunicationPage> createState() => _DataCommunicationPageState();
}

class _DataCommunicationPageState extends State<DataCommunicationPage> {
  final TextEditingController _hexController = TextEditingController();
  final TextEditingController _textController = TextEditingController();
  final List<_DataLog> _dataLogs = [];
  final ScrollController _scrollController = ScrollController();
  StreamSubscription? _dataSubscription;
  Timer? _readTimer;
  bool _autoReadEnabled = false;

  @override
  void initState() {
    super.initState();
    _setupDataReceiver();
  }

  void _setupDataReceiver() {
    if (widget.mode == 'rfcomm') {
      _dataSubscription = widget.device.onRfcommDataReceived.listen(
        (data) => _addLog(data, isSent: false),
        onError: (e) => _addError('Stream error: $e'),
      );
    }
  }

  /// Start periodic polling using the HEX input content (1s interval).
  /// The HEX field must be filled with a valid command beforehand.
  void _startAutoRead() {
    _stopAutoRead();
    _autoReadEnabled = true;
    _readTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      _sendPolledHex();
    });
    // Send first immediately.
    _sendPolledHex();
    if (mounted) setState(() {});
  }

  /// Send the current HEX field content silently (no SEND log).
  Future<void> _sendPolledHex() async {
    final text = _hexController.text.trim();
    if (text.isEmpty) return;

    Uint8List bytes;
    try {
      bytes = _parseHex(text);
    } catch (_) {
      return; // bad hex → skip silently
    }

    try {
      if (widget.mode == 'rfcomm') {
        await widget.device.sendRfcommData(bytes);
      } else {
        await _sendBytes(bytes);
      }
    } catch (_) {
      // silently skip polling errors
    }
  }

  /// Stop periodic polling.
  void _stopAutoRead() {
    _readTimer?.cancel();
    _readTimer = null;
    _autoReadEnabled = false;
    if (mounted) setState(() {});
  }

  void _toggleAutoRead() {
    if (_autoReadEnabled) {
      _stopAutoRead();
    } else {
      _startAutoRead();
    }
  }

  void _setupBleStream(Guid serviceUuid, Guid characteristicUuid) {
    _dataSubscription?.cancel();
    try {
      final stream = widget.device.getBleDataStream(
        serviceUuid: serviceUuid,
        characteristicUuid: characteristicUuid,
      );
      _dataSubscription = stream.listen(
        (data) => _addLog(data, isSent: false),
        onError: (e) => _addError('BLE stream error: $e'),
      );
      setState(() {});
    } catch (e) {
      _addError('Failed to start BLE stream: $e');
    }
  }

  void _addLog(Uint8List data, {required bool isSent}) {
    setState(() {
      _dataLogs.add(_DataLog(
        data: data,
        isSent: isSent,
        time: DateTime.now(),
      ));
    });

    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 200),
          curve: Curves.easeOut,
        );
      }
    });
  }

  void _addError(String message) {
    setState(() {
      _dataLogs.add(_DataLog(
        data: Uint8List.fromList(utf8.encode(message)),
        isSent: false,
        time: DateTime.now(),
        isError: true,
      ));
    });
  }

  /// Parse hex string like "AA BB CC" or "AABBCC" into bytes.
  Uint8List _parseHex(String hex) {
    final cleaned = hex.replaceAll(RegExp(r'[\s,]'), '');
    if (cleaned.length % 2 != 0) {
      throw FormatException('Hex string length must be even');
    }
    final bytes = <int>[];
    for (int i = 0; i < cleaned.length; i += 2) {
      bytes.add(int.parse(cleaned.substring(i, i + 2), radix: 16));
    }
    return Uint8List.fromList(bytes);
  }

  /// Send hex bytes (e.g. "AA BB 01 02").
  Future<void> _sendHex() async {
    final text = _hexController.text.trim();
    if (text.isEmpty) return;

    Uint8List bytes;
    try {
      bytes = _parseHex(text);
    } catch (e) {
      _addError('Invalid hex: $e');
      return;
    }

    _hexController.clear();
    _addLog(bytes, isSent: true);

    try {
      await _sendBytes(bytes);
    } catch (e) {
      _addError('Send hex failed: $e');
    }
  }

  /// Send UTF-8 string with trailing \r\n.
  Future<void> _sendText() async {
    final text = _textController.text;
    if (text.isEmpty) return;

    final payload = '$text\r\n';
    final bytes = Uint8List.fromList(utf8.encode(payload));
    _textController.clear();
    _addLog(bytes, isSent: true);

    try {
      await _sendBytes(bytes);
    } catch (e) {
      _addError('Send text failed: $e');
    }
  }

  /// Low-level send for both modes.
  Future<void> _sendBytes(Uint8List bytes) async {
    if (widget.mode == 'rfcomm') {
      await widget.device.sendRfcommData(bytes);
    } else {
      if (widget.device.servicesList.isEmpty) {
        await widget.device.discoverServices();
      }
      if (widget.device.servicesList.isNotEmpty &&
          widget.device.servicesList.first.characteristics.isNotEmpty) {
        await widget.device.servicesList.first.characteristics.first
            .write(bytes);
      }
    }
  }

  Future<void> _showBleSetupDialog() async {
    if (widget.device.servicesList.isEmpty) {
      await widget.device.discoverServices();
    }
    if (!mounted) return;

    final items = <_CharInfo>[];
    for (final service in widget.device.servicesList) {
      for (final char in service.characteristics) {
        if (char.properties.notify || char.properties.indicate) {
          items.add(_CharInfo(
            serviceUuid: service.serviceUuid,
            characteristicUuid: char.characteristicUuid,
            label: '${service.serviceUuid.str.substring(4, 8)} / '
                '${char.characteristicUuid.str.substring(4, 8)}',
          ));
        }
      }
    }

    if (!mounted) return;

    if (items.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('No notifiable characteristics found')),
      );
      return;
    }

    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Select Characteristic'),
        content: SizedBox(
          width: double.maxFinite,
          child: ListView.builder(
            shrinkWrap: true,
            itemCount: items.length,
            itemBuilder: (_, i) => ListTile(
              title: Text(items[i].label),
              onTap: () {
                Navigator.pop(ctx);
                _setupBleStream(
                  items[i].serviceUuid,
                  items[i].characteristicUuid,
                );
              },
            ),
          ),
        ),
      ),
    );
  }

  @override
  void dispose() {
    _readTimer?.cancel();
    _dataSubscription?.cancel();
    _hexController.dispose();
    _textController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(
          widget.device.platformName.isNotEmpty
              ? widget.device.platformName
              : widget.device.remoteId,
        ),
        actions: [
          if (widget.mode == 'ble')
            IconButton(
              icon: const Icon(Icons.settings_input_composite),
              tooltip: 'Setup BLE notify',
              onPressed: _showBleSetupDialog,
            ),
        ],
      ),
      body: Column(
        children: [
          _buildConnectionInfo(),
          Expanded(
            child: _dataLogs.isEmpty
                ? Center(
                    child: Text(
                      widget.mode == 'rfcomm'
                          ? 'Waiting for data...'
                          : 'Tap "Setup BLE notify" to start receiving',
                      style: Theme.of(context).textTheme.bodyLarge,
                    ),
                  )
                : ListView.builder(
                    controller: _scrollController,
                    padding: const EdgeInsets.all(12),
                    itemCount: _dataLogs.length,
                    itemBuilder: (_, i) => _buildDataRow(_dataLogs[i]),
                  ),
          ),
          _buildSendPanel(),
        ],
      ),
    );
  }

  Widget _buildConnectionInfo() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      child: Row(
        children: [
          Icon(Icons.bluetooth_connected,
              color: Theme.of(context).colorScheme.primary, size: 18),
          const SizedBox(width: 8),
          Text(
            widget.mode.toUpperCase(),
            style: TextStyle(
              fontWeight: FontWeight.bold,
              color: Theme.of(context).colorScheme.primary,
            ),
          ),
          const SizedBox(width: 8),
          Text(widget.device.remoteId,
              style: Theme.of(context).textTheme.bodySmall),
        ],
      ),
    );
  }

  Widget _buildDataRow(_DataLog log) {
    final color = log.isError
        ? Colors.red
        : log.isSent
            ? Colors.blue
            : Colors.green.shade700;
    final prefix =
        log.isError ? 'ERR' : log.isSent ? 'SEND' : 'RECV';

    String text;
    try {
      text = utf8.decode(log.data);
    } catch (_) {
      text = log.data
          .map((b) => '0x${b.toRadixString(16).padLeft(2, '0')}')
          .join(' ');
    }

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
            decoration: BoxDecoration(
              color: color.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(4),
            ),
            child: Text(
              prefix,
              style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.bold,
                color: color,
              ),
            ),
          ),
          const SizedBox(width: 8),
          Text(
            '${log.time.hour.toString().padLeft(2, '0')}:'
            '${log.time.minute.toString().padLeft(2, '0')}:'
            '${log.time.second.toString().padLeft(2, '0')}',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(fontSize: 10),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              text,
              style: TextStyle(
                fontSize: 13,
                fontFamily: 'monospace',
                color: log.isError ? Colors.red : null,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSendPanel() {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surface,
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.1),
            blurRadius: 4,
            offset: const Offset(0, -2),
          ),
        ],
      ),
      child: SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // ─── Polling toggle ─────────────────────────────────────────
            if (widget.mode == 'rfcomm')
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Row(
                  children: [
                    Icon(Icons.loop,
                        size: 16,
                        color: _autoReadEnabled ? Colors.green : Colors.grey),
                    const SizedBox(width: 4),
                    Text(
                      '轮询发送 HEX',
                      style: TextStyle(
                        fontSize: 11,
                        fontWeight: FontWeight.bold,
                        color: _autoReadEnabled ? Colors.green : Colors.grey,
                      ),
                    ),
                    const Spacer(),
                    Switch(
                      value: _autoReadEnabled,
                      onChanged: (_) => _toggleAutoRead(),
                      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                    ),
                  ],
                ),
              ),
            // Hex input row
            Row(
              children: [
                SizedBox(
                  width: 28,
                  child: Text(
                    'HEX',
                    style: TextStyle(
                      fontSize: 10,
                      fontWeight: FontWeight.bold,
                      color: Theme.of(context).colorScheme.primary,
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: TextField(
                    controller: _hexController,
                    decoration: InputDecoration(
                      hintText: 'e.g. AA BB 01 02',
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(20),
                      ),
                      contentPadding: const EdgeInsets.symmetric(
                        horizontal: 12,
                        vertical: 8,
                      ),
                      isDense: true,
                    ),
                    onSubmitted: (_) => _sendHex(),
                  ),
                ),
                const SizedBox(width: 8),
                FilledButton.tonalIcon(
                  onPressed: _sendHex,
                  icon: const Icon(Icons.send, size: 16),
                  label: const Text('HEX', style: TextStyle(fontSize: 12)),
                ),
              ],
            ),
            const SizedBox(height: 8),
            // String input row
            Row(
              children: [
                SizedBox(
                  width: 28,
                  child: Text(
                    'TXT',
                    style: TextStyle(
                      fontSize: 10,
                      fontWeight: FontWeight.bold,
                      color: Theme.of(context).colorScheme.secondary,
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: TextField(
                    controller: _textController,
                    decoration: InputDecoration(
                      hintText: r'Send as UTF-8 (appends \r\n)',
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(20),
                      ),
                      contentPadding: const EdgeInsets.symmetric(
                        horizontal: 12,
                        vertical: 8,
                      ),
                      isDense: true,
                    ),
                    onSubmitted: (_) => _sendText(),
                  ),
                ),
                const SizedBox(width: 8),
                FilledButton.tonalIcon(
                  onPressed: _sendText,
                  icon: const Icon(Icons.send, size: 16),
                  label: const Text('TXT', style: TextStyle(fontSize: 12)),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _DataLog {
  final Uint8List data;
  final bool isSent;
  final DateTime time;
  final bool isError;

  _DataLog({
    required this.data,
    required this.isSent,
    required this.time,
    this.isError = false,
  });
}

class _CharInfo {
  final Guid serviceUuid;
  final Guid characteristicUuid;
  final String label;

  _CharInfo({
    required this.serviceUuid,
    required this.characteristicUuid,
    required this.label,
  });
}
