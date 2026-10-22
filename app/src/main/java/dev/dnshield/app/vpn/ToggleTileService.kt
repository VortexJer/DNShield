package dev.dnshield.app.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.dnshield.app.engine.Engine
import dev.dnshield.app.ui.MainActivity

/** Boton en los ajustes rapidos para activar/desactivar sin abrir la app. */
class ToggleTileService : TileService() {

    override fun onStartListening() {
        Engine.init(this)
        val tile = qsTile ?: return
        val on = Engine.running.value
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = if (on) "Activo" else "Apagado"
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        Engine.init(this)
        when {
            Engine.running.value -> ShieldVpnService.stop(this)
            VpnService.prepare(this) == null -> ShieldVpnService.start(this)
            else -> {
                // Falta el permiso de VPN: hay que pedirlo desde la app.
                val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
                } else {
                    @Suppress("DEPRECATION") startActivityAndCollapse(intent)
                }
            }
        }
    }

    companion object {
        fun refresh(ctx: Context) {
            runCatching { requestListeningState(ctx, ComponentName(ctx, ToggleTileService::class.java)) }
        }
    }
}
