package com.floorsense.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.floorsense.app.service.ParkingTrackerService

class FloorSenseApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                ParkingTrackerService.CHANNEL_TRACKING,
                getString(R.string.channel_tracking),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ParkingTrackerService.CHANNEL_RESULT,
                getString(R.string.channel_result),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }
}
