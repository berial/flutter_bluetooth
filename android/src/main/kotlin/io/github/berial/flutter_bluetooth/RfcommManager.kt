package io.github.berial.flutter_bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 在主线程管理 RFCOMM 状态，在 IO 线程执行阻塞的套接字操作。 */
class RfcommManager(
    private val bluetoothAdapter: BluetoothAdapter?,
    private val sendEvent: (Map<String, Any?>) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private class Connection(val socket: BluetoothSocket) {
        val input = socket.inputStream
        val output = socket.outputStream
        val writeMutex = Mutex()
        val pendingReads = ArrayDeque<PendingRead>()
        var remainder: ByteArray? = null
        lateinit var reader: Job
    }

    private data class PendingRead(
        val maxSize: Int,
        val result: CompletableDeferred<ByteArray?> = CompletableDeferred()
    )

    private val connections = mutableMapOf<String, Connection>()
    private val connecting = mutableMapOf<String, Job>()
    private val remainderAfterEof = mutableMapOf<String, ByteArray>()
    private var server: BluetoothServerSocket? = null
    private var serverStarting: Job? = null
    private var acceptJob: Job? = null

    fun connect(device: BluetoothDevice, uuidString: String?, onResult: (Boolean, String?) -> Unit) {
        val address = device.address
        val uuid = try {
            UUID.fromString(uuidString ?: SPP_UUID)
        } catch (_: IllegalArgumentException) {
            onResult(false, "Invalid UUID format: $uuidString")
            return
        }
        disconnect(address)
        connecting[address] = scope.launch {
            try {
                try { bluetoothAdapter?.cancelDiscovery() } catch (_: SecurityException) {}
                val socket = connectWithFallback(device, uuid)
                try {
                    installConnection(address, socket)
                } catch (e: Exception) {
                    closeQuietly(socket)
                    throw e
                }
                sendConnectionState(address, "connected")
                onResult(true, null)
            } catch (_: TimeoutCancellationException) {
                onResult(false, "Connection timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(false, "Connection failed: ${e.message}")
            } finally {
                if (connecting[address] === coroutineContext.job) connecting.remove(address)
            }
        }.also { job ->
            job.invokeOnCompletion { error ->
                if (error is CancellationException) onResult(false, "Connection canceled")
            }
        }
    }

    private suspend fun connectWithFallback(device: BluetoothDevice, uuid: UUID): BluetoothSocket {
        val candidates = listOf(
            { device.createInsecureRfcommSocketToServiceRecord(uuid) },
            { device.createRfcommSocketToServiceRecord(uuid) },
            {
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                method.invoke(device, 1) as BluetoothSocket
            }
        )
        var socket: BluetoothSocket? = null
        try {
            withTimeout(CONNECT_TIMEOUT_MS) {
                var lastFailure: Exception? = null
                for ((index, createSocket) in candidates.withIndex()) {
                    try {
                        val connect = suspend {
                            withContext(Dispatchers.IO) { socket = createSocket() }
                            connectSocket(checkNotNull(socket))
                        }
                        if (index == candidates.lastIndex) connect()
                        else withTimeout(FALLBACK_ATTEMPT_MS) { connect() }
                        return@withTimeout
                    } catch (e: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        lastFailure = e
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SecurityException) {
                        throw e
                    } catch (e: Exception) {
                        lastFailure = e
                    }
                    closeQuietly(socket)
                    socket = null
                }
                throw IOException("All connection attempts failed", lastFailure)
            }
            return checkNotNull(socket).also { socket = null }
        } finally {
            closeQuietly(socket)
        }
    }

    private suspend fun connectSocket(socket: BluetoothSocket) =
        suspendCancellableCoroutine<Unit> { continuation ->
            // Closing from another thread interrupts the blocking BluetoothSocket.connect().
            continuation.invokeOnCancellation { closeQuietly(socket) }
            scope.launch(Dispatchers.IO) {
                try {
                    socket.connect()
                    continuation.resume(Unit)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        }

    private fun installConnection(remoteId: String, socket: BluetoothSocket) {
        val connection = Connection(socket)
        remainderAfterEof.remove(remoteId)
        connections.put(remoteId, connection)?.let(::closeConnection)
        connection.reader = scope.launch { readLoop(remoteId, connection) }
    }

    suspend fun sendData(remoteId: String, data: ByteArray): Boolean {
        val connection = connections[remoteId] ?: return false
        return connection.writeMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    var offset = 0
                    while (offset < data.size) {
                        val size = minOf(CHUNK_SIZE, data.size - offset)
                        connection.output.write(data, offset, size)
                        offset += size
                    }
                    connection.output.flush()
                    true
                } catch (_: IOException) {
                    false
                }
            }
        }
    }

    /** 从上次分段读取的余量或随后收到的数据中取至多 [maxSize] 字节。 */
    suspend fun readDataOnce(remoteId: String, maxSize: Int = 1024, timeoutMs: Long = 5000): ByteArray? {
        require(maxSize > 0)
        val connection = connections[remoteId]
        val remainder = connection?.remainder ?: remainderAfterEof.remove(remoteId)
        if (remainder != null) {
            val count = minOf(maxSize, remainder.size)
            val tail = if (count < remainder.size) remainder.copyOfRange(count, remainder.size) else null
            if (connection != null) connection.remainder = tail
            else if (tail != null) remainderAfterEof[remoteId] = tail
            return remainder.copyOf(count)
        }
        if (connection == null) return null
        val pending = PendingRead(maxSize)
        connection.pendingReads.addLast(pending)
        val timeout = scope.launch {
            delay(timeoutMs)
            if (connection.pendingReads.remove(pending)) pending.result.complete(null)
        }
        return try {
            pending.result.await()
        } finally {
            timeout.cancel()
            connection.pendingReads.remove(pending)
        }
    }

    fun disconnect(remoteId: String) {
        connecting.remove(remoteId)?.cancel()
        remainderAfterEof.remove(remoteId)
        val connection = connections.remove(remoteId) ?: return
        closeConnection(connection)
        sendConnectionState(remoteId, "disconnected")
    }

    fun isConnected(remoteId: String): Boolean = connections.containsKey(remoteId)

    private fun closeConnection(connection: Connection) {
        closeQuietly(connection.socket)
        connection.reader.cancel()
        connection.pendingReads.forEach { it.result.complete(null) }
        connection.pendingReads.clear()
        connection.remainder = null
    }

    fun startServer(uuidString: String?, name: String = "FlutterBluetooth", onResult: (Boolean, String?) -> Unit) {
        if (server != null || serverStarting != null) {
            onResult(false, "Server already running")
            return
        }
        val uuid = try {
            UUID.fromString(uuidString ?: SPP_UUID)
        } catch (_: IllegalArgumentException) {
            onResult(false, "Invalid UUID format: $uuidString")
            return
        }
        serverStarting = scope.launch {
            var opening: BluetoothServerSocket? = null
            try {
                withContext(Dispatchers.IO) {
                    opening = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(name, uuid)
                }
                val listener = checkNotNull(opening) { "Adapter unavailable" }
                server = listener
                opening = null
                acceptJob = scope.launch { acceptLoop(listener) }
                sendEvent(mapOf("type" to "rfcommServerStateChanged", "state" to "started", "uuid" to uuid.toString()))
                onResult(true, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(false, "Failed to listen: ${e.message}")
            } finally {
                closeQuietly(opening)
                if (serverStarting === coroutineContext.job) serverStarting = null
            }
        }.also { job ->
            job.invokeOnCompletion { error ->
                if (error is CancellationException) onResult(false, "Server start canceled")
            }
        }
    }

    fun stopServer() {
        serverStarting?.cancel()
        serverStarting = null
        val listener = server ?: return
        server = null
        closeQuietly(listener)
        acceptJob?.cancel()
        acceptJob = null
        sendEvent(mapOf("type" to "rfcommServerStateChanged", "state" to "stopped"))
    }

    fun isServerRunning(): Boolean = server != null

    private suspend fun acceptLoop(listener: BluetoothServerSocket) {
        try {
            var consecutiveErrors = 0
            while (currentCoroutineContext().isActive) {
                var accepted: BluetoothSocket? = null
                try {
                    withContext(Dispatchers.IO) { accepted = listener.accept() }
                    val socket = checkNotNull(accepted)
                    val address = socket.remoteDevice.address
                    connecting.remove(address)?.cancel()
                    installConnection(address, socket)
                    accepted = null
                    consecutiveErrors = 0
                    sendConnectionState(address, "connected", fromServer = true)
                } catch (_: IOException) {
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (++consecutiveErrors > ACCEPT_MAX_RETRIES) {
                        android.util.Log.e("RfcommManager", "acceptLoop exceeded max retries", e)
                        break
                    }
                    delay(200L * consecutiveErrors)
                } finally {
                    closeQuietly(accepted)
                }
            }
        } finally {
            if (server === listener) stopServer()
        }
    }

    private suspend fun readLoop(remoteId: String, connection: Connection) {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        try {
            while (currentCoroutineContext().isActive) {
                val bytesRead = withContext(Dispatchers.IO) { connection.input.read(buffer) }
                if (bytesRead < 0) break
                if (bytesRead == 0) continue
                val data = buffer.copyOf(bytesRead)
                if (connection.pendingReads.isEmpty()) {
                    sendEvent(mapOf("type" to "rfcommDataReceived", "remoteId" to remoteId, "data" to data))
                } else {
                    dispatchReadData(connection, data)
                }
            }
        } catch (_: IOException) {
            // Closing the socket interrupts the read.
        } finally {
            if (connections[remoteId] === connection) {
                connections.remove(remoteId)
                // A peer can close immediately after sending the response.
                connection.remainder?.let { remainderAfterEof[remoteId] = it }
                closeConnection(connection)
                sendConnectionState(remoteId, "disconnected")
            }
        }
    }

    private fun dispatchReadData(connection: Connection, data: ByteArray) {
        var offset = 0
        while (offset < data.size && connection.pendingReads.isNotEmpty()) {
            val pending = connection.pendingReads.removeFirst()
            val end = offset + minOf(pending.maxSize, data.size - offset)
            pending.result.complete(data.copyOfRange(offset, end))
            offset = end
        }
        if (offset < data.size) connection.remainder = data.copyOfRange(offset, data.size)
    }

    private fun sendConnectionState(remoteId: String, state: String, fromServer: Boolean = false) {
        sendEvent(mapOf(
            "type" to "rfcommConnectionStateChanged",
            "remoteId" to remoteId,
            "state" to state,
            "fromServer" to fromServer
        ))
    }

    private fun closeQuietly(socket: java.io.Closeable?) {
        try { socket?.close() } catch (_: Exception) {}
    }

    fun dispose() {
        stopServer()
        (connections.keys + connecting.keys).forEach(::disconnect)
        remainderAfterEof.clear()
        scope.cancel()
    }

    companion object {
        const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val FALLBACK_ATTEMPT_MS = 5_000L
        private const val CHUNK_SIZE = 512
        private const val READ_BUFFER_SIZE = 4096
        private const val ACCEPT_MAX_RETRIES = 10
    }
}
