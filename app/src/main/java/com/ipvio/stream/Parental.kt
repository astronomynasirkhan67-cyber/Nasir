package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.SecureRandom

/** Parental control: hides adult content and protects the setting with a 4-digit PIN. */
object Parental {
    private const val PREFS = "ipvio_general"
    var hideAdult by mutableStateOf(false)
        private set
    var pinSet by mutableStateOf(false)
        private set
    /** Changes with every switch so filtered lists are recomputed. */
    var version by mutableIntStateOf(0)
        private set
    /** Only while the parental control page is open; is reset on leaving. */
    var unlocked by mutableStateOf(false)

    private fun prefs(c: Context) = Profiles.sp(c, PREFS)

    fun init(c: Context) {
        val p = prefs(c)
        hideAdult = p.getBoolean("parental_hide", false)
        pinSet = !p.getString("parental_hash", null).isNullOrEmpty()
        unlocked = false
        version++
    }

    fun setHide(c: Context, v: Boolean) {
        hideAdult = v
        prefs(c).edit().putBoolean("parental_hide", v).apply()
        version++
    }

    private val guard = PinGuard()

    /** Seconds until a PIN may be entered again after too many failed attempts (0 = immediately). */
    fun lockSec(): Long = guard.waitSec(System.currentTimeMillis())

    fun verify(c: Context, pin: String): Boolean = verifyFor(c, Profiles.active, pin)

    /** Parental control settings of any profile (not only the active one). */
    private fun prefsOf(c: Context, profileId: Int) =
        c.applicationContext.getSharedPreferences(Profiles.scopedName(PREFS, profileId), Context.MODE_PRIVATE)

    /**
     * A profile is locked if adult content is hidden there and a PIN is set (e.g. a kids profile).
     * A locked profile can only be left with its PIN – otherwise switching would defeat the protection.
     */
    fun profileLocked(c: Context, profileId: Int): Boolean {
        val p = prefsOf(c, profileId)
        return p.getBoolean("parental_hide", false) && !p.getString("parental_hash", null).isNullOrEmpty()
    }

    fun verifyFor(c: Context, profileId: Int, pin: String): Boolean {
        val now = System.currentTimeMillis()
        if (guard.waitSec(now) > 0) return false
        val p = prefsOf(c, profileId)
        val h = p.getString("parental_hash", null) ?: return false
        val ok = ParentalLogic.check(pin, p.getString("parental_salt", "") ?: "", h)
        guard.record(ok, now)
        return ok
    }

    /** Error text after a wrong PIN (with waiting time if locked). */
    fun pinError(): String {
        val w = lockSec()
        return if (w > 0) "Zu viele Fehlversuche. Bitte in $w s erneut versuchen." else "Falsche PIN"
    }

    fun setPin(c: Context, pin: String) {
        if (!ParentalLogic.validPin(pin)) return
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs(c).edit().putString("parental_salt", salt).putString("parental_hash", ParentalLogic.hash(pin, salt)).apply()
        pinSet = true
    }

    fun clearPin(c: Context) {
        prefs(c).edit().remove("parental_salt").remove("parental_hash").apply()
        pinSet = false
    }

    fun filter(list: List<Item>): List<Item> = list.filterNot { ParentalLogic.isAdult(it.group, it.title) }
}
