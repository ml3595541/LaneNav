package com.lobster.lanenav.box

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import android.os.ParcelUuid
import android.util.Log
import com.lobster.lanenav.shared.BleFrames
import com.lobster.lanenav.shared.BleProtocol
import com.lobster.lanenav.shared.LocationPacket

/**
 * 盒子端 BLE GATT Client。
 *
 * 按服务 UUID 扫描手机 → 连接 → 协商 MTU → 开启通知 → 持续收定位。
 * 断线自动重新扫描（由外层服务循环驱动）。
 */
class BleGattClient(private val ctx: Context) {

    private val btManager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = btManager.adapter

    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var assembler = BleFrames.Assembler()

    @Volatile var connected = false
        private set
    @Volatile var mtu = BleProtocol.DEFAULT_MTU
        private set
    @Volatile var lastError: String? = null
        private set

    /** 收到完整定位帧 */
    var onPacket: ((LocationPacket) -> Unit)? = null
    /** 连接建立（MTU 协商+通知开启完成） */
    var onReady: (() -> Unit)? = null
    /** 断开回调，外层据此重连 */
    var onDisconnected: (() -> Unit)? = null

    private var cmdChar: BluetoothGattCharacteristic? = null

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            Log.i(TAG, "发现设备 ${dev.address} ${dev.name ?: ""}")
            stopScan()
            connect(dev)
        }

        override fun onScanFailed(errorCode: Int) {
            lastError = "扫描失败 code=$errorCode"
            Log.e(TAG, "BLE 扫描失败: $errorCode")
            scanning = false
            onDisconnected?.invoke()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "BLE 已连接，请求 MTU")
                mtu = BleProtocol.DEFAULT_MTU
                g.requestMtu(BleProtocol.TARGET_MTU)
            } else {
                connected = false
                Log.i(TAG, "BLE 断开 status=$status")
                runCatching { g.close() }
                if (gatt === g) gatt = null
                onDisconnected?.invoke()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtuValue: Int, status: Int) {
            mtu = mtuValue
            Log.i(TAG, "MTU 协商结果: $mtuValue")
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(BleProtocol.SERVICE_UUID)
            if (svc == null) {
                lastError = "未找到 LaneNav 服务"
                Log.e(TAG, lastError!!)
                g.disconnect()
                return
            }
            val dataChar = svc.getCharacteristic(BleProtocol.CHAR_DATA_UUID)
            cmdChar = svc.getCharacteristic(BleProtocol.CHAR_CMD_UUID)

            if (dataChar == null) {
                lastError = "未找到数据特征"
                g.disconnect()
                return
            }

            val cccd = dataChar.getDescriptor(BleProtocol.CCCD_UUID)
            if (cccd == null) {
                lastError = "未找到 CCCD"
                g.disconnect()
                return
            }
            g.setCharacteristicNotification(dataChar, true)
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            if (descriptor.uuid == BleProtocol.CCCD_UUID) {
                connected = true
                assembler.reset()
                lastError = null
                Log.i(TAG, "通知已开启，链路就绪")
                onReady?.invoke()
            }
        }

        /**
         * Android 13+ (API 33) 的新回调，value 直接给出。
         */
        @RequiresApi(33)
        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            handleData(characteristic, value)
        }

        /**
         * Android 12 及以下的旧回调，数据在 characteristic.value 里。
         * ⚠️ 必须单独重写：只重写 3 参版本时，Android 8~12 永远收不到数据。
         */
        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            val v = characteristic.value ?: return
            handleData(characteristic, v)
        }
    }

    private fun handleData(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != BleProtocol.CHAR_DATA_UUID) return
        val full = assembler.feed(value) ?: return
        val text = String(full, Charsets.UTF_8)
        LocationPacket.fromJson(text)?.let { onPacket?.invoke(it) }
    }

    @SuppressLint("MissingPermission")
    fun startScan(): Boolean {
        val ad = adapter ?: run {
            lastError = "设备不支持蓝牙"
            return false
        }
        if (!ad.isEnabled) {
            lastError = "蓝牙未开启"
            return false
        }
        if (scanning) return true

        return try {
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            scanning = true
            ad.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
                ?: run {
                    lastError = "扫描器不可用"
                    scanning = false
                    return false
                }
            true
        } catch (e: SecurityException) {
            lastError = "缺少蓝牙权限"
            scanning = false
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    @SuppressLint("MissingPermission")
    private fun connect(dev: BluetoothDevice) {
        try {
            stopScan()
            runCatching { gatt?.close() }
            gatt = dev.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            Log.i(TAG, "正在连接 ${dev.address}")
        } catch (e: SecurityException) {
            lastError = "连接失败(权限)"
            onDisconnected?.invoke()
        } catch (e: Exception) {
            lastError = "连接失败: ${e.message}"
            onDisconnected?.invoke()
        }
    }

    /** 发送控制命令给手机（sleep/wake）。 */
    @SuppressLint("MissingPermission")
    fun sendCommand(cmd: String): Boolean {
        val g = gatt ?: return false
        val ch = cmdChar ?: return false
        return try {
            val bytes = cmd.toByteArray(Charsets.UTF_8)
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(
                    ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.value = bytes
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
        } catch (e: Exception) {
            lastError = "命令发送失败: ${e.message}"
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        stopScan()
        connected = false
        assembler.reset()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        cmdChar = null
    }

    fun isReady(): Boolean = connected

    companion object {
        private const val TAG = "LaneNavBox"
    }
}
