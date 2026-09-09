package coop.ecoopera.aggiornacontatti

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentValues
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
import java.io.IOException

private const val MIMETYPE_SHAREPOINT = "vnd.android.cursor.item/sharepoint_id"

class ImportContactsWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val TAG = "ContactsSync"
    private val url = "https://ticket.ecoopera.coop/contatti"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {

        log("START sync")

        if (!hasPermissions()) return@withContext Result.failure()

        try {
            val vcards = download()
            if (vcards.isEmpty()) {
                log("Nessun contatto scaricato o errore di rete. Esco senza modificare la rubrica.")
                return@withContext Result.success()
            }

            val remoteIds = vcards.mapNotNull {
                it.getExtendedProperty("X-SHAREPOINT-ID")?.value
            }.toSet()

            val localMap = getLocalContacts()

            // DELETE solo i contatti non più presenti da remoto
            val toDelete = localMap.keys - remoteIds
            deleteContacts(toDelete.mapNotNull { localMap[it] })

            val ops = ArrayList<ContentProviderOperation>()

            vcards.forEach { v ->
                val spId = v.getExtendedProperty("X-SHAREPOINT-ID")?.value ?: return@forEach
                val contactId = localMap[spId]

                if (contactId != null) {
                    log("Aggiorno  - ${v.formattedName?.value}")
                    buildUpdateOperations(contactId, v,spId, ops)
                } else {
                    log("Inserisco  - ${v.formattedName?.value}")
                    buildCreateOperations(v, spId, ops)
                }

                // Svuota il batch parzialmente per evitare TransactionTooLargeException
                if (ops.size >= 300) {
                    apply(ops)
                    ops.clear()
                }
            }

            // Applica le ultime operazioni rimaste
            if (ops.isNotEmpty()) {
                apply(ops)
            }

            log("SYNC OK - ${vcards.size}")
            Result.success()

        } catch (e: Exception) {
            log("Errore: ${e.message}")
            Result.retry()
        }
    }

    // -----------------------------------
    private fun download(): List<VCard> {
        val request = Request.Builder().url(url).build()
        return try {
            OkHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Errore HTTP server: ${response.code}")

                val responseBody = response.body
                val bodyString = responseBody?.string()

                if (bodyString.isNullOrBlank()) {
                    emptyList()
                } else {
                    Ezvcard.parse(bodyString).all() ?: emptyList()
                }
            }
        } catch (e: Exception) {
            log("Errore durante il download o parsing: ${e.message}")
            emptyList()
        }
    }

    // -----------------------------------
    private fun getLocalContacts(): Map<String, String> {
        val map = mutableMapOf<String, String>()

        applicationContext.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.DATA1
            ),
            "${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(MIMETYPE_SHAREPOINT),
            null
        )?.use { it ->
            while (it.moveToNext()) {
                val contactId = it.getString(0)
                val spId = it.getString(1)
                if (!contactId.isNullOrBlank() && !spId.isNullOrBlank()) {
                    map[spId] = contactId
                }
            }
        }
        return map
    }

    // -----------------------------------
    private fun getRawContactId(contactId: String): String? {
        applicationContext.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID}=?",
            arrayOf(contactId),
            null
        )?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return null
    }

    // -----------------------------------
    private fun deleteContacts(ids: List<String>) {
        ids.forEach { contactId ->
            applicationContext.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts.CONTACT_ID}=?",
                arrayOf(contactId)
            )
        }
    }

    // -----------------------------------
    private fun buildCreateOperations(v: VCard, spId: String, ops: ArrayList<ContentProviderOperation>) {
        val backRefIndex = ops.size

        // CORREZIONE: Inserito un ContentValues vuoto per evitare il crash interno di Android
        val emptyValues = ContentValues()
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValues(emptyValues)
                .build()
        )

        val name = safe(v.formattedName?.value)
        val email = safe(v.emails?.firstOrNull()?.value)
        val title = safe(v.titles?.firstOrNull()?.value)
        val org = safe(v.organizations?.firstOrNull()?.values?.joinToString(" - "))

        newData(backRefIndex,
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
            name
        )?.let { ops.add(it) }

        newData(backRefIndex,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.ADDRESS,
            email
        )?.let { ops.add(it) }

        if (org != null || title != null) {
            val values = ContentValues().apply {
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                org?.let { put(ContactsContract.CommonDataKinds.Organization.COMPANY, it) }
                title?.let { put(ContactsContract.CommonDataKinds.Organization.TITLE, it) }
            }

            val op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValues(values)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRefIndex)

            ops.add(op.build())
        }

        v.telephoneNumbers?.forEach { tel ->
            val num = safe(tel.text)
            if (num != null) {
                val androidPhoneType = when {
                    tel.types?.contains(ezvcard.parameter.TelephoneType.CELL) == true -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                    tel.types?.contains(ezvcard.parameter.TelephoneType.WORK) == true -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK
                    else -> ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
                }

                val values = ContentValues().apply {
                    put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    put(ContactsContract.CommonDataKinds.Phone.NUMBER, num)
                    put(ContactsContract.CommonDataKinds.Phone.TYPE, androidPhoneType)
                }

                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValues(values)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRefIndex)
                        .build()
                )
            }
        }

        // X-ID
        val xIdValues = ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, MIMETYPE_SHAREPOINT)
            put(ContactsContract.Data.DATA1, spId)
        }

        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValues(xIdValues)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRefIndex)
                .build()
        )
    }

    // -----------------------------------
    private fun buildUpdateOperations(contactId: String, v: VCard, spId: String, ops: ArrayList<ContentProviderOperation>) {
        val rawId = getRawContactId(contactId) ?: return

        val mimetypesToClean = arrayOf(
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            MIMETYPE_SHAREPOINT
        )

        mimetypesToClean.forEach { mime ->
            ops.add(
                ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                    .withSelection(
                        "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                        arrayOf(rawId, mime)
                    )
                    .build()
            )
        }

        val name = safe(v.formattedName?.value)
        val email = safe(v.emails?.firstOrNull()?.value)
        val title = safe(v.titles?.firstOrNull()?.value)
        val org = safe(v.organizations?.firstOrNull()?.values?.joinToString(" - "))

        name?.let {
            val values = ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, it)
            }
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValues(values)
                    .build()
            )
        }

        email?.let {
            val values = ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Email.ADDRESS, it)
            }
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValues(values)
                    .build()
            )
        }

        if (org != null || title != null) {
            val values = ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                org?.let { put(ContactsContract.CommonDataKinds.Organization.COMPANY, it) }
                title?.let { put(ContactsContract.CommonDataKinds.Organization.TITLE, it) }
            }
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValues(values)
                    .build()
            )
        }

        v.telephoneNumbers?.forEach { tel ->
            val num = safe(tel.text)
            if (num != null) {
                val androidPhoneType = when {
                    tel.types?.contains(ezvcard.parameter.TelephoneType.CELL) == true -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                    tel.types?.contains(ezvcard.parameter.TelephoneType.WORK) == true -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK
                    else -> ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
                }

                val values = ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    put(ContactsContract.CommonDataKinds.Phone.NUMBER, num)
                    put(ContactsContract.CommonDataKinds.Phone.TYPE, androidPhoneType)
                }

                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValues(values)
                        .build()
                )
            }
        }

        // RIAGGIUNGI X-ID
        val xIdValues = ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            put(ContactsContract.Data.MIMETYPE, MIMETYPE_SHAREPOINT)
            put(ContactsContract.Data.DATA1, spId)
        }
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValues(xIdValues)
                .build()
        )
    }

    // -----------------------------------
    private fun safe(value: String?): String? {
        if (value.isNullOrBlank() || value == "-") return null
        return value
    }

    private fun newData(
        ref: Int,
        mime: String,
        key: String,
        value: String?
    ): ContentProviderOperation? {

        val safeVal = safe(value) ?: return null

        val values = ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, mime)
            put(key, safeVal)
        }

        return ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValues(values)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, ref)
            .build()
    }

    private fun apply(ops: List<ContentProviderOperation>) {
        if (ops.isEmpty()) return
        try {
            applicationContext.contentResolver.applyBatch(
                ContactsContract.AUTHORITY,
                ArrayList(ops)
            )
        } catch (e: Exception) {
            log("Errore critico durante l'applicazione del batch: ${e.message}")
            throw e // Rilanciamo l'eccezione per attivare il Result.retry() globale di WorkManager
        }
    }

    private fun hasPermissions(): Boolean {
        val readOk = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val writeOk = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
        return readOk && writeOk
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
    }
}
