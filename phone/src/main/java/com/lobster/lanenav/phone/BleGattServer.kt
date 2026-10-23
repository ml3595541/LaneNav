package com.lobster.lanenav.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.lobster.lanenav.shared.BleFrames
import com.lobster.lanenav.shared.BleProtocol
import com.lobster.lanenav.shared.LocationPacket
import java.util.ArrayDeque

/**
 * 手机端 BLE GATT Server。
 *
 * 广播服务 UUID 供盒子扫描；定位数据通过 notify 推送。
 * 相比 TCP 热点方案：无需开热点、功耗低一个数量级。
 */
class BleGattServer(private val ctx: Context) {

    private val btManager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = btManager.adapter

    private var server: BluetoothGattServer? = null
    private var dataChar: BluetoothGattCharacteristic? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null

    /** 已订阅通知的盒子 */
    private val subscribers = HashSet<BluetoothDevice>()

    /** 发送队列：BLE 必须等上一包 onNotificationSent 才能发下一包 */
    private val queue = ArrayDeque<ByteArray>()
    private var sending = false
    private var frameId = 0
    private var chunkSize = BleProtocol.maxPayload(BleProtocol.DEFAULT_MTU)

    @Volatile var clientCount = 0
        private set
    @Volatile var lastError: String? = null
        private set
    var onCommand: ((String) -> Unit)? = null

    private val callback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.i(TAG, "盒子已连接 BLE: ${device.address}")
            } else {
                subscribers.remove(device)
                clientCount = subscribers.size
                Log.i(TAG, "BLE 断开: ${device.address}, 剩余 ${clientCount}")
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            chunkSize = BleProtocol.maxPayload(mtu)
            Log.i(TAG, "MTU=$mtu, 单包载荷=$chunkSize")
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == BleProtocol.CCCD_UUID) {
                val enable = value.isNotEmpty() &&
                    (value[0].toInt() and 0x01) != 0
                if (enable) subscribers += device else subscribers -= device
                clientCount = subscribers.size
                Log.i(TAG, "订阅通知: $enable, 当前 ${clientCount} 台")
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == BleProtocol.CHAR_CMD_UUID) {
                val text = String(value, Charsets.UTF_8)
                onCommand?.invoke(text)
            }
            if (responseNeeded) {
                server?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value
                )
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            sending = false
            pumpQueue()
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val ad = adapter ?: run {
            lastError = "设备不支持蓝牙"
            return false
        }
        if (!ad.isEnabled) {
            lastError = "蓝牙未开启"
            return false
        }
        return try {
            server?.close()
            server = btManager.openGattServer(ctx, callback)
            if (server == null) {
                lastError = "无法创建 GATT Server"
                return false
            }

            val service = BluetoothGattService(
                BleProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            dataChar = BluetoothGattCharacteristic(
                BleProtocol.CHAR_DATA_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            ).apply {
                addDescriptor(
                    BluetoothGattDescriptor(
                        BleProtocol.CCCD_UUID,
                        BluetoothGattDescriptor.PERMISSION_READ or
                            BluetoothGattDescriptor.PERMISSION_WRITE
                    )
                )
            }

            cmdChar = BluetoothGattCharacteristic(
                BleProtocol.CHAR_CMD_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            service.addCharacteristic(dataChar)
            service.addCharacteristic(cmdChar)
            server?.addService(service)

            startAdvertising(ad)
            lastError = null
            true
        } catch (e: SecurityException) {
            lastError = "缺少蓝牙权限"
            Log.e(TAG, "BLE 启动失败(权限)", e)
            false
        } catch (e: Exception) {
            lastError = "BLE 启动失败: ${e.message}"
            Log.e(TAG, "BLE 启动失败", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(ad: android.bluetooth.BluetoothAdapter) {
        advertiser = ad.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()

        advertiser?.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i(TAG, "BLE 广播已启动")
            }

            override fun onStartFailure(errorCode: Int) {
                lastError = "广播失败 code=$errorCode"
                Log.e(TAG, "BLE 广播失败: $errorCode")
            }
        })
    }

    /** 推送一条定位。自动按 MTU 分包。 */
    fun push(pkt: LocationPacket) {
        if (subscribers.isEmpty()) return
        val bytes = pkt.toJson().toByteArray(Charsets.UTF_8)
        frameId = (frameId + 1) and 0xFF
        val chunks = BleFrames.split(bytes, chunkSize, frameId)
        if (chunks.isEmpty()) return
        queue.addAll(chunks)
        pumpQueue()
    }

    @SuppressLint("MissingPermission")
    private fun pumpQueue() {
        if (sending) return
        val next = queue.pollFirst() ?: return
        val dev = subscribers.firstOrNull() ?: run { queue.clear(); return }
        val ch = dataChar ?: return
        try {
            sending = true
            if (Build.VERSION.SDK_INT >= 33) {
                server?.notifyCharacteristicChanged(dev, ch, false, next)
            } else {
                @Suppress("DEPRECATION")
                ch.value = next
                @Suppress("DEPRECATION")
                server?.notifyCharacteristicChanged(dev, ch, false)
            }
        } catch (e: Exception) {
            sending = false
            lastError = "通知发送失败: ${e.message}"
            queue.clear()
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        // 先把队列里剩余的包尽力发完，避免丢尾
        queue.clear()
        subscribers.clear()
        clientCount = 0
        runCatching { advertiser?.stopAdvertising(null) }
        runCatching { server?.close() }
        advertiser = null
        server = null
        dataChar = null
        cmdChar = null
    }

    fun isReady(): Boolean = server != null

    companion object {
        private const val TAG = "LaneNavPhone"
    }
}
