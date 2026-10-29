package com.bordy.app.utils

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.bordy.app.data.MediaImage
import com.bordy.app.data.MediaVideo
import java.io.File

// تخزين مرفقات البورد داخل ملفات التطبيق الخاصة: لا تعتمد على إذن SAF وتبقى بعد إغلاق التطبيق
object BoardMediaStore {

    private val videoExtensions = setOf("mp4", "3gp", "mkv", "mov", "webm", "avi", "m4v")

    private fun dirFor(context: Context, projectId: Long): File =
        File(context.filesDir, "board_media/$projectId").apply { mkdirs() }

    fun kindOf(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") {
            return if (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() in videoExtensions) MediaVideo else MediaImage
        }
        val mime = context.contentResolver.getType(uri) ?: ""
        return when {
            mime.startsWith("video") -> MediaVideo
            mime.startsWith("image") -> MediaImage
            else -> when (extensionOf(context, uri)) {
                in videoExtensions -> MediaVideo
                else -> null
            }
        }
    }

    private fun extensionOf(context: Context, uri: Uri): String {
        val fromPath = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (fromPath.isNotEmpty() && fromPath.length <= 5) return fromPath
        val mime = context.contentResolver.getType(uri) ?: return ""
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime).orEmpty().lowercase()
    }

    // ينسخ الملف إلى مجلد البورد ويُرجي مساره الجديد — null إذا تعذّر النسخ
    fun import(context: Context, projectId: Long, uri: Uri, kind: String): File? {
        if (uri.scheme == "file") {
            val source = File(uri.path ?: return null)
            return if (source.exists()) source else null
        }
        val input = context.contentResolver.openInputStream(uri) ?: return null
        val ext = if (kind == MediaVideo) "mp4" else "jpg"
        val target = File(dirFor(context, projectId), "${System.currentTimeMillis()}.$ext")
        input.use { source -> target.outputStream().use { dest -> source.copyTo(dest, 64 * 1024) } }
        return if (target.exists()) target else null
    }

    fun deleteFile(context: Context, uriString: String) {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "file") return
        val path = uri.path ?: return
        val file = File(path)
        if (!file.exists()) return
        val ownedRoot = File(context.filesDir, "board_media").canonicalPath
        val owned = runCatching { file.canonicalPath.startsWith(ownedRoot) }.getOrDefault(false)
        if (owned) file.delete()
    }

    // المجلد كله عند حذف البورد — صفوف القاعدة تحذفها العلاقة المتسلسلة
    fun deleteProjectFolder(context: Context, projectId: Long) {
        runCatching { File(context.filesDir, "board_media/$projectId").deleteRecursively() }
    }
}
