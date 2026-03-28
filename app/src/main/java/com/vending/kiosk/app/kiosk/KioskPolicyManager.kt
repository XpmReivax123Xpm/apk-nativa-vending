package com.vending.kiosk.app.kiosk

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build

/**
 * Helper para centralizar interacciones con DevicePolicyManager.
 * No se conecta aun al flujo de pantallas en esta fase.
 */
class KioskPolicyManager(private val context: Context) {

    fun getDevicePolicyManager(): DevicePolicyManager? {
        return context.getSystemService(DevicePolicyManager::class.java)
    }

    fun getAdminComponentName(): ComponentName {
        return ComponentName(context, KioskDeviceAdminReceiver::class.java)
    }

    fun isDeviceOwner(): Boolean {
        val dpm = getDevicePolicyManager() ?: return false
        return runCatching { dpm.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)
    }

    fun isCurrentPackageLockTaskPermitted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        val dpm = getDevicePolicyManager() ?: return false
        return runCatching { dpm.isLockTaskPermitted(context.packageName) }.getOrDefault(false)
    }

    /**
     * Intenta autorizar el package actual para lock task mode.
     * Requiere Device Owner (o perfil con permisos equivalentes).
     */
    fun allowCurrentPackageForLockTask(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        if (!isDeviceOwner()) return false
        val dpm = getDevicePolicyManager() ?: return false
        val admin = getAdminComponentName()

        return runCatching {
            dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
            true
        }.getOrDefault(false)
    }
}
