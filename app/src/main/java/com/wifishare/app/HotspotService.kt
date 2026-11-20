package com.wifishare.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.net.InetAddress
import java.net.Socket

/**
 * Runs in the background (with a notification) while sharing is on:
 *  1. creates a Wi-Fi Direct group = a password-protected Wi-Fi network other devices can join,
 *     while this phone stays connected to its normal Wi-Fi;
 *  2. runs a proxy on that network which forwards traffic over this phone's Wi-Fi internet.
 */
class HotspotService : Service() {

    companion object {
        const val ACTION_START = "com.wifishare.app.START"
        const val ACTION_STOP = "com.wifishare.app.STOP"
        private const val CHANNEL_ID = "sharing"
        private const val NOTIF_ID = 1
        private const val FALLBACK_IP = "192.168.49.1"   // standard Wi-Fi Direct group owner address

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, HotspotService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, HotspotService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var p2p: WifiP2pManager
    private lateinit var cm: ConnectivityManager
    private var channel: WifiP2pManager.Channel? = null
    private var proxy: ProxyServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var wifiNetwork: Network? = null
    private val handler = Handler(Looper.getMainLooper())
    private var receiverRegistered = false
    private var stopping = false
    private var groupMisses = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        p2p = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        cm = getSystemService(ConnectivityManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown(null)
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_starting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        val st = HotspotState.current.status
        if (st != Status.RUNNING && st != Status.STARTING) begin()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!stopping) shutdown(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- start

    private fun begin() {
        stopping = false
        groupMisses = 0
        HotspotState.update { Snapshot(status = Status.STARTING, upstreamWifi = it.upstreamWifi) }

        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifi.isWifiEnabled) { shutdown(getString(R.string.err_wifi_off)); return }

        acquireLocks(wifi)
        watchWifiNetwork()
        registerP2pReceiver()

        val ch = p2p.initialize(this, mainLooper) {
            if (!stopping) shutdown(getString(R.string.err_channel_lost))
        }
        if (ch == null) { shutdown(getString(R.string.err_p2p_unsupported)); return }
        channel = ch

        // Remove any old group left over, then create ours.
        p2p.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { handler.postDelayed({ createGroup(ch, 0) }, 700) }
            override fun onFailure(reason: Int) { createGroup(ch, 0) }
        })
    }

    @SuppressLint("MissingPermission")
    private fun createGroup(ch: WifiP2pManager.Channel, attempt: Int) {
        if (stopping) return
        val s = Settings(this)
        val band = if (s.compat24) WifiP2pConfig.GROUP_OWNER_BAND_2GHZ else WifiP2pConfig.GROUP_OWNER_BAND_AUTO
        val config = WifiP2pConfig.Builder()
            .setNetworkName(s.ssid)
            .setPassphrase(s.password)
            .setGroupOperatingBand(band)
            .enablePersistentMode(false)
            .build()
        try {
            p2p.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { waitForGroup(ch, 0) }
                override fun onFailure(reason: Int) {
                    if (reason == WifiP2pManager.BUSY && attempt < 3) {
                        p2p.removeGroup(ch, null)
                        handler.postDelayed({ createGroup(ch, attempt + 1) }, 1500)
                    } else {
                        shutdown(when (reason) {
                            WifiP2pManager.P2P_UNSUPPORTED -> getString(R.string.err_p2p_unsupported)
                            WifiP2pManager.BUSY -> getString(R.string.err_busy)
                            else -> getString(R.string.err_generic, reason)
                        })
                    }
                }
            })
        } catch (e: SecurityException) {
            shutdown(getString(R.string.err_permission))
        }
    }

    @SuppressLint("MissingPermission")
    private fun waitForGroup(ch: WifiP2pManager.Channel, tries: Int) {
        if (stopping) return
        try {
            p2p.requestGroupInfo(ch) { group ->
                if (stopping) return@requestGroupInfo
                if (group != null && group.isGroupOwner) {
                    p2p.requestConnectionInfo(ch) { info ->
                        val ip = info?.groupOwnerAddress?.hostAddress ?: FALLBACK_IP
                        startProxy(group.networkName, group.passphrase ?: "", ip, group.clientList.size)
                    }
                } else if (tries < 30) {
                    handler.postDelayed({ waitForGroup(ch, tries + 1) }, 500)
                } else {
                    shutdown(getString(R.string.err_group_timeout))
                }
            }
        } catch (e: SecurityException) {
            shutdown(getString(R.string.err_permission))
        }
    }

    private fun startProxy(ssid: String, pass: String, ip: String, clients: Int) {
        val port = Settings(this).port
        Thread {
            var server: ProxyServer? = null
            var error: Exception? = null
            // The hotspot address can take a moment to become usable, so retry briefly.
            for (i in 0 until 12) {
                try {
                    server = ProxyServer(ip, port).also {
                        it.setUpstream(wifiUpstream)
                        it.start()
                    }
                    break
                } catch (e: Exception) {
                    error = e
                    Thread.sleep(500)
                }
            }
            handler.post {
                val srv = server
                if (stopping) { srv?.stop(); return@post }
                if (srv == null) {
                    shutdown(getString(R.string.err_proxy, error?.message ?: ""))
                    return@post
                }
                proxy = srv
                HotspotState.update {
                    it.copy(status = Status.RUNNING, ssid = ssid, password = pass, ip = ip, port = port,
                        devices = clients, bytesUp = 0, bytesDown = 0, error = null)
                }
                updateNotification(getString(R.string.notif_running, ssid))
                handler.postDelayed(poller, 2000)
            }
        }.start()
    }

    /** Outgoing connections always go over Wi-Fi when it is available. */
    private val wifiUpstream = object : ProxyServer.Upstream {
        override fun newSocket(): Socket = wifiNetwork?.socketFactory?.createSocket() ?: Socket()
        override fun resolve(host: String): Array<InetAddress> =
            wifiNetwork?.getAllByName(host) ?: InetAddress.getAllByName(host)
    }

    // ---------------------------------------------------------------- while running

    private val poller = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            val ch = channel ?: return
            val px = proxy ?: return
            try {
                p2p.requestGroupInfo(ch) { g ->
                    if (stopping) return@requestGroupInfo
                    if (g == null) {
                        if (++groupMisses >= 3) shutdown(getString(R.string.err_group_lost))
                        return@requestGroupInfo
                    }
                    groupMisses = 0
                    val n = g.clientList.size
                    HotspotState.update { it.copy(devices = n, bytesUp = px.bytesUp, bytesDown = px.bytesDown) }
                    updateNotification(getString(R.string.notif_running_devices, g.networkName, n))
                }
            } catch (_: SecurityException) { }
            if (!stopping) handler.postDelayed(this, 2000)
        }
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) {
                val state = i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED && !stopping &&
                    HotspotState.current.status != Status.STOPPED) {
                    shutdown(getString(R.string.err_wifi_off))
                }
            }
        }
    }

    private fun registerP2pReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(this, p2pReceiver,
            IntentFilter(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun watchWifiNetwork() {
        if (netCallback != null) return
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiNetwork = network
                HotspotState.update { it.copy(upstreamWifi = true) }
            }
            override fun onLost(network: Network) {
                if (wifiNetwork == network) wifiNetwork = null
                HotspotState.update { it.copy(upstreamWifi = false) }
            }
        }
        cm.registerNetworkCallback(req, cb)
        netCallback = cb
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks(wifi: WifiManager) {
        if (wifiLock == null) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "WifiShare:wifi").apply {
                setReferenceCounted(false); acquire()
            }
        }
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiShare:cpu").apply {
                setReferenceCounted(false); acquire()
            }
        }
    }

    // ---------------------------------------------------------------- stop

    /** Stops everything. [error] = null for a normal stop. */
    private fun shutdown(error: String?) {
        if (stopping && error == null && HotspotState.current.status == Status.STOPPED) return
        stopping = true
        handler.removeCallbacksAndMessages(null)
        proxy?.stop(); proxy = null
        channel?.let { ch ->
            try { p2p.removeGroup(ch, null) } catch (_: Exception) { }
            try { ch.close() } catch (_: Exception) { }
        }
        channel = null
        if (receiverRegistered) { try { unregisterReceiver(p2pReceiver) } catch (_: Exception) { }; receiverRegistered = false }
        netCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) { } }
        netCallback = null; wifiNetwork = null
        wifiLock?.let { if (it.isHeld) it.release() }; wifiLock = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null

        HotspotState.update {
            if (error != null) Snapshot(status = Status.ERROR, error = error) else Snapshot(status = Status.STOPPED)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------- notification

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, HotspotService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_share_wifi)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
