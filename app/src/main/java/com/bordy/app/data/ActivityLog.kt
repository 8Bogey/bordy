package com.bordy.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// سجل العمليات: يُكتب بعد نجاح العملية فقط، ونصّه قابل للتعديل من واجهة السجل
@Entity(
    tableName = "activity_logs",
    indices = [Index("createdAt"), Index("category")]
)
data class ActivityLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val category: String,
    val action: String,
    val title: String,
    val details: String = "",
    // مفتاح الكيان المستخدم لدمج النقرات المتتابعة (مخزون/كمية) في سطر واحد
    val entityKey: String = "",
    val repeatCount: Int = 1,
    val isEdited: Boolean = false,
    // تغيّر كمية يُعرض ملوّناً أسفل البطاقة: 1 زيادة، -1 نقصان، 0 ليس تغيّر كمية
    @ColumnInfo(defaultValue = "0") val changeDirection: Int = 0,
    @ColumnInfo(defaultValue = "") val changeLabel: String = "",
    val changeBefore: Int? = null,
    val changeAfter: Int? = null
)

enum class LogCategory(val label: String) {
    INVENTORY("المخزون"),
    SUPPLIERS("المزودون"),
    BOARDS("البوردات"),
    TEMPLATES("القوالب"),
    CART("السلة"),
    SETTINGS("الإعدادات");

    companion object {
        fun from(raw: String): LogCategory = entries.firstOrNull { it.name == raw } ?: INVENTORY
    }
}

enum class LogAction(val label: String) {
    ADD("إضافة"),
    EDIT("تعديل"),
    DELETE("حذف"),
    COMPLETE("إتمام"),
    STATUS("حالة"),
    STOCK("مخزون"),
    REORDER("ترتيب");

    companion object {
        fun from(raw: String): LogAction = entries.firstOrNull { it.name == raw } ?: EDIT
    }
}
