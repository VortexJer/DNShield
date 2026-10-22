package dev.dnshield.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import dev.dnshield.app.R
import dev.dnshield.app.core.DnsQuery
import dev.dnshield.app.core.Packets
import dev.dnshield.app.engine.Engine
import dev.dnshield.app.engine.ListStore
import dev.dnshield.app.ui.MainActivity
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * VPN local que solo intercepta DNS. El tunel anuncia un servidor DNS propio (10.111.222.2) y
 * solo enruta esa direccion, asi que el resto del trafico (webs, video, juegos) va directo por
 * la red real sin pasar por la app: cero coste de CPU por megabyte navegado.
 *
 * Cada consulta: si el dominio esta bloqueado se contesta al momento; si no, se reenvia al
 * servidor elegido (DNS normal, DoH o DoT) y se devuelve la respuesta tal cual.
 */
class ShieldVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var wakePipe: Array<FileDescriptor>? = null
    private var pool: ThreadPoolExecutor? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetwork: Network? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Engine.settings.enabled = false
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> { Engine.pause(5); notifyState(); return START_STICKY }
            ACTION_RESUME -> { Engine.resume(); notifyState(); return START_STICKY }
        }
        // ACTION_START, ACTION_RESTART, arranque por "VPN siempre activa" o reinicio del sistema.
        goForeground()
        Engine.settings.enabled = true
        if (intent?.action == ACTION_RESTART) {
            unwatchNetwork()
            closeTunnel()
        }
        if (tun == null) startTunnel()
        return START_STICKY
    }

    override fun onRevoke() {
        // Otra VPN (por ejemplo AdGuard VPN) ha tomado el control o el usuario la ha quitado en Ajustes.
        Engine.settings.enabled = false
        shutdown()
    }

    override fun onDestroy() {
        unwatchNetwork()
        closeTunnel()
        Engine.setRunning(false)
        super.onDestroy()
    }

    private fun startTunnel() {
        val settings = Engine.settings
        val builder = Builder()
            .setSession("DNShield")
            .setMtu(MTU)
            .addAddress(TUN_ADDR, 24)
            .addDnsServer(DNS_ADDR)
            .addRoute(DNS_ADDR, 32)
            .setBlocking(true)
            .setConfigureIntent(openAppIntent())
            // Sin esto Android pone "::/0 unreachable" y corta TODO el IPv6 de las apps (en redes
            // solo-IPv6 el movil se quedaria sin internet). Asi lo que no enrutamos va directo.
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
        // Nuestras propias conexiones (DoH/DoT, descargas de listas) no pasan por el tunel.
        runCatching { builder.addDisallowedApplication(packageName) }
        for (pkg in settings.excludedApps) runCatching { builder.addDisallowedApplication(pkg) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e); null
        }
        if (fd == null) {
            settings.enabled = false
            shutdown()
            return
        }
        tun = fd
        // Cola acotada: una app que inunde el filtro no puede llenar la memoria; lo que no cabe se rechaza.
        pool = ThreadPoolExecutor(6, 6, 30, TimeUnit.SECONDS, LinkedBlockingQueue(MAX_PENDING)).apply {
            allowCoreThreadTimeOut(true) // sin consultas, sin hilos vivos
        }
        val pipe = Os.pipe()
        wakePipe = pipe
        reader = Thread({ readLoop(fd, pipe[0]) }, "dnshield-tun").apply { start() }
        Engine.setRunning(true)
        watchNetwork()
        notifyState()

        Thread {
            Engine.loadBlocklist()
            if (ListStore.isDue(this)) runCatching { ListStore.update(this) {} }
        }.start()
    }

    /** Bloquea en poll() hasta que haya un paquete o pidamos parar: sin temporizadores ni despertares. */
    private fun readLoop(fd: ParcelFileDescriptor, stopFd: FileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(MTU)
        val tunPoll = StructPollfd().apply { this.fd = fd.fileDescriptor; events = OsConstants.POLLIN.toShort() }
        val stopPoll = StructPollfd().apply { this.fd = stopFd; events = OsConstants.POLLIN.toShort() }
        val polls = arrayOf(tunPoll, stopPoll)
        try {
            while (true) {
                try {
                    Os.poll(polls, -1)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue else throw e
                }
                if (stopPoll.revents.toInt() != 0) break
                if (tunPoll.revents.toInt() and OsConstants.POLLIN == 0) {
                    if (tunPoll.revents.toInt() != 0) break // POLLERR/POLLHUP: el tunel se ha cerrado
                    continue
                }
                val n = input.read(buf)
                if (n <= 0) continue
                val q = Packets.parse(buf, n) ?: continue
                handle(q, output)
            }
        } catch (e: Exception) {
            Log.w(TAG, "reader stopped: $e")
        }
    }

    private fun handle(q: DnsQuery, out: FileOutputStream) {
        val d = Engine.decide(q)
        if (d.response != null) {
            write(out, d.response)
            return
        }
        val task = Runnable {
            val answer = try {
                Engine.forward(q.dnsPayload(), d.entry)
            } catch (_: Throwable) {
                null // un servidor que manda basura no debe tumbar la app
            }
            val packet = when {
                answer == null -> Packets.servfail(q)
                // Cortar una respuesta DNS la dejaria corrupta; si no cabe en el tunel, fallo limpio.
                answer.size > MTU - 48 -> Packets.servfail(q)
                // La respuesta debe llevar el id de la consulta (DoH lo restaura; aqui lo garantizamos).
                Packets.u16(answer, 0) != q.dnsId -> Packets.servfail(q)
                else -> Packets.wrap(q, answer, answer.size)
            }
            write(out, packet)
        }
        try {
            pool?.execute(task)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            write(out, Packets.servfail(q)) // saturado: fallo rapido en vez de acumular
        }
    }

    private fun write(out: FileOutputStream, packet: ByteArray) {
        try {
            synchronized(out) { out.write(packet) }
        } catch (_: Exception) {
            // el tunel se cerro mientras contestabamos
        }
    }

    // ------------------------------------------------------------ red

    /**
     * Sigue la red fisica: DNS del sistema (para "Predeterminado del sistema" y la reserva),
     * filtrar o no segun Wi-Fi/datos, y reiniciar la proteccion al cambiar de red si se pidio.
     */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = update()
            override fun onLost(network: Network) = update()

            private fun update() {
                // Solo redes fisicas: nunca nuestra propia VPN (su "DNS" somos nosotros mismos).
                val network = physicalNetwork(cm) ?: return
                val c = cm.getNetworkCapabilities(network) ?: return
                cm.getLinkProperties(network)?.dnsServers
                    ?.filter { it.hostAddress != DNS_ADDR }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { Engine.systemDns = it }
                val wifi = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                val cell = c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                val s = Engine.settings
                Engine.networkExcluded = (wifi && !s.filterWifi) || (cell && !s.filterMobile)
                val changed = lastNetwork != null && lastNetwork != network
                lastNetwork = network
                if (changed) {
                    // Las conexiones DoH/DoT viejas iban por la red anterior: se rehacen.
                    Engine.rebuildUpstream()
                    if (s.restartOnNetworkChange) main.post { start(this@ShieldVpnService, ACTION_RESTART) }
                    if (ListStore.isDue(this@ShieldVpnService)) Thread { runCatching { ListStore.update(this@ShieldVpnService) {} } }.start()
                }
            }
        }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { cm.registerNetworkCallback(req, cb, main) }.onSuccess { netCallback = cb }
    }

    /** La red fisica que usaria Android: Wi-Fi/Ethernet validada antes que datos moviles. */
    private fun physicalNetwork(cm: ConnectivityManager): Network? {
        @Suppress("DEPRECATION")
        val candidates = cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
            .filter { (_, c) ->
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        fun score(c: NetworkCapabilities) =
            (if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 10 else 0) +
                (if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) 2 else 0)
        return candidates.maxByOrNull { score(it.second) }?.first
    }

    private fun unwatchNetwork() {
        netCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        netCallback = null
        lastNetwork = null
    }

    // ------------------------------------------------------------ ciclo de vida

    private fun closeTunnel() {
        wakePipe?.let { p -> runCatching { Os.write(p[1], byteArrayOf(1), 0, 1) } }
        reader?.join(1000)
        reader = null
        wakePipe?.forEach { runCatching { Os.close(it) } }
        wakePipe = null
        pool?.shutdownNow()
        pool = null
        runCatching { tun?.close() }
        tun = null
    }

    private fun shutdown() {
        unwatchNetwork()
        closeTunnel()
        Engine.setRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        ToggleTileService.refresh(this)
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notifyState() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        ToggleTileService.refresh(this)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Protección activa", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) }
            )
        }
        val paused = Engine.isPaused
        val toggle = if (paused) action(ACTION_RESUME, "Reanudar", 2) else action(ACTION_PAUSE, "Pausar 5 min", 2)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(if (paused) "DNShield en pausa" else "Protección DNS habilitada")
            .setContentText(if (paused) "Sin bloquear durante unos minutos" else "Bloqueando anuncios y rastreadores")
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .addAction(toggle)
            .addAction(action(ACTION_STOP, "Desactivar", 3))
            .build()
    }

    private fun action(act: String, label: String, req: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, req, Intent(this, ShieldVpnService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, label, pi).build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "DNShield"
        const val ACTION_START = "dev.dnshield.app.START"
        const val ACTION_STOP = "dev.dnshield.app.STOP"
        const val ACTION_RESTART = "dev.dnshield.app.RESTART"
        const val ACTION_PAUSE = "dev.dnshield.app.PAUSE"
        const val ACTION_RESUME = "dev.dnshield.app.RESUME"
        private const val CHANNEL = "dnshield"
        private const val NOTIF_ID = 7
        private const val MTU = 4096
        private const val MAX_PENDING = 256
        private const val TUN_ADDR = "10.111.222.1"
        private const val DNS_ADDR = "10.111.222.2"

        fun start(ctx: Context, action: String = ACTION_START) {
            ctx.startForegroundService(Intent(ctx, ShieldVpnService::class.java).setAction(action))
        }

        fun stop(ctx: Context) {
            if (Engine.running.value) {
                ctx.startService(Intent(ctx, ShieldVpnService::class.java).setAction(ACTION_STOP))
            } else {
                Engine.settings.enabled = false
            }
        }

        /** Aplica cambios que necesitan rehacer el tunel (apps excluidas). */
        fun restartIfRunning(ctx: Context) {
            if (Engine.running.value) start(ctx, ACTION_RESTART)
        }
    }
}
