package cz.ondroid.btlogger

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

@SuppressLint("MissingPermission")
class LoggerService : Service() {

    companion object {
        const val ACTION_STOP = "cz.ondroid.btlogger.STOP"
        const val ACTION_MARK = "cz.ondroid.btlogger.MARK"
        private const val CHANNEL = "logger"
        private const val NOTIF_ID = 1
        private const val CODEC_CHANGED = "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"
        private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"

        @Volatile
        var running = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private var started = false

    private var musicActive = false
    private var musicInactiveSince = 0L
    private var a2dpStoppedSince = 0L
    private var lastPlaybackSig: String? = null
    private var a2dpProxy: BluetoothA2dp? = null
    private var modeListener: AudioManager.OnModeChangedListener? = null

    private fun log(type: String, msg: String) = LogStore.log(this, type, msg)

    // ---------------------------------------------------------------- lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            log("CHYBA", "Službu nelze spustit (chybí oprávnění Bluetooth?): ${e.message}")
            stopSelf()
            return
        }
        started = true
        running = true
        audio = getSystemService(AudioManager::class.java)

        log("START", "Záznam spuštěn – ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        registerReceivers()
        audio.registerAudioDeviceCallback(deviceCallback, handler)
        audio.registerAudioPlaybackCallback(playbackCallback, handler)
        if (Build.VERSION.SDK_INT >= 31) {
            val l = AudioManager.OnModeChangedListener { mode -> log("AUDIO_REZIM", modeName(mode)) }
            audio.addOnModeChangedListener(mainExecutor, l)
            modeListener = l
        }
        musicActive = audio.isMusicActive
        log("HUDBA", if (musicActive) "Na začátku hudba hraje" else "Na začátku hudba nehraje")
        log("AUDIO_REZIM", "Výchozí: ${modeName(audio.mode)}")
        connectA2dpProxy()
        handler.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MARK -> log("ZNACKA", "Ruční značka z notifikace")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (started) {
            handler.removeCallbacksAndMessages(null)
            try { unregisterReceiver(receiver) } catch (_: Exception) {}
            try { audio.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}
            try { audio.unregisterAudioPlaybackCallback(playbackCallback) } catch (_: Exception) {}
            if (Build.VERSION.SDK_INT >= 31) {
                modeListener?.let { try { audio.removeOnModeChangedListener(it) } catch (_: Exception) {} }
            }
            a2dpProxy?.let { p ->
                try {
                    getSystemService(BluetoothManager::class.java)?.adapter
                        ?.closeProfileProxy(BluetoothProfile.A2DP, p)
                } catch (_: Exception) {}
            }
            log("STOP", "Záznam ukončen")
        }
        running = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Záznam Bluetooth", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val mark = PendingIntent.getService(this, 1, Intent(this, LoggerService::class.java).setAction(ACTION_MARK), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, LoggerService::class.java).setAction(ACTION_STOP), flags)
        val icon = Icon.createWithResource(this, android.R.drawable.stat_sys_data_bluetooth)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BT Logger zaznamenává")
            .setContentText("Dvojklik hlasitosti + = značka výpadku")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(icon, "Značka", mark).build())
            .addAction(Notification.Action.Builder(icon, "Zastavit", stop).build())
            .build()
    }

    // ---------------------------------------------------------------- polling

    /** Každých 200 ms kontroluje, jestli systém hlásí hrající hudbu. */
    private val poll = object : Runnable {
        override fun run() {
            val a = audio.isMusicActive
            if (a != musicActive) {
                val now = SystemClock.elapsedRealtime()
                if (!a) {
                    musicInactiveSince = now
                    log("HUDBA_STOP", "Systém hlásí, že hudba přestala hrát")
                } else {
                    val gap = if (musicInactiveSince > 0) now - musicInactiveSince else -1
                    log("HUDBA_START", "Hudba znovu hraje" + if (gap >= 0) " (pauza $gap ms)" else "")
                }
                musicActive = a
            }
            handler.postDelayed(this, 200)
        }
    }

    // ---------------------------------------------------------------- A2DP proxy

    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun connectA2dpProxy() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: run {
            log("CHYBA", "Bluetooth není k dispozici")
            return
        }
        if (!hasBtPermission()) {
            log("CHYBA", "Chybí oprávnění Bluetooth – BT události se nebudou zapisovat")
            return
        }
        log("BT_ADAPTER", if (adapter.isEnabled) "Bluetooth zapnuté" else "Bluetooth vypnuté")
        adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val p = proxy as BluetoothA2dp
                a2dpProxy = p
                try {
                    val devs = p.connectedDevices
                    if (devs.isEmpty()) log("A2DP_SPOJENI", "Žádné připojené A2DP zařízení")
                    devs.forEach { d ->
                        log("A2DP_SPOJENI", "Připojeno: ${name(d)}, přenos ${if (p.isA2dpPlaying(d)) "běží" else "neběží"}")
                    }
                } catch (_: SecurityException) {}
            }

            override fun onServiceDisconnected(profile: Int) {
                a2dpProxy = null
            }
        }, BluetoothProfile.A2DP)
    }

    // ---------------------------------------------------------------- receivers

    private fun registerReceivers() {
        val f = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(CODEC_CHANGED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            addAction(VOLUME_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_EXPORTED)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val now = SystemClock.elapsedRealtime()
            when (intent.action) {
                BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    val dev = name(device(intent))
                    if (state == BluetoothA2dp.STATE_NOT_PLAYING) {
                        a2dpStoppedSince = now
                        log("A2DP_STOP", "Přenos zvuku přes BT zastaven ($dev)")
                    } else if (state == BluetoothA2dp.STATE_PLAYING) {
                        val gap = if (a2dpStoppedSince > 0) now - a2dpStoppedSince else -1
                        a2dpStoppedSince = 0
                        log("A2DP_START", "Přenos zvuku přes BT běží ($dev)" + if (gap >= 0) " – přerušení $gap ms" else "")
                    }
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> log(
                    "A2DP_SPOJENI",
                    "${connName(intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1))} → " +
                        "${connName(intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1))} (${name(device(intent))})"
                )
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> log(
                    "HFP_SPOJENI",
                    "${connName(intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1))} → " +
                        "${connName(intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1))} (${name(device(intent))})"
                )
                BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED -> {
                    val s = when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                        BluetoothHeadset.STATE_AUDIO_CONNECTED -> "hovorový kanál otevřen"
                        BluetoothHeadset.STATE_AUDIO_CONNECTING -> "hovorový kanál se otevírá"
                        BluetoothHeadset.STATE_AUDIO_DISCONNECTED -> "hovorový kanál zavřen"
                        else -> "neznámý stav"
                    }
                    log("HFP_AUDIO", "$s (${name(device(intent))})")
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> log("BT_ACL", "Spojení navázáno (${name(device(intent))})")
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> log("BT_ACL", "Spojení přerušeno (${name(device(intent))})")
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val s = when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        BluetoothAdapter.STATE_ON -> "zapnuto"
                        BluetoothAdapter.STATE_OFF -> "vypnuto"
                        BluetoothAdapter.STATE_TURNING_ON -> "zapíná se"
                        BluetoothAdapter.STATE_TURNING_OFF -> "vypíná se"
                        else -> "neznámý stav"
                    }
                    log("BT_ADAPTER", "Bluetooth $s")
                }
                CODEC_CHANGED -> log("KODEK", "Změna kodeku: ${extras(intent)}")
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> log("AUDIO", "Zvukový výstup se odpojuje (becoming noisy)")
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val s = when (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)) {
                        AudioManager.SCO_AUDIO_STATE_CONNECTED -> "připojen"
                        AudioManager.SCO_AUDIO_STATE_CONNECTING -> "připojuje se"
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> "odpojen"
                        else -> "chyba"
                    }
                    log("SCO", "Hovorový zvuk (SCO) $s")
                }
                VOLUME_CHANGED -> {
                    val stream = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                    if (stream == AudioManager.STREAM_MUSIC) {
                        val v = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                        val p = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
                        if (v != p) log("HLASITOST", "Hlasitost médií $p → $v")
                    }
                }
                Intent.ACTION_SCREEN_ON -> log("DISPLEJ", "Displej zapnut")
                Intent.ACTION_SCREEN_OFF -> log("DISPLEJ", "Displej vypnut")
                Intent.ACTION_POWER_CONNECTED -> log("NABIJENI", "Nabíjení připojeno")
                Intent.ACTION_POWER_DISCONNECTED -> log("NABIJENI", "Nabíjení odpojeno")
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    val on = getSystemService(PowerManager::class.java).isPowerSaveMode
                    log("USPORA", if (on) "Úsporný režim zapnut" else "Úsporný režim vypnut")
                }
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    val s = when (intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1)) {
                        WifiManager.WIFI_STATE_ENABLED -> "zapnuta"
                        WifiManager.WIFI_STATE_DISABLED -> "vypnuta"
                        WifiManager.WIFI_STATE_ENABLING -> "zapíná se"
                        WifiManager.WIFI_STATE_DISABLING -> "vypíná se"
                        else -> "neznámý stav"
                    }
                    log("WIFI", "Wi-Fi $s")
                }
            }
        }
    }

    // ---------------------------------------------------------------- audio callbacks

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            addedDevices?.filter { it.isSink }?.forEach {
                log("VYSTUP", "Výstup přidán: ${deviceTypeName(it.type)} ${it.productName}")
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            removedDevices?.filter { it.isSink }?.forEach {
                log("VYSTUP", "Výstup odebrán: ${deviceTypeName(it.type)} ${it.productName}")
            }
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val sig = (configs ?: emptyList()).map { c ->
                val usage = usageName(c.audioAttributes.usage)
                if (Build.VERSION.SDK_INT >= 31) {
                    val dev = c.audioDeviceInfo?.let { deviceTypeName(it.type) } ?: "?"
                    "$usage → $dev"
                } else usage
            }.sorted().joinToString(", ")
            if (sig != lastPlaybackSig) {
                lastPlaybackSig = sig
                log("PREHRAVAC", if (sig.isEmpty()) "Žádný aktivní přehrávač" else "Aktivní: $sig")
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun device(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun name(d: BluetoothDevice?): String = try {
        d?.name ?: d?.address ?: "?"
    } catch (_: SecurityException) {
        "?"
    }

    private fun extras(intent: Intent): String {
        val b = intent.extras ?: return "-"
        return b.keySet().joinToString(", ") { k ->
            @Suppress("DEPRECATION")
            val v = b.get(k)
            "${k.substringAfterLast('.')}=${if (v is BluetoothDevice) name(v) else v}"
        }
    }

    private fun connName(s: Int) = when (s) {
        BluetoothProfile.STATE_CONNECTED -> "připojeno"
        BluetoothProfile.STATE_CONNECTING -> "připojuje se"
        BluetoothProfile.STATE_DISCONNECTED -> "odpojeno"
        BluetoothProfile.STATE_DISCONNECTING -> "odpojuje se"
        else -> "?"
    }

    private fun modeName(m: Int) = when (m) {
        AudioManager.MODE_NORMAL -> "normální"
        AudioManager.MODE_RINGTONE -> "vyzvánění"
        AudioManager.MODE_IN_CALL -> "hovor"
        AudioManager.MODE_IN_COMMUNICATION -> "komunikace (VoIP / asistent)"
        else -> "režim $m"
    }

    private fun usageName(u: Int) = when (u) {
        AudioAttributes.USAGE_MEDIA -> "média"
        AudioAttributes.USAGE_GAME -> "hra"
        AudioAttributes.USAGE_NOTIFICATION -> "notifikace"
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "vyzvánění"
        AudioAttributes.USAGE_NOTIFICATION_EVENT -> "upozornění"
        AudioAttributes.USAGE_ALARM -> "budík"
        AudioAttributes.USAGE_VOICE_COMMUNICATION -> "hovor"
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "navigace"
        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "systémový zvuk"
        AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY -> "usnadnění"
        AudioAttributes.USAGE_ASSISTANT -> "asistent"
        AudioAttributes.USAGE_UNKNOWN -> "neznámé"
        else -> "použití $u"
    }

    private fun deviceTypeName(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT hovor (SCO)"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "reproduktor telefonu"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "sluchátko telefonu"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "kabelová sluchátka"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
        AudioDeviceInfo.TYPE_TELEPHONY -> "telefonie"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "virtuální výstup"
        AudioDeviceInfo.TYPE_BUS -> "sběrnice (Android Auto)"
        26, 27 -> "BT LE audio"
        else -> "typ $t"
    }
}
