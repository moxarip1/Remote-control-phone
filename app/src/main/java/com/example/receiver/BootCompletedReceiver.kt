package com.example.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.server.LocalFileServer
import com.example.service.HomeServerForegroundService
import com.google.firebase.auth.FirebaseAuth

/**
 * Automatically starts and keeps [HomeServerForegroundService] running whenever the device
 * finishes booting or when the application package is updated.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedRole = prefs.getString(KEY_DEVICE_ROLE, ROLE_HOME_SERVER) ?: ROLE_HOME_SERVER
        if (savedRole == ROLE_ADMIN_ONLY) {
            return
        }

        val hasSignedInUser = try {
            FirebaseAuth.getInstance().currentUser != null
        } catch (_: Throwable) {
            false
        }
        if (!hasSignedInUser) return

        val serviceIntent = Intent(appContext, HomeServerForegroundService::class.java).apply {
            this.action = HomeServerForegroundService.ACTION_START_SERVER
            putExtra(HomeServerForegroundService.EXTRA_PORT, LocalFileServer.DEFAULT_PORT)
        }

        val hasNotifPermission = try {
            val runtimeGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            runtimeGranted && NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        } catch (_: Throwable) {
            false
        }

        try {
            appContext.startService(serviceIntent)
        } catch (_: Throwable) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasNotifPermission) {
                    appContext.startForegroundService(serviceIntent)
                }
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        const val PREFS_NAME = "smart_cloud_prefs"
        const val KEY_DEVICE_ROLE = "device_role"
        const val ROLE_HOME_SERVER = "HOME_SERVER"
        const val ROLE_ADMIN_ONLY = "ADMIN_ONLY"
    }
}
