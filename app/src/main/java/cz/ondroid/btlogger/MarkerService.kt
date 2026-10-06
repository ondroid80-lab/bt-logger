package cz.ondroid.btlogger

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Služba usnadnění: dvojité rychlé stisknutí hlasitosti nahoru = značka výpadku.
 * Jednotlivá stisknutí normálně fungují (událost se nepohlcuje).
 * Po dvojkliku se hlasitost vrátí o dva kroky zpět, takže se nezmění.
 */
class MarkerService : AccessibilityService() {

    companion object {
        private const val DOUBLE_PRESS_MS = 500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastDown = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        LogStore.log(this, "INFO", "Ovladač značek (hlasitost +) aktivní")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false

        val now = SystemClock.uptimeMillis()
        if (now - lastDown <= DOUBLE_PRESS_MS) {
            lastDown = 0
            mark()
        } else {
            lastDown = now
        }
        return false // stisk propustíme dál, hlasitost funguje normálně
    }

    private fun mark() {
        val note = if (LoggerService.running) "" else " (POZOR: záznam neběží)"
        LogStore.log(this, "ZNACKA", "Dvojklik hlasitosti +$note")
        vibrate()
        // vrátit hlasitost o dva kroky zpět – až po zpracování druhého stisku systémem
        handler.postDelayed({
            val audio = getSystemService(AudioManager::class.java)
            repeat(2) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            }
        }, 350)
    }

    private fun vibrate() {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 80, 80), -1))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
}
