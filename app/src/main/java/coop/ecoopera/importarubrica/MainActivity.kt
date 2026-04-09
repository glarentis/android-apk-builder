package coop.ecoopera.importarubrica

import android.Manifest
import android.app.DownloadManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ezvcard.Ezvcard
import ezvcard.VCard
import java.io.File

class MainActivity : AppCompatActivity() {

    private var downloadId: Long = -1
    private val fileName = "contatti_scaricati.vcf"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Verifichiamo i permessi prima di iniziare
        if (checkPermissions()) {
            startDownloadProcess()
        } else {
            requestPermissions()
        }
    }

    private fun startDownloadProcess() {
        val url = "https://ticket.ecoopera.coop/contatti"
        registerReceiver(onDownloadComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED)
        startDownload(url)
    }

    private fun startDownload(url: String) {
        val file = File(externalCacheDir, fileName)
        if (file.exists()) file.delete()

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("Aggiornamento Rubrica")
            setDestinationUri(Uri.fromFile(file))
        }

        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = manager.enqueue(request)
    }

    private val onDownloadComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1
            if (downloadId == id) {
                processDownloadedVcf()
            }
        }
    }

    private fun processDownloadedVcf() {
        val file = File(externalCacheDir, fileName)
        if (!file.exists()) return

        try {
            val vcards = Ezvcard.parse(file).all()
            for (vcard in vcards) {
                val name = vcard.formattedName?.value ?: continue
                val existingId = findContactIdByFn(name)

                if (existingId != null) {
                    updateContact(existingId, vcard)
                } else {
                    createNewContact(vcard)
                }
            }
            Toast.makeText(this, "Rubrica aggiornata con successo", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Errore durante l'importazione", Toast.LENGTH_SHORT).show()
        }
    }

    // Cerca un contatto esistente tramite il campo FN (Full Name)
    private fun findContactIdByFn(fn: String): String? {
        val uri = ContactsContract.Contacts.CONTENT_URI
        val projection = arrayOf(ContactsContract.Contacts._ID)
        val selection = "${ContactsContract.Contacts.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fn)

        contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
            }
        }
        return null
    }

    private fun createNewContact(vcard: VCard) {
        val ops = ArrayList<ContentProviderOperation>()

        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build())

        // Nome (FN)
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, vcard.formattedName.value)
            .build())

        // Email
        vcard.emails.firstOrNull()?.let { email ->
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email.value)
                .build())
        }

        contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    private fun updateContact(contactId: String, vcard: VCard) {
        val ops = ArrayList<ContentProviderOperation>()

        // Esempio: Aggiorna l'email per quel contactId specifico
        vcard.emails.firstOrNull()?.let { email ->
            ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection(
                    "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(contactId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                )
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email.value)
                .build())
        }

        // Puoi aggiungere qui aggiornamenti per TEL, ORG, etc.
        contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    // Gestione Permessi Runtime
    private fun checkPermissions() = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS), 100)
    }
}
