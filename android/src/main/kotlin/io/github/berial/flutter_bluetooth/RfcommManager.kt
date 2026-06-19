package io.github.berial.flutter_bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 管理经典蓝牙 RFCOMM（SPP）套接字连接，实现双向数据流传输。
 *
 * 每个已连接设备拥有：
 * - 一个 [BluetoothSocket] 用于 RFCOMM 通道
 * - 一个输入流读取协程（持续读取传入数据）
 * - 一个输出流用于发送数据
 */
class RfcommManager(
    private val bluetoothAdapter: BluetoothAdapter?,
    private val sendEvent: (Map<String, Any?>) -> Unit,
    private val onDisconnected: (String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 活跃的 RFCOMM 连接：remoteId -> (socket, outputStream)
    private data class RfcommConnection(
        val socket: BluetoothSocket,
        val outputStream: OutputStream,
        val inputStream: InputStream,
        val readJob: Job
    )

    private val connections = ConcurrentHashMap<String, RfcommConnection>()

    /**
     * 通过 RFCOMM 使用标准 SPP UUID 连接到经典蓝牙设备。
     *
     * @param device 远程蓝牙设备
     * @param uuidString 可选的自定义 SPP 服务 UUID。
     *                   默认使用标准 SPP UUID。
     * @param onResult 回调，返回成功/失败
     */
    fun connect(device: BluetoothDevice, uuidString: String?, onResult: (Boolean, String?) -> Unit) {
        scope.launch {
            try {
                val uuid = try {
                    UUID.fromString(uuidString ?: SPP_UUID)
                } catch (e: IllegalArgumentException) {
                    UUID.fromString(SPP_UUID)
                }

                // Android 4.2+ 支持 createRfcommSocketToServiceRecord
                val socket: BluetoothSocket = try {
                    device.createRfcommSocketToServiceRecord(uuid)
                } catch (e: Exception) {
                    // 回退：使用反射创建不安全 RFCOMM 连接
                    try {
                        val method = device.javaClass.getMethod("createInsecureRfcommSocketToServiceRecord", UUID::class.java)
                        method.invoke(device, uuid) as BluetoothSocket
                    } catch (refEx: Exception) {
                        onResult(false, "Failed to create RFCOMM socket: ${refEx.message}")
                        return@launch
                    }
                }

                // 取消发现 — 发现会减慢连接速度
                bluetoothAdapter?.cancelDiscovery()

                // 连接（在 IO 线程上阻塞）
                socket.connect()

                val inputStream = socket.inputStream
                val outputStream = socket.outputStream

                // 启动持续读取循环
                val readJob = scope.launch {
                    readLoop(device.address, inputStream, socket)
                }

                connections[device.address] = RfcommConnection(
                    socket = socket,
                    outputStream = outputStream,
                    inputStream = inputStream,
                    readJob = readJob
                )

                withContext(Dispatchers.Main) {
                    onResult(true, null)
                    sendEvent(mapOf(
                        "type" to "rfcommConnectionStateChanged",
                        "remoteId" to device.address,
                        "state" to "connected"
                    ))
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    // 尝试回退：某些设备需要不安全连接
                    tryFallbackInsecure(device, uuidString, onResult)
                }
            } catch (e: SecurityException) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Permission denied: ${e.message}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Connection failed: ${e.message}")
                }
            }
        }
    }

    private fun tryFallbackInsecure(device: BluetoothDevice, uuidString: String?, onResult: (Boolean, String?) -> Unit) {
        scope.launch {
            try {
                val uuid = try {
                    UUID.fromString(uuidString ?: SPP_UUID)
                } catch (e: IllegalArgumentException) {
                    UUID.fromString(SPP_UUID)
                }

                val method = device.javaClass.getMethod("createInsecureRfcommSocketToServiceRecord", UUID::class.java)
                val socket = method.invoke(device, uuid) as BluetoothSocket

                bluetoothAdapter?.cancelDiscovery()
                socket.connect()

                val inputStream = socket.inputStream
                val outputStream = socket.outputStream

                val readJob = scope.launch {
                    readLoop(device.address, inputStream, socket)
                }

                connections[device.address] = RfcommConnection(
                    socket = socket,
                    outputStream = outputStream,
                    inputStream = inputStream,
                    readJob = readJob
                )

                withContext(Dispatchers.Main) {
                    onResult(true, null)
                    sendEvent(mapOf(
                        "type" to "rfcommConnectionStateChanged",
                        "remoteId" to device.address,
                        "state" to "connected"
                    ))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "All connection attempts failed: ${e.message}")
                }
            }
        }
    }

    /**
     * 向已连接的 RFCOMM 设备发送数据。
     *
     * @return 数据是否成功写入
     */
    suspend fun sendData(remoteId: String, data: ByteArray): Boolean {
        val conn = connections[remoteId]
        if (conn == null) return false

        return withContext(Dispatchers.IO) {
            try {
                conn.outputStream.write(data)
                conn.outputStream.flush()
                true
            } catch (e: IOException) {
                // 写入失败 — 设备可能已断开
                false
            }
        }
    }

    /**
     * 断开设备的 RFCOMM 套接字连接。
     */
    fun disconnect(remoteId: String) {
        val conn = connections.remove(remoteId) ?: return
        try {
            conn.readJob.cancel()
            conn.inputStream.close()
        } catch (_: Exception) {}
        try {
            conn.outputStream.close()
        } catch (_: Exception) {}
        try {
            conn.socket.close()
        } catch (_: Exception) {}

        onDisconnected(remoteId)
        sendEvent(mapOf(
            "type" to "rfcommConnectionStateChanged",
            "remoteId" to remoteId,
            "state" to "disconnected"
        ))
    }

    /**
     * 断开所有 RFCOMM 连接。
     */
    fun disconnectAll() {
        connections.keys.toList().forEach { disconnect(it) }
    }

    /**
     * 检查设备是否有活跃的 RFCOMM 连接。
     */
    fun isConnected(remoteId: String): Boolean = connections.containsKey(remoteId)

    // ─── 内部读取循环 ──────────────────────────────────────────────────

    private suspend fun readLoop(remoteId: String, inputStream: InputStream, socket: BluetoothSocket) {
        val buffer = ByteArray(1024)
        try {
            while (coroutineContext.isActive) {
                val bytesRead = withContext(Dispatchers.IO) {
                    try {
                        inputStream.read(buffer)
                    } catch (e: IOException) {
                        -1
                    }
                }

                if (bytesRead == -1) break  // EOF — 连接已关闭

                if (bytesRead > 0) {
                    val data = buffer.copyOf(bytesRead)
                    sendEvent(mapOf(
                        "type" to "rfcommDataReceived",
                        "remoteId" to remoteId,
                        "data" to data.toList().map { it.toInt() and 0xFF }
                    ))
                }
            }
        } catch (e: IOException) {
            // 读取中断 — 断开时正常
        } catch (e: Exception) {
            android.util.Log.w("RfcommManager", "Read loop error for $remoteId: ${e.message}")
        } finally {
            // 读取停止时自动清理
            try { inputStream.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
            connections.remove(remoteId)
            onDisconnected(remoteId)
            sendEvent(mapOf(
                "type" to "rfcommConnectionStateChanged",
                "remoteId" to remoteId,
                "state" to "disconnected"
            ))
        }
    }

    companion object {
        /** 标准串口配置文件 UUID */
        const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
    }
}
