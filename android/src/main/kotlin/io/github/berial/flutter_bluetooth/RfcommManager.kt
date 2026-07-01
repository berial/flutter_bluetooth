package io.github.berial.flutter_bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * - 一个输出流用于发送数据（通过 [Mutex] 串行化写入）
 * - 一个 pendingReads 队列，供 [readDataOnce] 从 [readLoop] 派发数据
 */
class RfcommManager(
    private val bluetoothAdapter: BluetoothAdapter?,
    private val sendEvent: (Map<String, Any?>) -> Unit,
    private val onDisconnected: (String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private data class RfcommConnection(
        val socket: BluetoothSocket,
        val outputStream: OutputStream,
        val inputStream: InputStream,
        val readJob: Job,
        val writeMutex: Mutex,
        /** pending readDataOnce 请求队列。readLoop 命中时按 FIFO 派发。 */
        val pendingReads: ArrayDeque<PendingRead>,
        val pendingReadsLock: Any
    )

    /** 单条 pending read 请求，含 [maxSize] 用于截断派发数据。 */
    private data class PendingRead(
        val deferred: CompletableDeferred<ByteArray?>,
        val maxSize: Int
    )

    private val connections = ConcurrentHashMap<String, RfcommConnection>()

    // ─── 连接 ───────────────────────────────────────────────────────────

    /**
     * 通过 RFCOMM 连接到经典蓝牙设备。
     *
     * 连接策略（参照 flutter_bluetooth_serial）：
     * 1. 先取消蓝牙发现（避免拖慢连接）
     * 2. 优先 insecure socket（兼容性更好，不强制配对）
     * 3. 回退 1：secure socket
     * 4. 回退 2：反射 createRfcommSocket(channel=1)，用于 SDP 异常的顽固设备
     *
     * 失败时所有创建的 socket 都会被关闭，无泄漏。
     * 重复 connect 同一设备会先断开旧连接。
     */
    fun connect(device: BluetoothDevice, uuidString: String?, onResult: (Boolean, String?) -> Unit) {
        scope.launch {
            // 重复 connect 守卫：先清理旧连接
            connections.remove(device.address)?.let { closeConnectionQuietly(it) }

            val uuid = parseUuid(uuidString, onResult) ?: return@launch

            // 先取消发现（Android 建议连接前取消）
            try { bluetoothAdapter?.cancelDiscovery() } catch (_: SecurityException) {}

            // 尝试多种 socket 创建方式
            val socket = tryConnect(device, uuid)
            if (socket == null) {
                withContext(Dispatchers.Main) {
                    onResult(false, "All connection attempts failed")
                }
                return@launch
            }

            try {
                // 连接超时兜底：避免 socket.connect() 长时间阻塞（LDAR 仪器离线时）
                withTimeout(15_000) {
                    withContext(Dispatchers.IO) { socket.connect() }
                }
            } catch (e: IOException) {
                // 连接失败 — 关闭 socket 避免泄漏
                try { socket.close() } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    onResult(false, "Connection failed: ${e.message}")
                }
                return@launch
            } catch (e: SecurityException) {
                try { socket.close() } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    onResult(false, "Permission denied: ${e.message}")
                }
                return@launch
            } catch (_: TimeoutCancellationException) {
                try { socket.close() } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    onResult(false, "Connection timed out")
                }
                return@launch
            }

            val inputStream = socket.inputStream
            val outputStream = socket.outputStream
            var conn = RfcommConnection(
                socket = socket,
                outputStream = outputStream,
                inputStream = inputStream,
                readJob = Job(),
                writeMutex = Mutex(),
                pendingReads = ArrayDeque(),
                pendingReadsLock = Any()
            )
            conn = conn.copy(readJob = scope.launch { readLoop(device.address, conn) })

            connections[device.address] = conn

            withContext(Dispatchers.Main) {
                onResult(true, null)
                sendEvent(mapOf(
                    "type" to "rfcommConnectionStateChanged",
                    "remoteId" to device.address,
                    "state" to "connected"
                ))
            }
        }
    }

    /** 依次尝试 insecure → secure → reflect channel=1，返回首个成功的 socket 或 null。 */
    private fun tryConnect(device: BluetoothDevice, uuid: UUID): BluetoothSocket? {
        // 1. insecure（兼容性最好）
        try {
            return device.createInsecureRfcommSocketToServiceRecord(uuid)
        } catch (_: Exception) {}

        // 2. secure
        try {
            return device.createRfcommSocketToServiceRecord(uuid)
        } catch (_: Exception) {}

        // 3. 反射 createRfcommSocket(int channel) — 终极回退，channel=1
        try {
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            return method.invoke(device, 1) as BluetoothSocket
        } catch (_: Exception) {}

        return null
    }

    /** 解析 UUID，格式错误时返回 null 并回调失败（不静默回退）。 */
    private suspend fun parseUuid(uuidString: String?, onResult: (Boolean, String?) -> Unit): UUID? {
        if (uuidString == null) return UUID.fromString(SPP_UUID)
        return try {
            UUID.fromString(uuidString)
        } catch (e: IllegalArgumentException) {
            withContext(Dispatchers.Main) {
                onResult(false, "Invalid UUID format: $uuidString")
            }
            null
        }
    }

    // ─── 数据发送（带互斥）──────────────────────────────────────────────

    /**
     * 向已连接的 RFCOMM 设备发送数据。
     * 通过 [Mutex] 串行化写入，避免并发 write 导致字节错乱。
     * 自动分块（[CHUNK_SIZE]）以避免超过 RFCOMM MTU 导致写入不稳定。
     */
    suspend fun sendData(remoteId: String, data: ByteArray): Boolean {
        val conn = connections[remoteId] ?: return false
        return conn.writeMutex.withLock {
            try {
                var offset = 0
                while (offset < data.size) {
                    val end = minOf(offset + CHUNK_SIZE, data.size)
                    conn.outputStream.write(data, offset, end - offset)
                    offset = end
                }
                conn.outputStream.flush()
                true
            } catch (e: IOException) {
                false
            }
        }
    }

    // ─── 同步读取（从 readLoop 派发）─────────────────────────────────────

    /**
     * 同步读取一次数据。
     *
     * **不直接读取 InputStream**（避免与 [readLoop] 抢流丢数据），
     * 而是挂起等待 [readLoop] 读到的下一段数据派发过来。
     * 若在 [timeoutMs] 内无数据到达，返回 null。
     */
    suspend fun readDataOnce(remoteId: String, maxSize: Int = 1024, timeoutMs: Long = 5000): ByteArray? {
        val conn = connections[remoteId] ?: return null
        val deferred = CompletableDeferred<ByteArray?>()
        val pending = PendingRead(deferred, maxSize)
        synchronized(conn.pendingReadsLock) {
            conn.pendingReads.addLast(pending)
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            synchronized(conn.pendingReadsLock) { conn.pendingReads.remove(pending) }
            null
        }
    }

    // ─── 断开 ───────────────────────────────────────────────────────────

    /**
     * 断开设备的 RFCOMM 套接字连接。
     *
     * 统一由 [cleanupConnection] 处理资源关闭与事件发送，
     * 与 [readLoop] 的 finally 通过 [ConcurrentHashMap.remove] 原子性互斥，避免重复发送。
     */
    fun disconnect(remoteId: String) {
        val conn = connections.remove(remoteId) ?: return
        cleanupConnection(remoteId, conn, sendRfcommEvent = true, callOnDisconnected = true)
    }

    /** 断开所有 RFCOMM 连接并停止服务器。 */
    fun disconnectAll() {
        connections.keys.toList().forEach { disconnect(it) }
        stopServer()
    }

    /** 检查设备是否有活跃的 RFCOMM 连接。 */
    fun isConnected(remoteId: String): Boolean = connections.containsKey(remoteId)

    /**
     * 统一连接清理。由 [disconnect]（主动）或 [readLoop] finally（远端断开）调用。
     * 调用方必须已从 [connections] remove（保证原子互斥）。
     *
     * @param sendRfcommEvent 是否发送 rfcommConnectionStateChanged 事件
     * @param callOnDisconnected 是否回调 onDisconnected（触发 connectionStateChanged）
     */
    private fun cleanupConnection(
        remoteId: String,
        conn: RfcommConnection,
        sendRfcommEvent: Boolean,
        callOnDisconnected: Boolean
    ) {
        try { conn.readJob.cancel() } catch (_: Exception) {}
        // 失败 pending 读取请求
        synchronized(conn.pendingReadsLock) {
            conn.pendingReads.forEach { it.deferred.complete(null) }
            conn.pendingReads.clear()
        }
        try { conn.inputStream.close() } catch (_: Exception) {}
        try { conn.outputStream.close() } catch (_: Exception) {}
        try { conn.socket.close() } catch (_: Exception) {}

        // 对称通知：两条事件都发送，Dart 端统一处理
        if (callOnDisconnected) {
            onDisconnected(remoteId)
        }
        if (sendRfcommEvent) {
            sendEvent(mapOf(
                "type" to "rfcommConnectionStateChanged",
                "remoteId" to remoteId,
                "state" to "disconnected"
            ))
        }
    }

    private fun closeConnectionQuietly(conn: RfcommConnection) {
        try { conn.readJob.cancel() } catch (_: Exception) {}
        synchronized(conn.pendingReadsLock) {
            conn.pendingReads.forEach { it.deferred.complete(null) }
            conn.pendingReads.clear()
        }
        try { conn.inputStream.close() } catch (_: Exception) {}
        try { conn.outputStream.close() } catch (_: Exception) {}
        try { conn.socket.close() } catch (_: Exception) {}
    }

    // ─── RFCOMM 服务器模式 ──────────────────────────────────────────────

    private data class ServerHolder(
        val serverSocket: BluetoothServerSocket,
        val acceptJob: Job
    )

    @Volatile
    private var server: ServerHolder? = null
    @Volatile
    private var serverStarting = false
    private val serverLock = Any()

    /**
     * 启动 RFCOMM 服务器，接受传入连接。
     * 通过 [serverLock] + [serverStarting] 保证 check-then-act 原子性，避免竞态。
     */
    fun startServer(
        uuidString: String?,
        name: String = "FlutterBluetooth",
        onResult: (Boolean, String?) -> Unit
    ) {
        // 同步预占：检查并设置 starting 标志
        synchronized(serverLock) {
            if (server != null || serverStarting) {
                onResult(false, "Server already running")
                return
            }
            serverStarting = true
        }

        scope.launch {
            val uuid = parseUuid(uuidString, onResult) ?: run {
                synchronized(serverLock) { serverStarting = false }
                return@launch
            }

            val serverSocket = try {
                bluetoothAdapter?.listenUsingRfcommWithServiceRecord(name, uuid)
            } catch (e: SecurityException) {
                synchronized(serverLock) { serverStarting = false }
                withContext(Dispatchers.Main) {
                    onResult(false, "Permission denied: ${e.message}")
                }
                return@launch
            } catch (e: Exception) {
                synchronized(serverLock) { serverStarting = false }
                withContext(Dispatchers.Main) {
                    onResult(false, "Failed to listen: ${e.message}")
                }
                return@launch
            }

            if (serverSocket == null) {
                synchronized(serverLock) { serverStarting = false }
                withContext(Dispatchers.Main) {
                    onResult(false, "Adapter unavailable")
                }
                return@launch
            }

            val acceptJob = scope.launch { acceptLoop(serverSocket) }
            synchronized(serverLock) {
                server = ServerHolder(serverSocket, acceptJob)
                serverStarting = false
            }

            withContext(Dispatchers.Main) {
                onResult(true, null)
                sendEvent(mapOf(
                    "type" to "rfcommServerStateChanged",
                    "state" to "started",
                    "uuid" to uuid.toString()
                ))
            }
        }
    }

    /** 停止 RFCOMM 服务器。已建立的连接不受影响。 */
    fun stopServer() {
        val s: ServerHolder?
        synchronized(serverLock) {
            s = server
            server = null
            serverStarting = false
        }
        if (s == null) return
        try { s.acceptJob.cancel() } catch (_: Exception) {}
        try { s.serverSocket.close() } catch (_: Exception) {}
        sendEvent(mapOf(
            "type" to "rfcommServerStateChanged",
            "state" to "stopped"
        ))
    }

    /** 服务器是否正在运行。 */
    fun isServerRunning(): Boolean = server != null

    private suspend fun acceptLoop(serverSocket: BluetoothServerSocket) {
        var consecutiveErrors = 0
        while (coroutineContext.isActive) {
            val socket = try {
                withContext(Dispatchers.IO) { serverSocket.accept() }
            } catch (e: IOException) {
                // serverSocket 被关闭（stopServer）— 正常退出
                break
            } catch (e: Exception) {
                consecutiveErrors++
                if (consecutiveErrors > ACCEPT_MAX_RETRIES) {
                    android.util.Log.e("RfcommManager", "acceptLoop exceeded max retries: ${e.message}")
                    break
                }
                // 线性退避，避免瞬时异常刷日志
                delay(200L * consecutiveErrors)
                continue
            }
            consecutiveErrors = 0

            try {
                val remoteDevice = socket.remoteDevice
                if (remoteDevice == null) {
                    try { socket.close() } catch (_: Exception) {}
                    continue
                }
                val remoteId = remoteDevice.address
                val inputStream = socket.inputStream
                val outputStream = socket.outputStream

                // 同一设备再次连入：先完整清理旧连接（含取消 readJob）
                connections.remove(remoteId)?.let { closeConnectionQuietly(it) }

                var conn = RfcommConnection(
                    socket = socket,
                    outputStream = outputStream,
                    inputStream = inputStream,
                    readJob = Job(),
                    writeMutex = Mutex(),
                    pendingReads = ArrayDeque(),
                    pendingReadsLock = Any()
                )
                conn = conn.copy(readJob = scope.launch { readLoop(remoteId, conn) })

                connections[remoteId] = conn

                withContext(Dispatchers.Main) {
                    sendEvent(mapOf(
                        "type" to "rfcommConnectionStateChanged",
                        "remoteId" to remoteId,
                        "state" to "connected",
                        "fromServer" to true
                    ))
                }
            } catch (e: Exception) {
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    // ─── 内部读取循环 ──────────────────────────────────────────────────

    private suspend fun readLoop(remoteId: String, conn: RfcommConnection) {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        try {
            while (coroutineContext.isActive) {
                val bytesRead = withContext(Dispatchers.IO) {
                    try {
                        conn.inputStream.read(buffer)
                    } catch (e: IOException) {
                        -1
                    }
                }

                if (bytesRead == -1) break  // EOF — 连接已关闭

                if (bytesRead > 0) {
                    val data = buffer.copyOf(bytesRead)
                    // 优先派发给 pending readDataOnce；无 pending 则推送到流
                    if (!dispatchReadData(conn, data)) {
                        // N8: 事件派发切主线程，与 connect/disconnect 保持一致
                        withContext(Dispatchers.Main) {
                            sendEvent(mapOf(
                                "type" to "rfcommDataReceived",
                                "remoteId" to remoteId,
                                // 直接传 ByteArray，StandardMessageCodec 会编码为 Dart Uint8List
                                "data" to data
                            ))
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // 读取中断 — 断开时正常
        } catch (e: Exception) {
            android.util.Log.w("RfcommManager", "Read loop error for $remoteId: ${e.message}")
        } finally {
            // 仅在连接仍在 map 中时清理并发送事件（与 disconnect 原子互斥）
            // 远端主动断开时：sendRfcommEvent=true, callOnDisconnected=true（对称通知）
            if (connections.remove(remoteId) != null) {
                cleanupConnection(
                    remoteId = remoteId,
                    conn = conn,
                    sendRfcommEvent = true,
                    callOnDisconnected = true
                )
            } else {
                // 已被 disconnect 清理 — 仅关闭资源，不再发事件
                closeConnectionQuietly(conn)
            }
        }
    }

    /** dispatchReadData 包装：返回 true 表示已派发给 pending 请求。按 maxSize 截断数据。 */
    private fun dispatchReadData(conn: RfcommConnection, data: ByteArray): Boolean {
        val pending = synchronized(conn.pendingReadsLock) {
            if (conn.pendingReads.isEmpty()) null else conn.pendingReads.removeFirst()
        }
        if (pending != null) {
            // 按 maxSize 截断，避免返回超出调用方预期的数据量
            val result = if (data.size > pending.maxSize && pending.maxSize > 0) {
                data.copyOf(pending.maxSize)
            } else {
                data
            }
            pending.deferred.complete(result)
            return true
        }
        return false
    }

    /** 释放管理器自身协程 scope。插件销毁时调用。 */
    fun dispose() {
        disconnectAll()
        scope.cancel()
    }

    companion object {
        /** 标准串口配置文件 UUID */
        const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

        /** 发送数据分块大小（字节），避免超过 RFCOMM MTU 导致写入不稳定。 */
        private const val CHUNK_SIZE = 512

        /** 读取循环缓冲区大小（字节）。 */
        private const val READ_BUFFER_SIZE = 4096

        /** acceptLoop 连续异常最大重试次数，超过则退出。 */
        private const val ACCEPT_MAX_RETRIES = 10
    }
}
