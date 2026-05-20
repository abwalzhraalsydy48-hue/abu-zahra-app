package com.abuzahra.manager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.abuzahra.manager.api.ApiClient
import com.abuzahra.manager.service.CommandService
import com.abuzahra.manager.util.DeviceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LinkActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LinkActivity"
        private const val PERM_REQUEST = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If already linked, go to main
        if (DeviceUtils.isLinked(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_link)

        val editCode = findViewById<EditText>(R.id.editCode)
        val editServer = findViewById<EditText>(R.id.editServer)
        val btnLink = findViewById<Button>(R.id.btnLink)
        val textStatus = findViewById<TextView>(R.id.textStatus)
        val textDeviceId = findViewById<TextView>(R.id.textDeviceId)

        // Show device ID
        textDeviceId.text = "Device ID: ${DeviceUtils.getDeviceId(this)}"

        // Show current server
        editServer.setHint("Server URL (optional)")
        editServer.setText(Config.SERVER_DOMAIN)

        // Request basic permissions first
        requestBasicPermissions()

        btnLink.setOnClickListener {
            val code = editCode.text.toString().trim()
            val server = editServer.text.toString().trim()

            if (code.isBlank()) {
                Toast.makeText(this, "Enter link code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Update server if provided
            if (server.isNotBlank() && server != Config.SERVER_DOMAIN) {
                Config.SERVER_DOMAIN = server
                DeviceUtils.saveServerInfo(this, server, 8443)
            }

            btnLink.isEnabled = false
            textStatus.text = "Connecting to server..."
            editCode.setText(code.uppercase())

            lifecycleScope.launch {
                try {
                    // First test server connectivity
                    val canConnect = testServerConnection()
                    if (!canConnect) {
                        textStatus.text = "Cannot connect to server!\nCheck server URL: ${Config.SERVER_DOMAIN}\nMake sure the server is running."
                        btnLink.isEnabled = true
                        return@launch
                    }

                    textStatus.text = "Server OK, linking device..."

                    val result = ApiClient.linkDevice(this@LinkActivity, code.uppercase())
                    if (result.ok || result.success) {
                        textStatus.text = "Linked successfully!\n${result.message}"
                        Toast.makeText(this@LinkActivity, "Device linked!", Toast.LENGTH_SHORT).show()

                        // Start foreground service
                        CommandService.start(this@LinkActivity)

                        // Navigate to main activity after delay
                        kotlinx.coroutines.delay(1500)
                        startActivity(Intent(this@LinkActivity, MainActivity::class.java))
                        finish()
                    } else {
                        textStatus.text = "Failed: ${result.error}"
                        btnLink.isEnabled = true
                        Toast.makeText(this@LinkActivity, result.error, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Link error", e)
                    val errorMsg = e.message ?: "Unknown error"
                    if (errorMsg.contains("BEGIN_OBJECT") || errorMsg.contains("NUMBER")) {
                        textStatus.text = "Server returned invalid response!\nMake sure server URL is correct.\nCurrent: ${Config.SERVER_DOMAIN}"
                    } else if (errorMsg.contains("Connection refused") || errorMsg.contains("Failed to connect")) {
                        textStatus.text = "Cannot connect to server!\n${Config.SERVER_DOMAIN}\nIs the server running?"
                    } else if (errorMsg.contains("SSL") || errorMsg.contains("certificate")) {
                        textStatus.text = "SSL Error! Try using http:// instead of https://"
                    } else {
                        textStatus.text = "Error: $errorMsg"
                    }
                    btnLink.isEnabled = true
                    Toast.makeText(this@LinkActivity, "Connection failed: ${errorMsg.take(100)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun testServerConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "${Config.SERVER_DOMAIN}/api/health"
            val request = okhttp3.Request.Builder().url(url).get().build()
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            client.newCall(request).execute().use { resp ->
                // Any response means server is alive
                resp.isSuccessful || resp.code == 404
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server connection test failed", e)
            false
        }
    }

    private fun requestBasicPermissions() {
        val perms = mutableListOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val toRequest = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), PERM_REQUEST)
        }
    }
}
