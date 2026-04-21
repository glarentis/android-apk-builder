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

    private fun checkAndSetup() {
        if (hasPermissions()) {
            setupAutomaticWork()
            showActiveStatus()
        } else {
            requestPermissions()
        }
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
            100
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            setupAutomaticWork()
            showActiveStatus()
        } else {
            statusText.text = "Permessi necessari per aggiornare la rubrica aziendale."
        }
    }

    private fun setupAutomaticWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<ImportContactsWorker>(3, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "ContattiSync",
            ExistingPeriodicWorkPolicy.KEEP, // Mantiene la pianificazione esistente
            workRequest
        )
    }

    private fun forceSyncNow() {
        val forceRequest = OneTimeWorkRequestBuilder<ImportContactsWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

        WorkManager.getInstance(this).enqueue(forceRequest)

        Toast.makeText(this, "Sincronizzazione forzata avviata...", Toast.LENGTH_SHORT).show()
    }

    private fun showActiveStatus() {
        statusText.text = "Sincronizzazione automatica attiva (ogni 3 ore).\nPuoi chiudere l'app."
        btnForceSync.visibility = View.VISIBLE
    }
}
