package coop.ecoopera.importarubrica

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.parameter.TelephoneType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

class ImportContactsWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val urlString = "https://ticket.ecoopera.coop/contatti"
    private val SHAREPOINT_ID_MIME_TYPE = "vnd.android.cursor.item/sharepoint_id"
    private val MIN_CONTACT_THRESHOLD = 5

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d("Worker", "Inizio sincronizzazione completa con telefoni...") ;

            val vcfContent = URL(urlString).readText() ;
            val vcards = Ezvcard.parse(vcfContent).all() ;

            if (vcards.size < MIN_CONTACT_THRESHOLD) {
                Log.e("Worker", "Sincronizzazione annullata: dati insufficienti.") ;
                return@withContext Result.failure() ;
            }

            val remoteIds = vcards.mapNotNull { it.getExtendedProperty("X-SHAREPOINT-ID")?.value }.toSet() ;
            val localContactsMap = getAllLocalSharepointContacts() ;

            // 1. Pulizia obsoleti
            val idsToDelete = localContactsMap.keys - remoteIds ;
            if (idsToDelete.isNotEmpty()) {
                deleteContacts(idsToDelete.mapNotNull { localContactsMap[it] }) ;
            }

            // 2. Elaborazione (Create/Update)
            for (vcard in vcards) {
                val sharepointId = vcard.getExtendedProperty("X-SHAREPOINT-ID")?.value ?: continue ;
                val contactId = localContactsMap[sharepointId] ;

                if (contactId != null) {
                    updateContact(contactId, vcard) ;
                } else {
                    createNewContact(vcard, sharepointId) ;
                }
            }

            Result.success() ;
        } catch (e: Exception) {
            Log.e("Worker", "Errore: ${e.message}") ;
            Result.retry() ;
        }
    }

    private fun getAllLocalSharepointContacts(): Map<String, String> {
        val contactsMap = mutableMapOf<String, String>() ;
        val uri = ContactsContract.Data.CONTENT_URI ;
        val projection = arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DATA1) ;
        val selection = "${ContactsContract.Data.MIMETYPE} = ?" ;
        val selectionArgs = arrayOf(SHAREPOINT_ID_MIME_TYPE) ;

        applicationContext.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID) ;
            val spCol = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1) ;
            while (cursor.moveToNext()) {
                val contactId = cursor.getString(idCol) ;
                val sharepointId = cursor.getString(spCol) ?: continue ;
                contactsMap[sharepointId] = contactId ;
            }
        }
        return contactsMap ;
    }

    private fun deleteContacts(contactIds: List<String>) {
        val ops = ArrayList<ContentProviderOperation>() ;
        contactIds.distinct().forEach { id ->
            ops.add(ContentProviderOperation.newDelete(ContactsContract.RawContacts.CONTENT_URI)
                .withSelection("${ContactsContract.RawContacts.CONTACT_ID} = ?", arrayOf(id))
                .build()) ;
        }
        if (ops.isNotEmpty()) applicationContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops) ;
    }

    private fun createNewContact(vcard: VCard, sharepointId: String) {
        val ops = ArrayList<ContentProviderOperation>() ;

        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build()) ;

        // Nome
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, vcard.formattedName?.value ?: "")
            .build()) ;

        // SharePoint ID
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, SHAREPOINT_ID_MIME_TYPE)
            .withValue(ContactsContract.Data.DATA1, sharepointId)
            .build()) ;

        // Azienda e Job Title
        val company = vcard.organization?.values?.joinToString("; ") ?: "" ;
        val jobTitle = vcard.titles?.firstOrNull()?.value ?: "" ;
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, company)
            .withValue(ContactsContract.CommonDataKinds.Organization.TITLE, jobTitle)
            .withValue(ContactsContract.CommonDataKinds.Organization.TYPE, ContactsContract.CommonDataKinds.Organization.TYPE_WORK)
            .build()) ;

        // Email
        vcard.emails.firstOrNull()?.let { email ->
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email.value)
                .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_WORK)
                .build()) ;
        }

        // Telefoni (Filtrando il carattere "-")
        vcard.telephoneNumbers.forEach { tel ->
            val number = tel.text ;
            if (!number.isNullOrBlank() && number != "-") {
                val type = if (tel.types.contains(TelephoneType.CELL)) {
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                } else {
                    ContactsContract.CommonDataKinds.Phone.TYPE_WORK
                }

                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, type)
                    .build()) ;
            }
        }

        // Località (ADR)
        vcard.addresses?.firstOrNull()?.streetAddress?.let { loc ->
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredPostal.STREET, loc)
                .withValue(ContactsContract.CommonDataKinds.StructuredPostal.TYPE, ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK)
                .build()) ;
        }

        applicationContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops) ;
    }

    private fun updateContact(contactId: String, vcard: VCard) {
        val ops = ArrayList<ContentProviderOperation>() ;

        // Aggiorna Nome
        vcard.formattedName?.value?.let { newName ->
            ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(contactId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE))
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, newName)
                .build()) ;
        }

        // Aggiorna Azienda/Titolo
        val company = vcard.organization?.values?.joinToString("; ") ?: "" ;
        val jobTitle = vcard.titles?.firstOrNull()?.value ?: "" ;
        ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
            .withSelection("${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE))
            .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, company)
            .withValue(ContactsContract.CommonDataKinds.Organization.TITLE, jobTitle)
            .build()) ;

        // Nota: Aggiornare Email e Telefoni esistenti in un update è complesso perché potrebbero essercene multipli.
        // In una sincronizzazione "mirror", spesso conviene eliminare i Data vecchi di quel contatto e reinserirli,
        // ma per semplicità qui aggiorniamo la prima email trovata.
        vcard.emails.firstOrNull()?.let { email ->
            ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(contactId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE))
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email.value)
                .build()) ;
        }

        if (ops.isNotEmpty()) applicationContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops) ;
    }
}
