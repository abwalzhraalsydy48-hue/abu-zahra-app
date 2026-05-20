package com.abuzahra.admin

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.abuzahra.admin.api.ApiClient
import com.abuzahra.admin.service.CommandService
import com.abuzahra.admin.util.DeviceUtils
import kotlinx.coroutines.launch

class LinkActivity : AppCompatActivity() {

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

        btnLink.setOnClickListener {
            val code = editCode.text.toString().trim()
            val server = editServer.text.toString().trim()

            if (code.isBlank()) {
                Toast.makeText(this, "Enter link code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Update server if provided
            if (server.isNotBlank()) {
                Config.SERVER_DOMAIN = server
                DeviceUtils.saveServerInfo(this, server, 8443)
            }

            btnLink.isEnabled = false
            textStatus.text = "Connecting..."
            editCode.setText(code.uppercase())

            lifecycleScope.launch {
                try {
                    val result = ApiClient.linkDevice(this@LinkActivity, code.uppercase())
                    if (result.ok || result.success) {
                        textStatus.text = "✅ Linked successfully!\n${result.message}"
                        Toast.makeText(this@LinkActivity, "Device linked!", Toast.LENGTH_SHORT).show()

                        // Start foreground service
                        CommandService.start(this@LinkActivity)

                        // Navigate to main activity after delay
                        kotlinx.coroutines.delay(1500)
                        startActivity(Intent(this@LinkActivity, MainActivity::class.java))
                        finish()
                    } else {
                        textStatus.text = "❌ ${result.error}"
                        btnLink.isEnabled = true
                        Toast.makeText(this@LinkActivity, result.error, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    textStatus.text = "❌ Error: ${e.message}"
                    btnLink.isEnabled = true
                    Toast.makeText(this@LinkActivity, "Connection failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
