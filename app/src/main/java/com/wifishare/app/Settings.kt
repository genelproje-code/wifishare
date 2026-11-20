package com.wifishare.app

import android.content.Context
import java.security.SecureRandom

/** User settings, saved on the phone. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var name: String
        get() = prefs.getString(KEY_NAME, null) ?: ("Share" + (1000..9999).random()).also { name = it }
        set(v) = prefs.edit().putString(KEY_NAME, v).apply()

    var password: String
        get() = prefs.getString(KEY_PASS, null) ?: randomPassword().also { password = it }
        set(v) = prefs.edit().putString(KEY_PASS, v).apply()

    /** Force 2.4 GHz: slower, but works with old phones, laptops and TVs. */
    var compat24: Boolean
        get() = prefs.getBoolean(KEY_24, false)
        set(v) = prefs.edit().putBoolean(KEY_24, v).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(v) = prefs.edit().putInt(KEY_PORT, v).apply()

    /** Wi-Fi Direct network names must start with "DIRECT-" followed by two characters. */
    val ssid: String get() = SSID_PREFIX + name

    companion object {
        const val SSID_PREFIX = "DIRECT-WS-"
        const val DEFAULT_PORT = 8282
        const val MAX_NAME = 22          // SSID limit is 32 bytes, prefix uses 10
        private const val KEY_NAME = "name"
        private const val KEY_PASS = "password"
        private const val KEY_24 = "compat24"
        private const val KEY_PORT = "port"

        private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789" // no look-alike characters

        fun randomPassword(length: Int = 10): String {
            val r = SecureRandom()
            return (1..length).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
        }

        fun validName(n: String) = n.length in 1..MAX_NAME && n.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }
        fun validPassword(p: String) = p.length in 8..63 && p.all { it.code in 32..126 }
        fun validPort(p: Int) = p in 1024..65535
    }
}
