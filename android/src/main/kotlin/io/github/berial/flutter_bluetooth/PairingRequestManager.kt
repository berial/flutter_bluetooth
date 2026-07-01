package io.github.berial.flutter_bluetooth

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * 管理蓝牙配对请求处理。
 *
 * 支持的配对变体（参照 flutter_bluetooth_serial）：
 * - [BluetoothDevice.PAIRING_VARIANT_PIN] — 4 位数字 PIN
 * - [BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION] — Passkey 确认（用户比对数字是否一致）
 * - 变体 3 (CONSENT) — 简单是/否确认，复用 Passkey 确认逻辑
 * - 变体 4 (DISPLAY_PASSKEY) / 5 (DISPLAY_PIN) — 显示密钥，仅通知 Dart 端
 *
 * 工作机制：
 * 1. Dart 端调用 `enablePairingRequestHandling` 注册接收器
 * 2. 系统发起配对时触发 [ACTION_PAIRING_REQUEST] 广播
 * 3. 本接收器将请求转化为事件推送到 Dart 端
 * 4. Dart 端通过 `respondPairingRequest` 方法回传 PIN 或确认结果
 * 5. 本管理器通过 pendingRequests 等待响应，调用 `device.setPin` 或 `device.setPairingConfirmation`
 *
 * 若 Dart 端未启用处理，配对请求由系统默认 UI 处理。
 */
class PairingRequestManager(
    private val context: Context,
    private val sendEvent: (Map<String, Any?>) -> Unit
) {
    /** 等待 Dart 端响应的配对请求：address -> PendingRequest */
    private data class PendingRequest(
        val device: BluetoothDevice,
        val variant: Int,
        val pairingKey: Int,
        val timestamp: Long
    )

    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()
    private var receiverRegistered = false
    private val handler = Handler(Looper.getMainLooper())

    /** 配对请求超时（毫秒）。超时后从 pending 移除，交还系统处理。 */
    private val requestTimeoutMs = 60_000L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return
            val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            } ?: return

            val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)
            val pairingKey = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, BluetoothDevice.ERROR)
            val address = device.address ?: return

            // 仅需响应的变体入队 pendingRequests；DISPLAY 类变体仅通知，不等待响应。
            if (needsResponse(variant)) {
                pendingRequests[address] = PendingRequest(device, variant, pairingKey, System.currentTimeMillis())
                // 超时清理
                handler.postDelayed({ pendingRequests.remove(address) }, requestTimeoutMs)
            }

            // 拦截系统配对 UI，避免与 Dart 端响应冲突
            if (isOrderedBroadcast) {
                try { abortBroadcast() } catch (_: Exception) {}
            }

            // 推送事件到 Dart 端
            val variantStr = variantToString(variant)
            sendEvent(mapOf(
                "type" to "pairingRequest",
                "remoteId" to address,
                "variant" to variantStr,
                "variantCode" to variant,
                "pairingKey" to pairingKey
            ))
        }
    }

    /** 判断变体是否需要 Dart 端响应。DISPLAY 类变体仅通知，无需响应。 */
    private fun needsResponse(variant: Int): Boolean =
        variant == BluetoothDevice.PAIRING_VARIANT_PIN ||
        variant == BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION ||
        variant == 3 // CONSENT

    /** 启用配对请求处理。注册 [ACTION_PAIRING_REQUEST] 广播接收器。 */
    fun enable(): Boolean {
        if (receiverRegistered) return true
        return try {
            val filter = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST)
            context.registerReceiver(receiver, filter)
            receiverRegistered = true
            android.util.Log.d("PairingRequestMgr", "Pairing request receiver registered")
            true
        } catch (e: Exception) {
            android.util.Log.e("PairingRequestMgr", "Failed to register receiver: ${e.message}")
            false
        }
    }

    /** 禁用配对请求处理。注销接收器并清空 pending 请求。 */
    fun disable() {
        if (!receiverRegistered) return
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        receiverRegistered = false
        // 取消所有超时回调，避免已 clear 的 pending 被无效 remove
        handler.removeCallbacksAndMessages(null)
        pendingRequests.clear()
        android.util.Log.d("PairingRequestMgr", "Pairing request receiver unregistered")
    }

    /**
     * 响应配对请求 — 设置 PIN。
     *
     * 适用于 [PAIRING_VARIANT_PIN] 变体。
     * 调用 [BluetoothDevice.setPin] 并终止广播。
     *
     * @return 是否成功设置 PIN
     */
    fun respondPin(address: String, pin: String): Boolean {
        val request = pendingRequests.remove(address) ?: return false
        val device = request.device
        return try {
            val success = device.setPin(pin.toByteArray())
            if (success) {
                android.util.Log.d("PairingRequestMgr", "PIN set for $address")
            }
            success
        } catch (e: SecurityException) {
            android.util.Log.e("PairingRequestMgr", "setPin failed: ${e.message}")
            false
        }
    }

    /**
     * 响应配对请求 — 确认或拒绝 Passkey/Consent。
     *
     * 适用于 [PAIRING_VARIANT_PASSKEY_CONFIRMATION] 和变体 3 (CONSENT)。
     * 调用 [BluetoothDevice.setPairingConfirmation]。
     *
     * @param confirm true 表示确认配对，false 表示拒绝
     * @return 是否成功设置确认
     */
    fun respondConfirmation(address: String, confirm: Boolean): Boolean {
        val request = pendingRequests.remove(address) ?: return false
        val device = request.device
        return try {
            val success = device.setPairingConfirmation(confirm)
            if (success) {
                android.util.Log.d("PairingRequestMgr", "Pairing confirmation $confirm for $address")
            }
            success
        } catch (e: SecurityException) {
            android.util.Log.e("PairingRequestMgr", "setPairingConfirmation failed: ${e.message}")
            false
        }
    }

    /** 释放资源。 */
    fun dispose() {
        disable()
    }

    /** 将配对变体码转为可读字符串。 */
    private fun variantToString(variant: Int): String = when (variant) {
        BluetoothDevice.PAIRING_VARIANT_PIN -> "pin"
        BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION -> "passkeyConfirmation"
        3 -> "consent"
        4 -> "displayPasskey"
        5 -> "displayPin"
        else -> "unknown($variant)"
    }
}
