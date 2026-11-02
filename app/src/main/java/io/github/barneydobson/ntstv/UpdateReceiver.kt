package io.github.barneydobson.ntstv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat

/**
 * Receives the result of an update install from Android's package installer.
 *
 * Not exported: it is only reachable through the PendingIntent that [Updater] gives to the
 * system installer, so the confirmation intent it carries comes from the system.
 */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // First update: Android asks the user to allow it (and to confirm).
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    Updater.onInstallFailed()
                    return
                }
                Log.i(Updater.TAG, "Asking for permission to install the update")
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Log.w(Updater.TAG, "Couldn't show the install prompt: ${e.message}")
                    Updater.onInstallFailed()
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i(Updater.TAG, "Update installed")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(Updater.TAG, "Update not installed (status $status): $message")
                Updater.onInstallFailed()
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "io.github.barneydobson.ntstv.INSTALL_STATUS"
    }
}
