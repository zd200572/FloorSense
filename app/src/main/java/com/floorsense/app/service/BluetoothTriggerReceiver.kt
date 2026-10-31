package com.floorsense.app.service

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * 车载蓝牙断开广播监听（规格书 §5）。
 *
 * ACL_DISCONNECTED 属于隐式广播豁免清单，可在 Manifest 中静态注册。
 * 收到广播即认为“用户停好车下车”，拉起前台采集服务执行黄金 10 分钟记录。
 */
class BluetoothTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return

        // Android 12+ 读取设备信息需要 BLUETOOTH_CONNECT；拿不到设备名不影响启动采集
        var deviceName: String? = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED
        ) {
            val device = IntentCompat.getParcelableExtra(
                intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
            )
            deviceName = try {
                device?.name
            } catch (security: SecurityException) {
                null
            }
        }

        Log.i(TAG, "车载蓝牙已断开（${deviceName ?: "未知设备"}），开始记录停车位置")
        ParkingTrackerService.start(context)
    }

    private companion object {
        const val TAG = "BluetoothTrigger"
    }
}
