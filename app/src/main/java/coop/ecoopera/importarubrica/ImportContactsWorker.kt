package coop.ecoopera.importarubrica

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnForceSync: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        btnForceSync = findViewById(R.id.btnForceSync)

        btnForceSync.setOnClickListener {
            forceSyncNow()
        }

        checkAndSetup()
    }

    // -----------------------------------
    // ✅ SETUP INIZIALE
    // -----------------------------------
    private fun checkAndSetup() {
        if (hasPermissions()) {
            checkBatteryOptimization()
            setupAutomaticWork()
            showActiveStatus()
        } else {
            requestPermissions()
        }
    }

    // -----------------------------------
    // ✅ PERMESSI
    // -----------------------------------
    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS
            ),
            100
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            checkBatteryOptimization()
            setupAutomaticWork()
            showActiveStatus()
        } else {
            statusText.text = "Permessi necessari per sincronizzare la rubrica."
        }
    }

    // -----------------------------------
    // 🔋 BATTERY SMART (Samsung + fallback)
    // -----------------------------------
    private fun checkBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager

        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            showBatteryDialog()
        } else {
            if (isSamsungDevice()) {
                showSamsungExtraStep()
            }
        }
    }

    private fun showBatteryDialog() {
        AlertDialog.Builder(this)
            .setTitle("Ottimizzazione batteria")
            .setMessage(
                "Per mantenere la sincronizzazione automatica attiva, " +
                "è necessario disattivare le restrizioni della batteria.\n\n" +
                "Ti guideremo nei passaggi."
            )
            .setCancelable(false)
            .setPositiveButton("Continua") { _, _ ->
                requestDisableBatteryOptimization()
            }
            .show()
    }

    private fun requestDisableBatteryOptimization() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (e: Exception) {
            openBatteryFallback()
        }
    }

    private fun isSamsungDevice(): Boolean {
        return Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    }

    private fun showSamsungExtraStep() {
        AlertDialog.Builder(this)
            .setTitle("Configurazione Samsung")
            .setMessage(
                "Per garantire il funzionamento automatico:\n\n" +
                "1. Vai in 'Batteria'\n" +
                "2. Apri 'Limiti utilizzo app'\n" +
                "3. Inserisci Sincronizzazione rubrica in 'App mai in sospensione'"
            )
            .setPositiveButton("Apri impostazioni") { _, _ ->
                openSamsungBatterySettings()
            }
            .setNegativeButton("Salta", null)
            .show()
    }

    private fun openSamsungBatterySettings() {
        try {
            val intent = Intent()
            intent.component = ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity"
            )
            startActivity(intent)
        } catch (e: Exception) {
            openBatteryFallback()
        }
    }

    // fallback semplice (come richiesto)
    private fun openBatteryFallback() {
        AlertDialog.Builder(this)
            .setTitle("Impostazioni batteria")
            .setMessage(
                "Vai nelle impostazioni di sistema e disattiva le restrizioni della batteria per questa app, " +
                "altrimenti la sincronizzazione potrebbe non funzionare correttamente."
            )
            .setPositiveButton("Apri impostazioni") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                } catch (_: Exception) {
                }
            }
            .show()
    }

    // -----------------------------------
    // ⏱ WORKMANAGER
    // -----------------------------------
    private fun setupAutomaticWork() {

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest =
            PeriodicWorkRequestBuilder<ImportContactsWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "ContattiSync",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    private fun forceSyncNow() {
        val forceRequest = OneTimeWorkRequestBuilder<ImportContactsWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueue(forceRequest)
    }

    private fun showActiveStatus() {
        statusText.text =
            "Sincronizzazione automatica attiva (ogni 12 ore).\nPuoi chiudere l'app."
        btnForceSync.visibility = View.VISIBLE
    }
}
