package com.abuzahra.admin.api

import android.content.Context
import android.util.Log
import com.abuzahra.admin.Config
import com.abuzahra.admin.model.Command
import com.abuzahra.admin.model.LinkResult
import com.abuzahra.admin.util.DeviceUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private const val TAG = "ApiClient"

    // ===== LINK DEVICE =====
    suspend fun linkDevice(context: Context, code: String): LinkResult = withContext(Dispatchers.IO) {
        try {
            val deviceId = DeviceUtils.getDeviceId(context)
            val deviceToken = DeviceUtils.getDeviceToken(context)
            val deviceInfo = Config.getDeviceInfo(context)

            val body = mapOf(
                "device_id" to deviceId,
                "link_code" to code,
                "device_token" to deviceToken,
                "device_name" to (deviceInfo["device_name"] ?: deviceId),
                "device_model" to (deviceInfo["device_model"] ?: ""),
                "brand" to (deviceInfo["brand"] ?: ""),
                "os_version" to (deviceInfo["os_version"] ?: "")
            )

            val response = post("/register", body)
            val result = gson.fromJson(response, LinkResult::class.java)

            if (result.ok || result.success) {
                DeviceUtils.setLinked(context, true)
                result.token?.let { token ->
                    context.getSharedPreferences("abuzahra", Context.MODE_PRIVATE)
                        .edit().putString("device_token", token).apply()
                }
                Log.i(TAG, "Device linked successfully: ${result.message}")
            } else {
                Log.w(TAG, "Link failed: ${result.error}")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "linkDevice error", e)
            LinkResult(error = e.message ?: "Network error")
        }
    }

    // ===== GET PENDING COMMANDS =====
    suspend fun getCommands(context: Context): List<Command> = withContext(Dispatchers.IO) {
        try {
            val deviceId = DeviceUtils.getDeviceId(context)
            val response = get("/commands/$deviceId")
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val map = gson.fromJson<Map<String, Any>>(response, type)
            val cmds = map["commands"]
            if (cmds is List<*>) {
                val json = gson.toJson(cmds)
                return@withContext gson.fromJson(json, Array<Command>::class.java).toList()
            }
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "getCommands error", e)
            emptyList()
        }
    }

    // ===== SUBMIT COMMAND RESULT =====
    suspend fun submitResult(cmdId: String, command: String, status: String, result: Any?) {
        withContext(Dispatchers.IO) {
            try {
                val body = mapOf(
                    "status" to status,
                    "result" to (result?.let { gson.toJson(it) } ?: "OK"),
                    "command" to command
                )
                val response = post("/command_result/$cmdId", body)
                Log.d(TAG, "Result submitted for $cmdId: $response")
            } catch (e: Exception) {
                Log.e(TAG, "submitResult error for $cmdId", e)
            }
        }
    }

    // ===== SEND DATA =====
    suspend fun sendData(context: Context, command: String, data: Any?) {
        withContext(Dispatchers.IO) {
            try {
                val deviceId = DeviceUtils.getDeviceId(context)
                val body = mapOf(
                    "device_id" to deviceId,
                    "command" to command,
                    "data" to data,
                    "timestamp" to System.currentTimeMillis()
                )
                post("/data", body)
            } catch (e: Exception) {
                Log.e(TAG, "sendData error", e)
            }
        }
    }

    // ===== HEARTBEAT =====
    suspend fun sendHeartbeat(context: Context, battery: Int, status: String = "online") {
        withContext(Dispatchers.IO) {
            try {
                val deviceId = DeviceUtils.getDeviceId(context)
                val body = mapOf(
                    "device_id" to deviceId,
                    "status" to status,
                    "battery" to battery
                )
                post("/heartbeat", body)
            } catch (e: Exception) {
                Log.e(TAG, "heartbeat error", e)
            }
        }
    }

    // ===== DEVICE SETTINGS =====
    suspend fun getSettings(context: Context): Map<String, Any?> = withContext(Dispatchers.IO) {
        try {
            val deviceId = DeviceUtils.getDeviceId(context)
            val response = get("/settings/$deviceId")
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val map = gson.fromJson<Map<String, Any>>(response, type)
            map.getOrDefault("settings", emptyMap<String, Any>()) as? Map<String, Any?> ?: emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "getSettings error", e)
            emptyMap()
        }
    }

    // ===== LOW-LEVEL HTTP =====
    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${Config.SERVER_DOMAIN}/api$path")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            resp.body?.string() ?: "{}"
        }
    }

    private suspend fun post(path: String, body: Any): String = withContext(Dispatchers.IO) {
        val json = gson.toJson(body)
        val requestBody = json.toRequestBody(JSON)
        val request = Request.Builder()
            .url("${Config.SERVER_DOMAIN}/api$path")
            .post(requestBody)
            .build()
        client.newCall(request).execute().use { resp ->
            resp.body?.string() ?: "{}"
        }
    }

    fun postSync(path: String, body: Any): String {
        return try {
            val json = gson.toJson(body)
            val requestBody = json.toRequestBody(JSON)
            val request = Request.Builder()
                .url("${Config.SERVER_DOMAIN}/api$path")
                .post(requestBody)
                .build()
            client.newCall(request).execute().use { resp ->
                resp.body?.string() ?: "{}"
            }
        } catch (e: IOException) {
            Log.e(TAG, "postSync error", e)
            "{}"
        }
    }
}
