package com.bordy.app.ui.components

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.bordy.app.utils.DangerRed
import com.bordy.app.viewmodel.DeleteOutcome

// حذف من المخزون: ممنوع إن كان مستخدماً في بورد غير مكتمل، ومسموح بتأكيد واحد عند الفصل عن القوالب
class StockDeleteHandler internal constructor(
    private val notify: (String) -> Unit,
    private val onDeleted: () -> Unit,
    private val deletedMessage: String
) {
    var pendingConfirm by mutableStateOf<String?>(null)
        private set

    private var retry: (() -> Unit)? = null

    fun attempt(action: (confirmed: Boolean, onResult: (DeleteOutcome) -> Unit) -> Unit) {
        action(false) { handle(it, action) }
    }

    fun confirm() {
        val again = retry ?: return
        pendingConfirm = null
        again()
    }

    fun dismissConfirm() { pendingConfirm = null }

    private fun handle(
        outcome: DeleteOutcome,
        action: (confirmed: Boolean, onResult: (DeleteOutcome) -> Unit) -> Unit
    ) {
        when (outcome) {
            is DeleteOutcome.Deleted -> { notify(deletedMessage); onDeleted() }
            is DeleteOutcome.Blocked -> notify(outcome.message)
            is DeleteOutcome.NeedsTemplateConfirm -> {
                retry = { action(true) { handle(it, action) } }
                pendingConfirm = outcome.message
            }
        }
    }
}

@Composable
fun rememberStockDeleteHandler(
    deletedMessage: String = "تم الحذف",
    onDeleted: () -> Unit = {}
): StockDeleteHandler {
    val context = LocalContext.current
    val notify: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    return remember(deletedMessage, onDeleted) {
        StockDeleteHandler(notify, onDeleted, deletedMessage)
    }
}

@Composable
fun StockDeleteConfirmDialog(handler: StockDeleteHandler) {
    val message = handler.pendingConfirm ?: return
    AlertDialog(
        onDismissRequest = handler::dismissConfirm,
        title = { Text("حذف من المخزون") },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = handler::confirm,
                colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
            ) { Text("حذف مع فصل القوالب") }
        },
        dismissButton = { TextButton(onClick = handler::dismissConfirm) { Text("إلغاء") } }
    )
}
