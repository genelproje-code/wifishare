package com.wifishare.app

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

enum class Status { STOPPED, STARTING, RUNNING, ERROR }

data class Snapshot(
    val status: Status = Status.STOPPED,
    val ssid: String = "",
    val password: String = "",
    val ip: String = "",
    val port: Int = 0,
    val devices: Int = 0,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val upstreamWifi: Boolean = false,
    val error: String? = null,
)

/** Shared, observable state between the background service and the screen. */
object HotspotState {
    @Volatile
    var current = Snapshot()
        private set

    private val listeners = CopyOnWriteArraySet<(Snapshot) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun update(change: (Snapshot) -> Snapshot) {
        val s = synchronized(this) { change(current).also { current = it } }
        main.post { listeners.forEach { it(s) } }
    }

    fun observe(l: (Snapshot) -> Unit) {
        listeners.add(l)
        l(current)
    }

    fun remove(l: (Snapshot) -> Unit) {
        listeners.remove(l)
    }
}
