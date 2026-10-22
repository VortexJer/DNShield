package dev.dnshield.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import dev.dnshield.app.engine.Engine
import dev.dnshield.app.engine.UserRule
import dev.dnshield.app.vpn.ShieldVpnService

/**
 * Control por adb para los tests del emulador (solo existe en la build debug):
 *   adb shell am broadcast -a dev.dnshield.app.debug.CMD -n dev.dnshield.app/.debug.DebugReceiver --es cmd start
 * cmd: start | stop | allow <d> | block <d> | clear | server <id> | proto <id> <plain|doh|dot> | status
 * La respuesta se escribe en logcat con la etiqueta DNShieldTest.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Engine.init(ctx)
        val s = Engine.settings
        val cmd = intent.getStringExtra("cmd") ?: return
        val arg = intent.getStringExtra("arg") ?: ""
        val arg2 = intent.getStringExtra("arg2") ?: ""
        val out = when (cmd) {
            "start" -> if (VpnService.prepare(ctx) == null) { ShieldVpnService.start(ctx); "starting" } else "no-consent"
            "stop" -> { ShieldVpnService.stop(ctx); "stopping" }
            "allow" -> { s.allowlist = s.allowlist + UserRule(arg); Engine.reloadConfig(); "allowed $arg" }
            "block" -> { s.userRules = s.userRules + UserRule(arg); Engine.reloadConfig(); "blocked $arg" }
            "clear" -> { s.allowlist = emptyList(); s.userRules = emptyList(); Engine.reloadConfig(); "cleared" }
            "server" -> { s.serverId = arg; Engine.rebuildUpstream(); "server ${Engine.currentServer().first}" }
            "proto" -> { s.setProtocol(arg, arg2); Engine.rebuildUpstream(); "server ${Engine.currentServer().first}" }
            "status" -> "running=${Engine.running.value} rules=${Engine.blocklist.ruleCount} server=${Engine.currentServer().first} sysdns=${Engine.systemDns.map { it.hostAddress }} excluded=${Engine.networkExcluded}"
            "log" -> Engine.recent().take(arg.toIntOrNull() ?: 15).joinToString(" | ") { "${it.domain} ${it.result} ${it.answer} ${it.elapsedMs}ms ${it.via}" }
            else -> "unknown $cmd"
        }
        Log.i("DNShieldTest", "RESULT $cmd: $out")
    }
}
