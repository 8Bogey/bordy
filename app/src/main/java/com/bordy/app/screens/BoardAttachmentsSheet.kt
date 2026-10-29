package com.bordy.app.screens

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.bordy.app.data.*
import com.bordy.app.ui.components.*
import com.bordy.app.utils.*
import com.bordy.app.viewmodel.MainViewModel

// لوحة ألوان الملاحظة بأسلوب Keep: خلفات ناعمة بنص داكن
private val KeepNotePalette = listOf(
    Color(0xFFF7F7F7), Color(0xFFFEF7C0), Color(0xFFD6F5DD), Color(0xFFD6E9FF),
    Color(0xFFFBDCE9), Color(0xFFE9DFFF), Color(0xFFFFDCCB), Color(0xFFE2E2E2)
)
private val NoteTextColor = Color(0xFF1F2429)

fun noteColorFor(index: Int): Color = KeepNotePalette[index.mod(KeepNotePalette.size)]

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun BoardAttachmentsSheet(vm: MainViewModel, projectId: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val media by remember(projectId) { vm.getProjectMedia(projectId) }.collectAsState(initial = emptyList())
    val note by remember(projectId) { vm.getProjectNote(projectId) }.collectAsState(initial = null)

    var editingNote by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<ProjectMedia?>(null) }
    var pendingDelete by remember { mutableStateOf<ProjectMedia?>(null) }

    val images = media.filter { it.kind == MediaImage }
    val videos = media.filter { it.kind == MediaVideo }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            vm.addProjectMedia(context, projectId, uris) { _, _ -> SoundHelper.playAddSuccess() }
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.addProjectMedia(context, projectId, listOf(uri)) { _, _ -> SoundHelper.playAddSuccess() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.92f),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AttachFile, null, tint = SellGreen, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("مرفقات البورد وملاحظته", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("${images.size + videos.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Spacer(Modifier.height(10.dp))

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // ▎الملاحظة
                if (editingNote) {
                    NoteEditor(
                        projectId = projectId,
                        initial = note,
                        vm = vm,
                        onDone = { editingNote = false }
                    )
                } else {
                    val current = note
                    if (current == null) {
                        Card(
                            onClick = { editingNote = true },
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.StickyNote2, null, tint = BrandYellow, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text("أضف ملاحظة لهذا البورد", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Icon(Icons.Default.Add, null, tint = SellGreen, modifier = Modifier.size(18.dp))
                            }
                        }
                    } else {
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                .background(noteColorFor(current.colorIndex))
                                .border(1.dp, Color.Black.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                                .clickable { editingNote = true }
                                .padding(16.dp)
                        ) {
                            Column {
                                if (current.title.isNotBlank()) {
                                    Text(current.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = NoteTextColor)
                                    Spacer(Modifier.height(6.dp))
                                }
                                if (current.body.isNotBlank()) {
                                    Text(current.body, fontSize = 14.sp, color = NoteTextColor, lineHeight = 21.sp)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "تعديل • ${formatDateWithDay(current.updatedAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NoteTextColor.copy(alpha = 0.55f)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                // ▎الصور والفيديو
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BrandButton(
                        text = "إضافة صور",
                        icon = Icons.Default.AddAPhoto,
                        onClick = { imagePicker.launch(arrayOf("image/*")) },
                        modifier = Modifier.weight(1f)
                    )
                    BrandButton(
                        text = "إضافة فيديو",
                        icon = Icons.Default.Videocam,
                        onClick = { videoPicker.launch(arrayOf("video/*")) },
                        containerColor = BuyAmber,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (images.isEmpty() && videos.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.Image,
                        message = "لا مرفقات بعد — الصور والفيديو تُنسخ داخل التطبيق فتبقى حتى لو حُذف الملف الأصلي أو تغيّر إذنه"
                    )
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        images.forEach { m ->
                            MediaTile(media = m, onClick = { viewer = m }, onDelete = { pendingDelete = m }) {
                                AsyncImage(
                                    model = m.uri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        videos.forEach { m ->
                            MediaTile(media = m, onClick = { viewer = m }, onDelete = { pendingDelete = m }) {
                                Box(Modifier.fillMaxSize().background(Color(0xFF20242A)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.PlayCircleOutline, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(30.dp))
                                }
                            }
                        }
                    }
                    Text(
                        "الصور ${images.size} · الفيديوهات ${videos.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    viewer?.let { m ->
        MediaViewerDialog(
            media = m,
            onDismiss = { viewer = null },
            onDelete = { vm.deleteProjectMedia(context, m); viewer = null }
        )
    }

    pendingDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            shape = DialogShape,
            title = { Text("حذف المرفق", fontWeight = FontWeight.Bold, color = DangerRed) },
            text = { Text(if (m.kind == MediaVideo) "يُحذف هذا الفيديو من البورد." else "يُحذف هذا الملف من البورد.") },
            confirmButton = {
                Button(
                    onClick = { vm.deleteProjectMedia(context, m); pendingDelete = null },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                    shape = CircleShape
                ) { Text("حذف", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("إلغاء") } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteEditor(projectId: Long, initial: ProjectNote?, vm: MainViewModel, onDone: () -> Unit) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var body by remember { mutableStateOf(initial?.body ?: "") }
    var colorIndex by remember { mutableStateOf(initial?.colorIndex ?: 1) }
    val isNew = initial == null

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(noteColorFor(colorIndex))
            .border(1.dp, Color.Black.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            placeholder = { Text("العنوان", color = NoteTextColor.copy(alpha = 0.45f)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = noteFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            placeholder = { Text("اكتب ملاحظة… تفاصيل التسليم، المقاسات، اتفاق السعر", color = NoteTextColor.copy(alpha = 0.45f)) },
            minLines = 4,
            maxLines = 10,
            shape = RoundedCornerShape(14.dp),
            colors = noteFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            KeepNotePalette.forEachIndexed { index, color ->
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(color)
                        .border(
                            if (index == colorIndex) 2.5.dp else 1.dp,
                            if (index == colorIndex) SellGreen else Color.Black.copy(alpha = 0.18f),
                            CircleShape
                        )
                        .clickable { colorIndex = index },
                    contentAlignment = Alignment.Center
                ) {
                    if (index == colorIndex) Icon(Icons.Default.Check, "لون الملاحظة", tint = NoteTextColor, modifier = Modifier.size(14.dp))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandButton(
                text = "حفظ",
                icon = Icons.Default.Check,
                onClick = {
                    vm.saveProjectNote(ProjectNote(projectId = projectId, title = title, body = body, colorIndex = colorIndex, updatedAt = System.currentTimeMillis()))
                    onDone()
                },
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDone) { Text("إلغاء", color = NoteTextColor.copy(alpha = 0.7f), fontWeight = FontWeight.Bold) }
            if (!isNew) {
                TextButton(onClick = { vm.deleteProjectNote(projectId); onDone() }) {
                    Text("حذف", color = DangerRed, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun noteFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedContainerColor = Color.White.copy(alpha = 0.55f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.35f),
    focusedTextColor = NoteTextColor,
    unfocusedTextColor = NoteTextColor,
    cursorColor = NoteTextColor
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTile(
    media: ProjectMedia,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.size(96.dp)) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                .clickable { onClick() }
        ) { content() }
        if (media.kind == MediaVideo) {
            Text(
                "فيديو",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f)).clickable { onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Close, "حذف المرفق", tint = Color.White, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun MediaViewerDialog(media: ProjectMedia, onDismiss: () -> Unit, onDelete: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (media.kind == MediaVideo) {
                VideoPlayer(media.uri)
            } else {
                AsyncImage(
                    model = media.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().clickable { onDismiss() }
                )
            }
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopEnd).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TintedCircleButton(
                    onClick = onDelete,
                    icon = Icons.Default.Delete,
                    contentDescription = "حذف المرفق",
                    tint = DangerRed,
                    size = 40.dp,
                    iconSize = 19.dp
                )
                TintedCircleButton(
                    onClick = onDismiss,
                    icon = Icons.Default.Close,
                    contentDescription = "إغلاق",
                    tint = Color.White.copy(alpha = 0.35f),
                    size = 40.dp,
                    iconSize = 19.dp
                )
            }
        }
    }
}

@Composable
private fun VideoPlayer(uriString: String) {
    val context = LocalContext.current
    val videoView = remember(uriString) { VideoView(context) }
    AndroidView(factory = { videoView }, modifier = Modifier.fillMaxSize())
    LaunchedEffect(uriString) {
        val controller = MediaController(context).apply { setAnchorView(videoView) }
        videoView.setMediaController(controller)
        videoView.setVideoURI(Uri.parse(uriString))
        videoView.start()
    }
    DisposableEffect(uriString) {
        onDispose { videoView.stopPlayback() }
    }
}
