package com.wirefinder.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.wirefinder.R
import com.wirefinder.data.APNetwork
import com.wirefinder.ui.main.MainActivity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationService @Inject constructor(
    private val context: Context
) {
    private val CHANNEL_ID = "network_alerts"
    private val NOTIFICATION_ID_BASE = 1000

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Shows a notification for a detected known network.
     */
    fun showNetworkDetectedNotification(network: APNetwork, signalStrength: Int) {
        val notificationId = stableNotificationId(network.mac)
        val contentTitle = context.getString(R.string.notification_title)
        val contentText = context.getString(
            R.string.notification_text,
            network.ssid,
            network.mac,
            signalStrength
        )

        // Intent to open app when notification is tapped
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("network_mac", network.mac)
            putExtra("network_ssid", network.ssid)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Dismiss action
        val dismissIntent = Intent(context, NotificationDismissReceiver::class.java).apply {
            putExtra("notification_id", notificationId)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 1,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_wifi)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_wifi)
                    .setContentTitle(context.getString(R.string.notification_public_title))
                    .setContentText(context.getString(R.string.notification_public_text))
                    .build()
            )
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_notification_wifi,
                context.getString(R.string.notification_action_connect),
                pendingIntent
            )
            .addAction(
                R.drawable.ic_notification_dismiss,
                context.getString(R.string.notification_action_dismiss),
                dismissPendingIntent
            )
            .build()

        NotificationManagerCompat.from(context).notify(
            notificationId,
            notification
        )
    }

    /**
     * Stable notification ID derived from the full MAC address (48 bits,
     * offset above the FGS notification ID). Folding the 48-bit value keeps
     * the differentiating NIC bytes, avoiding collisions between APs that
     * share a vendor OUI.
     */
    private fun stableNotificationId(mac: String): Int {
        val hex = mac.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.take(12)
        val full = hex.toLongOrNull(16) ?: return NOTIFICATION_ID_BASE + mac.hashCode()
        return NOTIFICATION_ID_BASE + ((full xor (full ushr 32)).toInt())
    }

    /**
     * Cancels a specific notification.
     */
    fun cancelNotification(networkMac: String) {
        NotificationManagerCompat.from(context).cancel(stableNotificationId(networkMac))
    }

    /**
     * Cancels all notifications from this app.
     */
    fun cancelAllNotifications() {
        NotificationManagerCompat.from(context).cancelAll()
    }
}