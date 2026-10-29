package com.bordy.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bordy.app.utils.SellGreen
import com.bordy.app.utils.persistReadPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * منتقي صور يفتح معرض النظام ثم يعرض شاشة الاقتصاص قبل إرجاع مسار الصورة النهائية.
 */
@Composable
fun rememberImageCropPicker(onPicked: (String) -> Unit): ActivityResultLauncher<Array<String>> {
    val context = LocalContext.current
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            context.persistReadPermission(uri)
            pendingUri = uri
        }
    }
    pendingUri?.let { uri ->
        ImageCropDialog(
            sourceUri = uri,
            onDismiss = { pendingUri = null },
            onConfirm = { savedPath ->
                pendingUri = null
                onPicked(savedPath)
            }
        )
    }
    return launcher
}

@Composable
fun ImageCropDialog(
    sourceUri: Uri,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(sourceUri) {
        val decoded = withContext(Dispatchers.IO) { decodeForCrop(context, sourceUri) }
        bitmap = decoded
        loadFailed = decoded == null
        offset = Offset.Zero
        zoom = 1f
    }

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = DialogShape,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(18.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "اقتصاص الصورة",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    TintedCircleButton(
                        onClick = { if (!saving) onDismiss() },
                        icon = Icons.Default.Close,
                        contentDescription = "إغلاق",
                        tint = MaterialTheme.colorScheme.surfaceVariant,
                        size = 34.dp,
                        iconSize = 17.dp
                    )
                }

                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val side = minOf(maxHeight.coerceAtMost(340.dp), maxWidth)
                    Box(
                        modifier = Modifier
                            .size(side)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black)
                            .onGloballyPositioned { viewport = it.size }
                            .pointerInput(bitmap) {
                                detectTransformGestures { _, pan, gestureZoom, _ ->
                                    val bmp = bitmap ?: return@detectTransformGestures
                                    zoom = (zoom * gestureZoom).coerceIn(1f, 5f)
                                    offset = clampOffset(offset + pan, viewport, bmp, zoom)
                                }
                            }
                    ) {
                        val bmp = bitmap
                        if (bmp != null) {
                            Canvas(Modifier.fillMaxSize()) {
                                val scale = max(size.width / bmp.width, size.height / bmp.height) * zoom
                                val drawWidth = bmp.width * scale
                                val drawHeight = bmp.height * scale
                                drawImage(
                                    image = bmp.asImageBitmap(),
                                    dstOffset = IntOffset(
                                        ((size.width - drawWidth) / 2f + offset.x).roundToInt(),
                                        ((size.height - drawHeight) / 2f + offset.y).roundToInt()
                                    ),
                                    dstSize = IntSize(drawWidth.roundToInt(), drawHeight.roundToInt())
                                )
                                val lineColor = Color.White.copy(alpha = 0.35f)
                                val strokePx = 1.dp.toPx()
                                drawLine(lineColor, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), strokePx)
                                drawLine(lineColor, Offset(size.width * 2f / 3f, 0f), Offset(size.width * 2f / 3f, size.height), strokePx)
                                drawLine(lineColor, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), strokePx)
                                drawLine(lineColor, Offset(0f, size.height * 2f / 3f), Offset(size.width, size.height * 2f / 3f), strokePx)
                                drawRect(Color.White.copy(alpha = 0.75f), style = Stroke(1.5.dp.toPx()))
                            }
                        } else if (loadFailed) {
                            Text(
                                "تعذّر تحميل الصورة",
                                color = Color.White,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        } else {
                            CircularProgressIndicator(
                                color = SellGreen,
                                strokeWidth = 3.dp,
                                modifier = Modifier.align(Alignment.Center).size(34.dp)
                            )
                        }
                    }
                }

                Text(
                    "اسحب الصورة بإصبع واحد وقَرِّب بإصبعين لاختيار الجزء الذي تريده",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { if (!saving) onDismiss() }, enabled = !saving) {
                        Text("إلغاء")
                    }
                    Spacer(Modifier.width(6.dp))
                    BrandButton(
                        text = if (saving) "جارٍ الحفظ..." else "اقتصاص",
                        onClick = {
                            val bmp = bitmap ?: return@BrandButton
                            saving = true
                            scope.launch {
                                val path = withContext(Dispatchers.IO) {
                                    cropAndSave(context, bmp, viewport, offset, zoom)
                                }
                                saving = false
                                if (path != null) onConfirm(path) else onDismiss()
                            }
                        },
                        enabled = bitmap != null && viewport.width > 0 && !saving,
                        icon = Icons.Default.Crop
                    )
                }
            }
        }
    }
}

private fun clampOffset(offset: Offset, viewport: IntSize, bitmap: Bitmap, zoom: Float): Offset {
    if (viewport.width <= 0 || viewport.height <= 0) return Offset.Zero
    val scale = max(viewport.width.toFloat() / bitmap.width, viewport.height.toFloat() / bitmap.height) * zoom
    val maxX = ((bitmap.width * scale - viewport.width) / 2f).coerceAtLeast(0f)
    val maxY = ((bitmap.height * scale - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

private fun cropAndSave(
    context: Context,
    bitmap: Bitmap,
    viewport: IntSize,
    offset: Offset,
    zoom: Float
): String? {
    if (viewport.width <= 0 || viewport.height <= 0) return null
    val scale = max(viewport.width.toFloat() / bitmap.width, viewport.height.toFloat() / bitmap.height) * zoom
    val cropWidth = (viewport.width / scale).roundToInt().coerceIn(1, bitmap.width)
    val cropHeight = (viewport.height / scale).roundToInt().coerceIn(1, bitmap.height)
    val centerX = bitmap.width / 2f - offset.x / scale
    val centerY = bitmap.height / 2f - offset.y / scale
    val left = (centerX - cropWidth / 2f).roundToInt().coerceIn(0, bitmap.width - cropWidth)
    val top = (centerY - cropHeight / 2f).roundToInt().coerceIn(0, bitmap.height - cropHeight)
    val cropped = Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
    val dir = File(context.filesDir, "images").apply { mkdirs() }
    val file = File(dir, "crop_${System.currentTimeMillis()}.jpg")
    FileOutputStream(file).use { output ->
        cropped.compress(Bitmap.CompressFormat.JPEG, 92, output)
    }
    if (cropped !== bitmap) cropped.recycle()
    return Uri.fromFile(file).toString()
}

private fun decodeForCrop(context: Context, uri: Uri): Bitmap? = try {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val width = info.size.width
        val height = info.size.height
        val longest = max(width, height)
        if (longest > 2048) {
            val factor = 2048f / longest
            decoder.setTargetSize(
                (width * factor).roundToInt().coerceAtLeast(1),
                (height * factor).roundToInt().coerceAtLeast(1)
            )
        }
    }
} catch (_: Throwable) {
    null
}
