package coop.ecoopera.importarubrica

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnForceSync: Button

    companion object {
        private const val WORK_NAME = "ContattiSync"
        private const val WORK_MANUAL = "ContattiSyncManual"
    }

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
    // ✅ SETUP
    // -----------------------------------
    private fun checkAndSetup() {
        if (hasPermissions()) {
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

        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            setupAutomaticWork()
            showActiveStatus()
        } else {
            statusText.text = "Permessi necessari per aggiornare la rubrica aziendale."
            btnForceSync.visibility = View.GONE
        }
    }

    // -----------------------------------
    // ⏱ SYNC AUTOMATICA
    // -----------------------------------
    private fun setupAutomaticWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest =
            PeriodicWorkRequestBuilder<ImportContactsWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

        // CORREZIONE CRITICA: Cambiato da UPDATE a KEEP per evitare il reset del timer ad ogni apertura dell'app
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    // -----------------------------------
    // 🚀 SYNC MANUALE (CORRETTO CONTRO CONFLITTI)
    // -----------------------------------
    private fun forceSyncNow() {
        val forceRequest =
            OneTimeWorkRequestBuilder<ImportContactsWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

        // Usiamo KEEP per evitare che, se l'utente clicca più volte di fila,
        // l'operazione in corso venga bruscamente interrotta e ricreata da zero.
        WorkManager.getInstance(this).enqueueUniqueWork(
            WORK_MANUAL,
            ExistingWorkPolicy.KEEP,
            forceRequest
        )

        Toast.makeText(this, "Sincronizzazione forzata avviata...", Toast.LENGTH_SHORT).show()
    }

    // -----------------------------------
    // ✅ UI
    // -----------------------------------
    private fun showActiveStatus() {
        statusText.text = "Sincronizzazione automatica attiva (ogni 12 ore).\nPuoi chiudere l'app."
        btnForceSync.visibility = View.VISIBLE
    }
}