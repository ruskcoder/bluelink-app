package com.ruskcoder.messages.data.repository

import android.content.Context
import android.provider.ContactsContract
import com.ruskcoder.messages.data.model.Contact
import com.ruskcoder.messages.data.model.colorFor
import com.ruskcoder.messages.data.model.initialsFor
import com.ruskcoder.messages.imessage.Handles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the device address book. Ported from `context/contacts.tsx`'s `expo-contacts` wrapper. */
class ContactsRepository(private val context: Context) {
    suspend fun loadContacts(): List<Contact> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val seen = LinkedHashMap<String, Contact>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            // Both sizes: the thumbnail is what a 40dp row wants, and the full-size one is what the
            // contact page's 140dp portrait needs. A contact can have the thumbnail without the
            // other, so neither alone is a reliable "has a photo" answer.
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
        )
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val thumbIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)
            val photoIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex) ?: continue
                // The same raw contact can produce multiple phone-number rows; keep the first.
                if (seen.containsKey(id)) continue
                val name = cursor.getString(nameIndex)?.takeIf { it.isNotBlank() } ?: continue
                val number = cursor.getString(numberIndex)
                // Full-size first: Coil downsamples to whatever the target is, so the larger source
                // serves both the row and the portrait, and the thumbnail is only the fallback for
                // a contact that has no full-size photo stored.
                val photo = cursor.getString(photoIndex)?.takeIf { it.isNotBlank() }
                    ?: cursor.getString(thumbIndex)?.takeIf { it.isNotBlank() }
                seen[id] = Contact(
                    id = id,
                    name = name,
                    about = number,
                    color = colorFor(name),
                    initials = initialsFor(name),
                    photoUri = photo,
                )
            }
        }
        seen.values.toList()
    }

    /**
     * The address-book entry for an Apple handle, or null when nothing matches.
     *
     * Takes an already-loaded address book rather than querying, because the callers that need this
     * — naming a conversation and its avatar, and resolving a group's roster — are already holding
     * one, and this is called once per conversation on the refresh path.
     */
    fun contactForHandle(handle: String, addressBook: List<Contact>): Contact? =
        addressBook.firstOrNull { Handles.matchesNumber(handle, it.about) }
}
