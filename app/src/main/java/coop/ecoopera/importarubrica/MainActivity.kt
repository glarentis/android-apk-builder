package coop.ecoopera.importarubrica

import android.app.DownloadManager
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    private var downloadId: Long = -1
    private val fileName = "contatti_scaricati.vcf"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val url = "https://ticket.ecoopera.coop/contatti"

        // Registra il receiver prima di far partire il download
        registerReceiver(onDownloadComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED)

        startDownload(url)
    }

    private fun startDownload(url: String) {
        val file = File(externalCacheDir, fileName)

        // Se il file esiste già, lo cancelliamo per evitare conflitti
        if (file.exists()) file.delete()

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("Download Rubrica")
            setDescription("Aggiornamento contatti in corso...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // Utilizziamo l'URI del file nella cache esterna
            setDestinationUri(Uri.fromFile(file))
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }

        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = manager.enqueue(request)
    }

    private val onDownloadComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1
            if (downloadId == id && id != -1L) {
                openDownloadedFile()
            }
        }
    }

    private fun openDownloadedFile() {
        val file = File(externalCacheDir, fileName)

        if (!file.exists()) {
            Toast.makeText(this, "Errore: file non scaricato", Toast.LENGTH_SHORT).show()
            return
        }

        val contentUri = FileProvider.getUriForFile(
            this,
            "$packageName.provider",
            file
        )

        // "text/x-vcard" è lo standard per i file .vcf
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "text/x-vcard")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Nessuna app per gestire i contatti (VCF)", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Pulizia sicura del receiver
        try {
            unregisterReceiver(onDownloadComplete)
        } catch (e: IllegalArgumentException) {
            // Già rimosso
        }
    }
}
