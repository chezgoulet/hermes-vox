package com.hermesvox

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * ContactsAccess — the Android edge of `contact`: `ContactsContract` and nothing else.
 *
 * **The projection is the scope.** A lookup returns a display name and the numbers the owner
 * labelled; it never projects a note, a postal address, a photo or an email. The group exists to
 * "look up a contact's number by name", so that is the whole read rather than a wider one with
 * fields hidden afterwards. The same rule carries into `message`/`dial`, which use the first
 * number of the first match and nothing else.
 *
 * **The match is a partial name, case-insensitive.** [PhoneEngine] bounds the query and the result
 * at `PhoneTool.MAX_CONTACT_MATCHES`; this object orders by display name and groups a person's
 * numbers under one row. `%` and `_` in the caller's name are escaped so a name is a name, not a
 * pattern the caller did not ask for.
 *
 * **The permission is named exactly.** [permission] returns Android's own
 * `android.permission.READ_CONTACTS`, and the engine puts that string in a `permission` refusal.
 * The owner grants it from the Phone tools sheet when Contacts is turned on; it can be revoked at
 * any moment, and the next call says so.
 */
object ContactsAccess {

    /** The one runtime permission a lookup needs. */
    const val PERMISSION: String = Manifest.permission.READ_CONTACTS

    /** How many numbers one person may contribute, so a pathological contact cannot flood a page. */
    const val MAX_NUMBERS_PER_CONTACT = 10

    /** True when Android has granted contacts access to this app for this install. */
    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** The exact permission the app lacks, or null when it holds it. */
    fun permission(context: Context): String? = if (granted(context)) null else PERMISSION

    /**
     * Up to [limit] people whose display name contains [name], case-insensitively. Null means the
     * provider failed; an empty list is the true answer "nobody by that name".
     */
    fun matches(
        context: Context,
        name: String,
        limit: Int,
    ): List<PhoneEngine.PhonePlatform.ContactMatch>? {
        if (permission(context) != null) return emptyList()
        val query = name.trim()
        if (query.isEmpty() || limit < 1) return emptyList()
        return try {
            val escaped = query
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? ESCAPE '\\'"
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL,
            )
            val byId = LinkedHashMap<Long, MutableList<PhoneEngine.PhonePlatform.ContactNumber>>()
            val names = HashMap<Long, String>()
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                arrayOf("%$escaped%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val display = c.getString(1)?.trim().orEmpty()
                    val number = c.getString(2)?.trim().orEmpty()
                    if (display.isEmpty() || number.isEmpty()) continue
                    // The result is bounded by people, not rows: the first `limit` names are the
                    // answer, and a person already in that page keeps adding their numbers.
                    if (id !in names && names.size >= limit) continue
                    names.putIfAbsent(id, display)
                    val list = byId.getOrPut(id) { ArrayList() }
                    if (list.size < MAX_NUMBERS_PER_CONTACT) {
                        list.add(
                            PhoneEngine.PhonePlatform.ContactNumber(
                                label = label(c.getInt(3), if (c.isNull(4)) null else c.getString(4)),
                                number = number,
                            ),
                        )
                    }
                }
            }
            names.entries.map { (id, display) ->
                PhoneEngine.PhonePlatform.ContactMatch(
                    displayName = display,
                    numbers = byId[id].orEmpty(),
                )
            }
        } catch (t: Throwable) {
            VoxLog.w("event=phone-contacts read-failed reason=${t.javaClass.simpleName}")
            null
        }
    }

    /** The first number of the first match, for `message`/`dial`. Null is "no such contact". */
    fun firstNumber(context: Context, name: String): String? =
        matches(context, name, 1)?.firstOrNull()?.numbers?.firstOrNull()?.number

    /**
     * A number's label in the owner's words. The common types are named as Android names them; a
     * custom label is the owner's own word; anything else is `other` rather than an invented name.
     */
    fun label(type: Int, custom: String?): String = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME,
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK,
        ContactsContract.CommonDataKinds.Phone.TYPE_OTHER_FAX -> "fax"
        ContactsContract.CommonDataKinds.Phone.TYPE_PAGER -> "pager"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE -> "work mobile"
        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "main"
        ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM ->
            custom?.trim()?.takeIf { it.isNotEmpty() } ?: "other"
        else -> "other"
    }
}
