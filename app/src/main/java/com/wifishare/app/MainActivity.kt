package com.wifishare.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SysSettings
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.wifishare.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var settings: Settings
    private lateinit var cm: ConnectivityManager
    private var lastQrText: String? = null
    private var wifiCount = 0

    private val stateListener: (Snapshot) -> Unit = { render(it) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val needed = requiredPermissions().filter { it != Manifest.permission.POST_NOTIFICATIONS }
            if (needed.all { result[it] == true || has(it) }) startSharing()
            else showDialog(R.string.perm_title, R.string.perm_denied, R.string.open_settings) {
                startActivity(Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null)))
            }
        }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { runOnUiThread { wifiCount++; renderUpstream() } }
        override fun onLost(network: Network) { runOnUiThread { wifiCount = maxOf(0, wifiCount - 1); renderUpstream() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        settings = Settings(this)
        cm = getSystemService(ConnectivityManager::class.java)

        b.etName.setText(settings.name)
        b.etPassword.setText(settings.password)
        b.sw24.isChecked = settings.compat24
        b.etPort.setText(settings.port.toString())
        b.tilPassword.setEndIconOnClickListener { b.etPassword.setText(Settings.randomPassword()) }

        b.btnToggle.setOnClickListener {
            when (HotspotState.current.status) {
                Status.RUNNING, Status.STARTING -> HotspotService.stop(this)
                else -> onStartPressed()
            }
        }
        b.btnCopySsid.setOnClickListener { copy(HotspotState.current.ssid) }
        b.btnCopyPass.setOnClickListener { copy(HotspotState.current.password) }
        b.btnCopyPac.setOnClickListener { copy(b.tvPac.text.toString()) }
        b.btnHowTo.setOnClickListener {
            val show = b.tvHowTo.visibility != View.VISIBLE
            b.tvHowTo.visibility = if (show) View.VISIBLE else View.GONE
            b.btnHowTo.setText(if (show) R.string.how_to_hide else R.string.how_to_show)
        }
        b.btnShareDetails.setOnClickListener { shareDetails() }

        cm.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), wifiCallback)
    }

    override fun onStart() {
        super.onStart()
        HotspotState.observe(stateListener)
    }

    override fun onStop() {
        HotspotState.remove(stateListener)
        super.onStop()
    }

    override fun onDestroy() {
        try { cm.unregisterNetworkCallback(wifiCallback) } catch (_: Exception) { }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- start flow

    private fun onStartPressed() {
        if (!saveSettings()) return
        val missing = requiredPermissions().filter { !has(it) }
        if (missing.isNotEmpty()) { permissionLauncher.launch(missing.toTypedArray()); return }
        startSharing()
    }

    private fun startSharing() {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        if (!wifi.isWifiEnabled) {
            showDialog(R.string.wifi_off_title, R.string.wifi_off_msg, R.string.turn_on_wifi) {
                startActivity(Intent(SysSettings.Panel.ACTION_WIFI))
            }
            return
        }
        // Before Android 13, Wi-Fi Direct needs Location turned on.
        if (Build.VERSION.SDK_INT < 33) {
            val lm = getSystemService(LocationManager::class.java)
            if (!lm.isLocationEnabled) {
                showDialog(R.string.location_title, R.string.location_msg, R.string.turn_on_location) {
                    startActivity(Intent(SysSettings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                return
            }
        }
        if (wifiCount == 0) {
            Toast.makeText(this, R.string.no_wifi_internet_toast, Toast.LENGTH_LONG).show()
        }
        HotspotService.start(this)
    }

    private fun saveSettings(): Boolean {
        val name = b.etName.text?.toString()?.trim().orEmpty()
        val pass = b.etPassword.text?.toString().orEmpty()
        val port = b.etPort.text?.toString()?.toIntOrNull() ?: -1
        b.tilName.error = if (Settings.validName(name)) null else getString(R.string.err_name)
        b.tilPassword.error = if (Settings.validPassword(pass)) null else getString(R.string.err_password)
        b.tilPort.error = if (Settings.validPort(port)) null else getString(R.string.err_port)
        if (b.tilName.error != null || b.tilPassword.error != null || b.tilPort.error != null) return false
        settings.name = name
        settings.password = pass
        settings.port = port
        settings.compat24 = b.sw24.isChecked
        return true
    }

    private fun requiredPermissions(): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            list += Manifest.permission.NEARBY_WIFI_DEVICES
            list += Manifest.permission.POST_NOTIFICATIONS
        } else {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return list
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- UI

    private fun render(s: Snapshot) {
        val running = s.status == Status.RUNNING
        val busy = s.status == Status.STARTING

        b.progress.visibility = if (busy) View.VISIBLE else View.GONE
        b.btnToggle.setText(if (running || busy) R.string.stop else R.string.start)
        b.btnToggle.setBackgroundColor(ContextCompat.getColor(this,
            if (running) R.color.running else if (busy) R.color.starting else R.color.brand))

        b.tvStatus.setText(when (s.status) {
            Status.RUNNING -> R.string.status_running
            Status.STARTING -> R.string.status_starting
            else -> R.string.status_stopped
        })
        b.tvError.visibility = if (s.status == Status.ERROR && s.error != null) View.VISIBLE else View.GONE
        b.tvError.text = s.error

        listOf(b.cardJoin, b.cardProxy, b.cardStats, b.btnShareDetails).forEach {
            it.visibility = if (running) View.VISIBLE else View.GONE
        }
        b.cardSettings.visibility = if (running || busy) View.GONE else View.VISIBLE
        b.tvTip.visibility = b.cardSettings.visibility

        if (running) {
            b.tvSsid.text = s.ssid
            b.tvPassword.text = s.password
            b.tvHost.text = s.ip
            b.tvPort.text = s.port.toString()
            b.tvPac.text = pacUrl(s)
            b.tvHowTo.text = getString(R.string.how_to_body, s.ip, s.port.toString(), pacUrl(s))
            b.tvDevices.text = s.devices.toString()
            b.tvData.text = getString(R.string.data_value,
                Formatter.formatShortFileSize(this, s.bytesDown), Formatter.formatShortFileSize(this, s.bytesUp))
            val qrText = wifiQr(s.ssid, s.password)
            if (qrText != lastQrText) {
                b.imgQr.setImageBitmap(makeQr(qrText, 600))
                lastQrText = qrText
            }
        }
        renderUpstream()
    }

    private fun renderUpstream() {
        b.tvUpstream.setText(if (wifiCount > 0) R.string.upstream_ok else R.string.upstream_none)
        b.tvUpstream.setTextColor(ContextCompat.getColor(this, if (wifiCount > 0) R.color.running else R.color.warn))
    }

    private fun pacUrl(s: Snapshot) = "http://${s.ip}:${s.port}/proxy.pac"

    private fun shareDetails() {
        val s = HotspotState.current
        val text = getString(R.string.share_text, s.ssid, s.password, s.ip, s.port.toString(), pacUrl(s))
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("WiFi Share", text))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun showDialog(title: Int, msg: Int, positive: Int, action: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title).setMessage(msg)
            .setPositiveButton(positive) { _, _ -> action() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        /** Standard Wi-Fi QR format understood by Android and iPhone cameras. */
        fun wifiQr(ssid: String, pass: String): String {
            fun esc(v: String) = v.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace(":", "\\:").replace("\"", "\\\"")
            return "WIFI:T:WPA;S:${esc(ssid)};P:${esc(pass)};;"
        }

        fun makeQr(text: String, size: Int): Bitmap {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
            val px = IntArray(size * size)
            for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m[x, y]) Color.BLACK else Color.WHITE
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
        }
    }
}
