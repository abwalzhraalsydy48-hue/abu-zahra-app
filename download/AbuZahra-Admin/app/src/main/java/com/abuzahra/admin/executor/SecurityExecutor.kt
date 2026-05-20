package com.abuzahra.admin.executor

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File

object SecurityExecutor {

    private const val TAG = "SecurityExecutor"

    // ===== WIPE DATA =====
    fun wipeData(context: Context): String {
        return try {
            val pm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            pm.wipeData(0)
            "Wiping data..."
        } catch (e: Exception) {
            "Error: ${e.message} - Requires Device Admin permission"
        }
    }

    // ===== FACTORY RESET =====
    fun factoryReset(context: Context): String {
        return wipeData(context)
    }

    // ===== SHOW / HIDE APP ICON =====
    fun showApp(context: Context): String {
        return try {
            val pm = context.packageManager
            val componentName = android.content.ComponentName(context, "com.abuzahra.admin.MainActivity")
            pm.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            "App icon shown"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun hideApp(context: Context): String {
        return try {
            val pm = context.packageManager
            val componentName = android.content.ComponentName(context, "com.abuzahra.admin.MainActivity")
            pm.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            "App icon hidden"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // ===== CHANGE PASSCODE =====
    fun changePasscode(context: Context, params: Map<String, Any>): String {
        return "Passcode change requires Device Admin API (setPassword)"
    }

    // ===== ENABLE / DISABLE BIOMETRIC =====
    fun enableBiometric(context: Context): String {
        return "Biometric enrollment requires Security settings"
    }

    fun disableBiometric(context: Context): String {
        return "Biometric removal requires Security settings"
    }

    // ===== ANTI UNINSTALL =====
    fun antiUninstallOn(context: Context): String {
        return "Device Admin must be activated by user for anti-uninstall protection"
    }

    fun antiUninstallOff(context: Context): String {
        return antiUninstallOn(context)
    }

    // ===== DEVICE ADMIN STATUS =====
    fun deviceAdminStatus(context: Context): Map<String, Any> {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val isAdmin = dpm.isAdminActive(android.content.ComponentName(context, "com.abuzahra.admin.service.DeviceAdminReceiver"))
            mapOf(
                "is_device_admin" to isAdmin,
                "active_admins" to dpm.activeAdmins.size,
                "device_owner" to (dpm.deviceOwner == context.packageName)
            )
        } catch (e: Exception) {
            mapOf("error" to e.message ?: "")
        }
    }

    // ===== CHECK ROOT =====
    fun checkRoot(): Map<String, Any> {
        val rootPaths = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/bin/failsafe/su", "/data/local/xbin/su",
            "/data/local/bin/su", "/system/sd/xbin/su"
        )
        var hasRoot = false
        for (path in rootPaths) {
            if (File(path).exists()) { hasRoot = true; break }
        }
        return mapOf("rooted" to hasRoot, "message" to if (hasRoot) "Device is rooted" else "Device is not rooted")
    }

    // ===== SET SCREEN LOCK =====
    fun setScreenLock(context: Context): String {
        return try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Opening security settings"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun removeScreenLock(context: Context): String {
        return setScreenLock(context)
    }
}
