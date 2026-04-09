package coop.ecoopera.importarubrica

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ezvcard.Ezvcard
import ezvcard.VCard
import java.net.URL

class ImportContactsWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val urlString = "https://ticket.ecoopera.coop/contatti"

    override suspend fun doWork(): Result {
        return try {
            Log.d("Worker", "Inizio sincronizzazione ogni 3 ore...")

            // 1. Download del contenuto VCF come stringa o stream
            val vcfContent = URL(urlString).readText()

            // 2. Parsing con Ezvcard
            val vcards = Ezvcard.parse(vcfContent).all()

            // 3. Elaborazione contatti
            for (vcard in vcards) {
                val name = vcard.formattedName?.value ?: continue
                val contactId = findContactIdByFn(name)

                if (contactId != null) {
                    updateContact(contactId, vcard)
                } else {
                    createNewContact(vcard)
                }
            }

            Log.d("Worker", "Sincronizzazione completata con successo")
            Result.success()
        } catch (e: Exception) {
            Log.e("Worker", "Errore durante il download/importazione: ${e.message}")
            Result.retry() // Riprova se c'è un errore di rete
        }
    }

    private fun findContactIdByFn(fn: String): String? {
        val uri = ContactsContract.Contacts.CONTENT_URI
        val projection = arrayOf(ContactsContract.Contacts._ID)
        val selection = "${ContactsContract.Contacts.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fn)

        applicationContext.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
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

        // Nome
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

        applicationContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    private fun updateContact(contactId: String, vcard: VCard) {
        val ops = ArrayList<ContentProviderOperation>()
        vcard.emails.firstOrNull()?.let { email ->
            ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection(
                    "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(contactId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                )
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email.value)
                .build())
        }
        applicationContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }
}
