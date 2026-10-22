package dev.dnshield.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import dev.dnshield.app.engine.Settings

/** Si estaba activo al apagar el movil, vuelve a activarse al encenderlo (o tras actualizar la app). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Settings(context).enabled) return
        if (VpnService.prepare(context) != null) return
        runCatching { ShieldVpnService.start(context) }
    }
}
