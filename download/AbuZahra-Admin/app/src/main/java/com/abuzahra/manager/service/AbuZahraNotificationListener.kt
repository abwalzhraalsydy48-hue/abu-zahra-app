package com.abuzahra.manager.service

import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.abuzahra.manager.api.ApiClient
import com.abuzahra.manager.executor.MonitorExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AbuZahraNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "AbuZahraNotif"
        var instance: AbuZahraNotificationListener? = null
            private set

        // Store recent notifications (max 500)
        private val notificationHistory = mutableListOf<Map<String, Any>>>()
        private const val MAX_HISTORY = 500

        fun isServiceActive(): Boolean = instance != null

        fun getRecentNotifications(): List<Map<String, Any>> {
            return notificationHistory.toList()
        }

        fun clearHistory() {
            notificationHistory.clear()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Notification Listener connected")

        // Load existing notifications
        try {
            val active = activeNotifications
            if (active != null) {
                for (sbn in active) {
                    addNotificationToHistory(sbn)
                }
                Log.i(TAG, "Loaded ${active.size} existing notifications")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading existing notifications", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            addNotificationToHistory(sbn)

            // Forward notification if monitoring is active
            val scope = CoroutineScope(Dispatchers.IO)
            scope.launch {
                try {
                    val notifData = extractNotificationData(sbn)
                    ApiClient.sendData(this@AbuZahraNotificationListener, "notification", notifData)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to forward notification", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Notification removed - can track if needed
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.w(TAG, "Notification Listener disconnected")

        // Request reconnection on Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                requestRebind(
                    android.content.ComponentName(this, AbuZahraNotificationListener::class.java)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request rebind", e)
            }
        }
    }

    private fun addNotificationToHistory(sbn: StatusBarNotification) {
        try {
            val data = extractNotificationData(sbn)
            synchronized(notificationHistory) {
                notificationHistory.add(0, data)
                if (notificationHistory.size > MAX_HISTORY) {
                    notificationHistory.removeAt(notificationHistory.size - 1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding notification to history", e)
        }
    }

    private fun extractNotificationData(sbn: StatusBarNotification): Map<String, Any> {
        val extras = sbn.notification?.extras
        val title = extras?.getCharSequence("android.title")?.toString() ?: ""
        val text = extras?.getCharSequence("android.text")?.toString() ?: ""
        val bigText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            extras?.getCharSequence("android.bigText")?.toString() ?: text
        } else text

        val packageName = sbn.packageName ?: ""
        val key = sbn.key ?: ""
        val id = sbn.id
        val tag = sbn.tag ?: ""
        val postTime = sbn.postTime
        val isClearable = sbn.isClearable
        val isOngoing = sbn.isOngoing
        val category = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            sbn.notification?.category ?: ""
        } else ""

        // Try to extract messages from messaging notifications
        val messages = mutableListOf<Map<String, String>>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val messagesArray = extras?.getParcelableArray("android.messages")
            if (messagesArray != null) {
                for (msg in messagesArray) {
                    val bundle = msg as? android.os.Bundle ?: continue
                    val sender = bundle.getString("sender", "")
                    val msgText = bundle.getString("text", "")
                    messages.add(mapOf("sender" to sender, "text" to msgText))
                }
            }
        }

        return mapOf(
            "package" to packageName,
            "title" to title,
            "text" to text,
            "big_text" to bigText,
            "key" to key,
            "id" to id,
            "tag" to tag,
            "post_time" to postTime,
            "is_clearable" to isClearable,
            "is_ongoing" to isOngoing,
            "category" to category,
            "messages" to messages
        )
    }
}
