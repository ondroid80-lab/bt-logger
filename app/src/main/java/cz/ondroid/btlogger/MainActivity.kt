package cz.ondroid.btlogger

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMS = 10
    }

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var btnToggle: Button
    private var startAfterPerms = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        logView = findViewById(R.id.log)
        btnToggle = findViewById(R.id.btnToggle)

        btnToggle.setOnClickListener { toggle() }
        findViewById<Button>(R.id.btnPerms).setOnClickListener { requestPerms(false) }
        findViewById<Button>(R.id.btnAccess).setOnClickListener { openAccessibility() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestBattery() }
        findViewById<Button>(R.id.btnMark).setOnClickListener {
            LogStore.log(this, "ZNACKA", "Ruční značka z aplikace")
        }
        findViewById<Button>(R.id.btnReport).setOnClickListener { showReport() }
        findViewById<Button>(R.id.btnExport).setOnClickListener { export() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        LogStore.listener = { refresh() }
        refresh()
        // Log (označitelný text) si jinak vezme fokus a obrazovka sjede dolů,
        // takže tlačítko Spustit záznam není vidět.
        val scroll = findViewById<android.widget.ScrollView>(R.id.scroll)
        scroll.post { scroll.scrollTo(0, 0) }
    }

    override fun onPause() {
        LogStore.listener = null
        super.onPause()
    }

    // ---------------------------------------------------------------- UI

    private fun refresh() {
        val running = LoggerService.running
        btnToggle.text = if (running) "Zastavit záznam" else "Spustit záznam"
        btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (running) 0xFFC62828.toInt() else 0xFF2E7D32.toInt()
        )

        val sb = StringBuilder()
        sb.append("Záznam: ").append(if (running) "BĚŽÍ ✅" else "zastaven").append('\n')
        sb.append("Oprávnění: ").append(if (missingPerms().isEmpty()) "OK ✅" else "chybí ⚠️").append('\n')
        sb.append("Značky hlasitostí +: ").append(if (accessibilityOn()) "zapnuto ✅" else "vypnuto ⚠️").append('\n')
        sb.append("Optimalizace baterie: ").append(if (batteryIgnored()) "vypnuta ✅" else "zapnuta ⚠️").append("\n\n")
        sb.append("Značek: ${LogStore.count(this, "ZNACKA")}   ")
        sb.append("Přerušení A2DP: ${LogStore.count(this, "A2DP_STOP")}   ")
        sb.append("Pauz hudby: ${LogStore.count(this, "HUDBA_STOP")}")
        status.text = sb.toString()

        logView.text = LogStore.recentLines(this).asReversed().take(200).joinToString("\n") { line ->
            val p = line.split(';', limit = 4)
            if (p.size == 4) "${p[0].substringAfter(' ')}  ${p[2]}  ${p[3]}" else line
        }
    }

    // ---------------------------------------------------------------- logging

    private fun toggle() {
        if (LoggerService.running) {
            startService(Intent(this, LoggerService::class.java).setAction(LoggerService.ACTION_STOP))
            logView.postDelayed({ refresh() }, 300)
            return
        }
        if (missingPerms().isNotEmpty()) {
            requestPerms(true)
            return
        }
        startLogger()
    }

    private fun startLogger() {
        startForegroundService(Intent(this, LoggerService::class.java))
        logView.postDelayed({ refresh() }, 300)
    }

    // ---------------------------------------------------------------- permissions

    private fun missingPerms(): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) list.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun requestPerms(thenStart: Boolean) {
        val missing = missingPerms()
        if (missing.isEmpty()) {
            Toast.makeText(this, "Oprávnění už jsou povolená", Toast.LENGTH_SHORT).show()
            if (thenStart) startLogger()
            return
        }
        startAfterPerms = thenStart
        requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val btOk = Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (!btOk) {
            Toast.makeText(this, "Bez oprávnění „Zařízení v okolí“ nelze sledovat Bluetooth", Toast.LENGTH_LONG).show()
        } else if (startAfterPerms) {
            startLogger()
        }
        startAfterPerms = false
        refresh()
    }

    private fun accessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains("$packageName/")
    }

    private fun openAccessibility() {
        AlertDialog.Builder(this)
            .setTitle("Značky tlačítkem hlasitosti")
            .setMessage(
                "V dalším okně otevřete „Nainstalované aplikace“ (nebo „Nainstalované služby“) → BT Logger a zapněte ho.\n\n" +
                    "Pokud je přepínač šedý („Omezené nastavení“): Nastavení → Aplikace → BT Logger → ⋮ vpravo nahoře → " +
                    "„Povolit omezená nastavení“ a zkuste to znovu.\n\n" +
                    "Android zobrazí varování, že aplikace může sledovat vaše akce. Tahle aplikace sleduje jen tlačítko hlasitosti nahoru."
            )
            .setPositiveButton("Otevřít") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Zrušit", null)
            .show()
    }

    private fun batteryIgnored(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    private fun requestBattery() {
        if (batteryIgnored()) {
            Toast.makeText(this, "Optimalizace baterie je už vypnutá", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---------------------------------------------------------------- report & export

    private fun showReport() {
        val tv = TextView(this).apply {
            text = LogStore.markerReport(this@MainActivity)
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setPadding(40, 20, 40, 20)
        }
        AlertDialog.Builder(this)
            .setTitle("Rozbor značek")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Zavřít", null)
            .show()
    }

    private fun export() {
        val src = LogStore.file(this)
        val dir = File(cacheDir, "export").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val out = File(dir, "bt_log_$stamp.csv")
        src.copyTo(out, overwrite = true)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", out)

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "BT Logger – log $stamp")
            putExtra(Intent.EXTRA_TEXT, "Telefon: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n\n" + LogStore.markerReport(this@MainActivity))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Sdílet log"))
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Vymazat log?")
            .setMessage("Smaže všechny zaznamenané události.")
            .setPositiveButton("Vymazat") { _, _ -> LogStore.clear(this) }
            .setNegativeButton("Zrušit", null)
            .show()
    }
}
