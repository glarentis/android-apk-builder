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

            // ✅ DELETE (solo i tuoi)
            val toDelete = localMap.keys - remoteIds
            deleteContacts(toDelete.mapNotNull { localMap[it] })

            vcards.forEach { v ->
                val spId = v.getExtendedProperty("X-SHAREPOINT-ID")?.value ?: return@forEach

                val contactId = localMap[spId]

                if (contactId != null) {
                    updateContact(contactId, v)
                } else {
                    createContact(v, spId)
                }
            }

            log("SYNC OK - ${vcards.size}")
            Result.success()

        } catch (e: Exception) {
            log("Errore: ${e.message}")
            Result.retry()
        }
    }

    // -----------------------------------
    // 🌐 DOWNLOAD
    // -----------------------------------
    private fun download(): List<VCard> {
        val response = OkHttpClient().newCall(Request.Builder().url(url).build()).execute()
        val body = response.body?.string() ?: return emptyList()
        return Ezvcard.parse(body).all()
    }

    // -----------------------------------
    // 📇 LETTURA CONTATTI LOCALI (X-ID)
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
    // ❌ DELETE
    // -----------------------------------
    private fun deleteContacts(ids: List<String>) {
        ids.forEach {
            applicationContext.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts.CONTACT_ID}=?",
                arrayOf(it)
            )
        }
    }

    // -----------------------------------
    // ➕ CREATE
    // -----------------------------------
    private fun createContact(v: VCard, spId: String) {
        val ops = ArrayList<ContentProviderOperation>()

        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI).build())

        val name = v.formattedName?.value
        val email = v.emails.firstOrNull()?.value
        val title = v.titles.firstOrNull()?.value
        val org = v.organizations.firstOrNull()?.values?.joinToString(" - ")

        newData(0,
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
            name
        )?.let { ops.add(it) }

        newData(0,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.ADDRESS,
            email
        )?.let { ops.add(it) }

        newData(0,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.COMPANY,
            org
        )?.let { ops.add(it) }

        newData(0,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.TITLE,
            title
        )?.let { ops.add(it) }

        // ✅ Telefoni
        v.telephoneNumbers.forEach {
            val num = it.text
            if (!num.isNullOrBlank() && num != "-") {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, num)
                        .build()
                )
            }
        }

        // ✅ X-ID
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, "vnd.android.cursor.item/sharepoint_id")
                .withValue(ContactsContract.Data.DATA1, spId)
                .build()
        )

        apply(ops)
    }

    // -----------------------------------
    // 🔄 UPDATE
    // -----------------------------------
    private fun updateContact(contactId: String, v: VCard) {

        val ops = mutableListOf<ContentProviderOperation>()

        // elimina dati dinamici
        ops.add(
            ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(
                    "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE} IN (?,?,?,?)",
                    arrayOf(
                        contactId,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                    )
                ).build()
        )

        val name = v.formattedName?.value
        val email = v.emails.firstOrNull()?.value
        val title = v.titles.firstOrNull()?.value
        val org = v.organizations.firstOrNull()?.values?.joinToString(" - ")

        if (!name.isNullOrBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.CONTACT_ID, contactId)
                    .withValue(ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build()
            )
        }

        if (!email.isNullOrBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.CONTACT_ID, contactId)
                    .withValue(ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                    .build()
            )
        }

        if (!org.isNullOrBlank() || !title.isNullOrBlank()) {
            val op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.CONTACT_ID, contactId)
                .withValue(ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)

            if (!org.isNullOrBlank())
                op.withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, org)

            if (!title.isNullOrBlank())
                op.withValue(ContactsContract.CommonDataKinds.Organization.TITLE, title)

            ops.add(op.build())
        }

        v.telephoneNumbers.forEach {
            val num = it.text
            if (!num.isNullOrBlank() && num != "-") {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValue(ContactsContract.Data.CONTACT_ID, contactId)
                        .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, num)
                        .build()
                )
            }
        }

        apply(ops)
    }

    // -----------------------------------
    // ✅ SAFE DATA
    // -----------------------------------
    private fun newData(
        ref: Int,
        mime: String,
        key: String,
        value: String?
    ): ContentProviderOperation? {

        if (value.isNullOrBlank() || value == "-") return null

        return ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, ref)
            .withValue(ContactsContract.Data.MIMETYPE, mime)
            .withValue(key, value)
            .build()
    }

    private fun apply(ops: List<ContentProviderOperation>) {
        applicationContext.contentResolver.applyBatch(
            ContactsContract.AUTHORITY,
            ArrayList(ops)
        )
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.WRITE_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
    }
}
