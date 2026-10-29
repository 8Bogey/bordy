package com.bordy.app.utils

import android.content.Context
import android.content.Intent
import android.net.Uri

fun Context.persistReadPermission(uri: Uri) {
    try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: SecurityException) {
    }
}
