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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val url = "https://ticket.ecoopera.coop/contatti"
        startDownload(url)

        // Registra il ricevitore per intercettare la fine del download
        registerReceiver(onDownloadComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    private fun startDownload(url: String) {
        // Salviamo il file nella cache esterna dell'app
        val file = File(externalCacheDir, "contatti_scaricati.vcf")

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Download Documento")
            .setDescription("Scaricamento in corso...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(file))

        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = manager.enqueue(request)
    }

    private val onDownloadComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1
            if (downloadId == id) {
                openDownloadedFile()
            }
        }
    }

    private fun openDownloadedFile() {
        val file = File(externalCacheDir, "contatti_scaricati.vcf")

        if (!file.exists()) {
            Toast.makeText(this, "File non trovato", Toast.LENGTH_SHORT).show()
            return
        }

        // Ottieni l'URI sicuro tramite FileProvider
        val contentUri = FileProvider.getUriForFile(
            this,
            "$packageName.provider",
            file
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, contentResolver.getType(contentUri))
            // Se conosci il tipo (es. PDF), puoi forzarlo: setDataAndType(contentUri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Nessuna app disponibile per aprire questo file", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(onDownloadComplete)
        } catch (e: Exception) {
            // Ignora se già rimosso
        }
    }
}
