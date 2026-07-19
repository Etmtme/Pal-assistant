package com.example

import android.content.Context
import android.provider.ContactsContract
import android.util.Log

object ContactHelper {

    /**
     * Searches for a contact's phone number by name.
     */
    fun getPhoneNumberByName(context: Context, name: String): String? {
        val contentResolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )
        
        // Match name with standard SQL LIKE query
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")

        try {
            contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numberIndex >= 0) {
                        return cursor.getString(numberIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ContactHelper", "Error querying contacts: ${e.message}")
        }
        return null
    }

    /**
     * Lists contacts (e.g. up to 10) for displaying.
     */
    fun listContacts(context: Context): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        val contentResolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        try {
            contentResolver.query(uri, projection, null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                
                var count = 0
                while (cursor.moveToNext() && count < 10) {
                    if (nameIndex >= 0 && numberIndex >= 0) {
                        val name = cursor.getString(nameIndex)
                        val num = cursor.getString(numberIndex)
                        list.add(Pair(name, num))
                        count++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ContactHelper", "Error listing contacts: ${e.message}")
        }
        return list
    }
}
