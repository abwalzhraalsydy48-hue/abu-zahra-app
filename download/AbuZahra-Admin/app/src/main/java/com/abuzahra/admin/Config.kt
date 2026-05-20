package com.abuzahra.admin

import android.content.Context
import android.os.Build

object Config {
    // Server Configuration - Update these to match your server
    var SERVER_DOMAIN = "https://alsydyabwalzhra.online"
    var SERVER_PORT = 8443
    var FIREBASE_PROJECT = "studio-7073076148-6afe0"
    var FIREBASE_RTDB_URL = "https://$FIREBASE_PROJECT-default-rtdb.firebaseio.com"

    fun getBaseUrl(): String = SERVER_DOMAIN
    fun getApiUrl(path: String): String = "$SERVER_DOMAIN/api/$path"

    // Device Info
    fun getDeviceInfo(context: Context): Map<String, String> {
        return mapOf(
            "device_name" to Build.MODEL,
            "device_model" to Build.MODEL,
            "brand" to Build.BRAND,
            "os_version" to "Android ${Build.VERSION.RELEASE}",
            "sdk" to Build.VERSION.SDK_INT.toString(),
            "manufacturer" to Build.MANUFACTURER
        )
    }
}
