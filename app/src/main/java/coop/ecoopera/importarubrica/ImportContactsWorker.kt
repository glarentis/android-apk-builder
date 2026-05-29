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
import java.io.IOException

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
                    buildUpdateOperations(contactId, v, ops)
                } else {
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
        OkHttpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Errore HTTP server: ${response.code}")
            val body = response.body?.string() ?: return emptyList()
            return Ezvcard.parse(body).all()
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
            arrayOf("vnd.android.cursor.item/sharepoint_id"),
            null
        )?.use {
            while (it.moveToNext()) {
                map[it.getString(1)] = it.getString(0)
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
        // Indice di riferimento all'interno del batch corrente
        val backRefIndex = ops.size

        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI).build())

        val name = safe(v.formattedName?.value)
        val email = safe(v.emails.firstOrNull()?.value)
        val title = safe(v.titles.firstOrNull()?.value)
        val org = safe(v.organizations.firstOrNull()?.values?.joinToString(" - "))

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
            val op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRefIndex)
                .withValue(ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)

            org?.let {
                op.withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, it)
            }

            title?.let {
                op.withValue(ContactsContract.CommonDataKinds.Organization.TITLE, it)
            }

            ops.add(op.build())
        }

        v.telephoneNumbers.forEach { tel ->
            val num = safe(tel.text)
            if (num != null) {
                val androidPhoneType = when {
                    tel.types.contains(ezvcard.parameter.TelephoneType.CELL) -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                    tel.types.contains(ezvcard.parameter.TelephoneType.WORK) -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK
                    else -> ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
                }

                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRefIndex)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, num)
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, androidPhoneType)
                        .build()
                )
            }
        }

        // X-ID
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRefIndex)
                .withValue(ContactsContract.Data.MIMETYPE, "vnd.android.cursor.item/sharepoint_id")
                .withValue(ContactsContract.Data.DATA1, spId)
                .build()
        )
    }

    // -----------------------------------
    private fun buildUpdateOperations(contactId: String, v: VCard, ops: ArrayList<ContentProviderOperation>) {
        val rawId = getRawContactId(contactId) ?: return

        // Cancella selettivamente SOLO i dati gestiti dall'app per preservare modifiche locali dell'utente
        val mimetypesToClean = arrayOf(
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            "vnd.android.cursor.item/sharepoint_id"
        )

        mimetypesToClean.forEach { mime ->
            ops.add(
                ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                    .withSelection(
                        "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                        arrayOf(rawId, mime)
                    ).build()
            )
        }

        val name = safe(v.formattedName?.value)
        val email = safe(v.emails.firstOrNull()?.value)
        val title = safe(v.titles.firstOrNull()?.value)
        val org = safe(v.organizations.firstOrNull()?.values?.joinToString(" - "))

        name?.let {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    .withValue(ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, it)
                    .build()
            )
        }

        email?.let {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    .withValue(ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, it)
                    .build()
            )
        }

        if (org != null || title != null) {
            val op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)

            org?.let {
                op.withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, it)
            }

            title?.let {
                op.withValue(ContactsContract.CommonDataKinds.Organization.TITLE, it)
            }

            ops.add(op.build())
        }

        v.telephoneNumbers.forEach { tel ->
            val num = safe(tel.text)
            if (num != null) {
                val androidPhoneType = when {
                    tel.types.contains(ezvcard.parameter.TelephoneType.CELL) -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                    tel.types.contains(ezvcard.parameter.TelephoneType.WORK) -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK
                    else -> ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
                }

                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, num)
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, androidPhoneType)
                        .build()
                )
            }
        }

        // RIAGGIUNGI X-ID
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, "vnd.android.cursor.item/sharepoint_id")
                .withValue(ContactsContract.Data.DATA1, v.getExtendedProperty("X-SHAREPOINT-ID")?.value)
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

        return ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, ref)
            .withValue(ContactsContract.Data.MIMETYPE, mime)
            .withValue(key, safeVal)
            .build()
    }

    private fun apply(ops: List<ContentProviderOperation>) {
        applicationContext.contentResolver.applyBatch(
            ContactsContract.AUTHORITY,
            ArrayList(ops)
        )
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
