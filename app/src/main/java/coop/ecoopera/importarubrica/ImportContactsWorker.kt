package coop.ecoopera.importarubrica

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ezvcard.Ezvcard
import ezvcard.VCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ImportContactsWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val TAG = "ContactsSync"
    private val url = "https://ticket.ecoopera.coop/contatti"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {

        log("START sync")

        // ✅ Controllo permessi
        if (!hasPermissions()) {
            log("Permessi mancanti")
            return@withContext Result.failure()
        }

        try {
            // ✅ Download vCard
            val vcards = downloadContacts()

            if (vcards.isEmpty()) {
                log("Nessun contatto ricevuto")
                return@withContext Result.retry()
            }

            // ✅ Cancella contatti precedenti gestiti dall’app
            clearAllContacts()

            // ✅ Inserisci nuovi contatti
            vcards.forEach {
                createContact(it)
            }

            log("SYNC OK - ${vcards.size} contatti")
            Result.success()

        } catch (e: Exception) {
            log("Errore: ${e.message}")
            Result.retry()
        }
    }

    // -----------------------------------
    // 🌐 DOWNLOAD CONTATTI
    // -----------------------------------
    private fun downloadContacts(): List<VCard> {

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("Errore HTTP ${response.code}")
        }

        val body = response.body?.string() ?: throw Exception("Risposta vuota")

        return Ezvcard.parse(body).all()
    }

    // -----------------------------------
    // 📇 CREA CONTATTO
    // -----------------------------------
    private fun createContact(vcard: VCard) {

        val ops = ArrayList<ContentProviderOperation>()

        // ✅ Raw contact
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE,null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME,null)
                .build()
        )

        // ✅ Nome
        val name = vcard.formattedName?.value ?: ""
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                )
                .withValue(
                    ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                    name
                )
                .build()
        )

        // ✅ Telefono
        vcard.telephoneNumbers.forEach {
            val number = it.text
            if (!number.isNullOrBlank()) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                        )
                        .withValue(
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            number
                        )
                        .withValue(
                            ContactsContract.CommonDataKinds.Phone.TYPE,
                            ContactsContract.CommonDataKinds.Phone.TYPE_WORK
                        )
                        .build()
                )
            }
        }

        // ✅ Email
        vcard.emails.firstOrNull()?.let {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
                    )
                    .withValue(
                        ContactsContract.CommonDataKinds.Email.ADDRESS,
                        it.value
                    )
                    .build()
            )
        }

        applyBatchSafe(ops)
    }

    // -----------------------------------
    // 🧹 PULIZIA
    // -----------------------------------
    private fun clearAllContacts() {
        try {
            applicationContext.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                null,
                null
            )
            log("Contatti cancellati")
        } catch (e: Exception) {
            log("Errore cancellazione: ${e.message}")
        }
    }

    // -----------------------------------
    // ✅ BATCH SAFE
    // -----------------------------------
    private fun applyBatchSafe(ops: List<ContentProviderOperation>) {
        ops.chunked(100).forEach {
            applicationContext.contentResolver.applyBatch(
                ContactsContract.AUTHORITY,
                ArrayList(it)
            )
        }
    }

    // -----------------------------------
    // 🔐 PERMESSI
    // -----------------------------------
    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.WRITE_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    // -----------------------------------
    // 🪵 LOG
    // -----------------------------------
    private fun log(msg: String) {
        Log.d(TAG, msg)
    }
}
