package io.leancast.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the receiver on boot only when the user enabled autostart. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Settings(context).autostart) {
            ReceiverService.start(context)
        }
    }
}
