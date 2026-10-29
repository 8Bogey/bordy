package com.bordy.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.ColumnInfo

@Entity(tableName = "suppliers", indices = [Index(value = ["syncId"], unique = true)])
data class Supplier(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, val phone: String = "", val url: String = "",
    val rating: Int = 3, val defaultMinOrder: Int = 1, val deliveryCost: Double = 0.0,
    val orderIndex: Int = 0,
    @ColumnInfo(defaultValue = "") val syncId: String = newSyncId()
)

@Entity(tableName = "components", indices = [Index(value = ["syncId"], unique = true)])
data class Component(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, val imageUri: String = "",
    val brand: String = "", val origin: String = "", val customFields: String = "{}",
    val orderIndex: Int = 0,
    @ColumnInfo(defaultValue = "0") val stockCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val purchasePrice: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val sellPrice: Double = 0.0,
    @ColumnInfo(defaultValue = "") val syncId: String = newSyncId()
)

// وسم: اسم نظيف + لون ثابت من لوحة الوسوم
@Entity(tableName = "tags", indices = [
    Index(value = ["name"], unique = true),
    Index(value = ["syncId"], unique = true)
])
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    @ColumnInfo(defaultValue = "0") val colorIndex: Int = 0,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
    @ColumnInfo(defaultValue = "") val syncId: String = newSyncId()
)

// ربط قطعة بوسم — الحذف يتسلسل من الطرفين فلا تبقى علاقات يتيمة
@Entity(tableName = "component_tags",
    primaryKeys = ["componentId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = Component::class, parentColumns = ["id"], childColumns = ["componentId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Tag::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("componentId"), Index("tagId"), Index(value = ["syncId"], unique = true)])
data class ComponentTag(
    val componentId: Long,
    val tagId: Long,
    @ColumnInfo(defaultValue = "") val syncId: String = newSyncId()
)

@Entity(tableName = "component_variants",
    foreignKeys = [ForeignKey(entity = Component::class, parentColumns = ["id"], childColumns = ["componentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("componentId"), Index(value = ["syncId"], unique = true)])
data class ComponentVariant(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val componentId: Long, val specName: String,
    val defaultPurchasePrice: Double = 0.0,
    val defaultSellPrice: Double = 0.0,
    val stockCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val isBase: Boolean = false,
    @ColumnInfo(defaultValue = "") val syncId: String = newSyncId()
)

@Entity(tableName = "supplier_offers",
    foreignKeys = [
        ForeignKey(entity = ComponentVariant::class, parentColumns = ["id"], childColumns = ["variantId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Supplier::class, parentColumns = ["id"], childColumns = ["supplierId"], onDelete = ForeignKey.CASCADE)
    ], indices = [Index("variantId"), Index("supplierId"), Index(value = ["syncId"], unique = true)])
data class SupplierOffer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val variantId: Long, val supplierId: Long,
    val purchasePrice: Double = 0.0, val minOrder: Int = 1,
    @ColumnInfo(defaultValue = "") val syncId: String = newSyncId()
)

@Entity(tableName = "cart_items",
    foreignKeys = [
        ForeignKey(entity = SupplierOffer::class, parentColumns = ["id"], childColumns = ["offerId"], onDelete = ForeignKey.CASCADE)
    ], indices = [Index("offerId"), Index(value = ["syncId"], unique = true)])
data class CartItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val offerId: Long,
    val quantity: Int = 1,
    val orderIndex: Int = 0,
    @ColumnInfo(defaultValue = "") val syncId: String = newSyncId()
)

@Entity(tableName = "templates", indices = [Index(value = ["syncId"], unique = true)])
data class Template(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, val imageUri: String = "",
    val boxWidth: Double = 0.0, val boxHeight: Double = 0.0, val boxDepth: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
    // تكلفة عمل القالب تُسحب معه إلى البورد وتُضاف إلى أجور البورد
    @ColumnInfo(defaultValue = "0") val laborCost: Double = 0.0,
    @ColumnInfo(defaultValue = "") val syncId: String = newSyncId()
)

fun Template.sanitized(): Template = copy(
    name = name.trim(),
    boxWidth = boxWidth.coerceAtLeast(0.0),
    boxHeight = boxHeight.coerceAtLeast(0.0),
    boxDepth = boxDepth.coerceAtLeast(0.0),
    laborCost = laborCost.coerceAtLeast(0.0)
)

// قطعة قالب: حجم من المخزون، أو عنصر يدوي (variantId = null)، أو بطاقة انفصلت عن المخزون
@Entity(tableName = "template_items", indices = [Index(value = ["syncId"], unique = true)])
data class TemplateItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: Long,
    val variantId: Long? = null,
    val quantity: Int = 1,
    val orderIndex: Int = 0,
    @ColumnInfo(defaultValue = "") val manualName: String = "",
    @ColumnInfo(defaultValue = "0") val purchasePrice: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val sellPrice: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val isManual: Boolean = false,
    @ColumnInfo(defaultValue = "") val syncId: String = newSyncId()
)

@Entity(tableName = "projects", indices = [Index(value = ["syncId"], unique = true)])
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerName: String, val dueDate: Long? = null,
    val status: String = "ACTIVE",
    val completedAt: Long? = null,
    val laborCost: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val receivedAmount: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "") val syncId: String = newSyncId()
)

@Entity(tableName = "project_items", indices = [Index(value = ["syncId"], unique = true)])
data class ProjectItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val variantId: Long? = null,
    val manualName: String = "",
    val quantity: Int = 1,
    val sellPrice: Double = 0.0,
    val fromStock: Boolean = false,
    val isCustomSell: Boolean = false,
    val lastKnownPurchase: Double = 0.0,
    val isManual: Boolean = false,
    val orderIndex: Int = 0,
    @ColumnInfo(defaultValue = "0") val isShortage: Boolean = false,
    @ColumnInfo(defaultValue = "0") val shortageRemaining: Int = 0,
    @ColumnInfo(defaultValue = "0") val shortageFromStock: Int = 0,
    @ColumnInfo(defaultValue = "0") val shortageManual: Int = 0,
    @ColumnInfo(defaultValue = "0") val shortageAddedQty: Int = 0,
    @ColumnInfo(defaultValue = "0") val shortageManualPurchase: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val shortageManualSell: Double = 0.0,
    @ColumnInfo(defaultValue = "") val syncId: String = newSyncId()
)

const val MediaImage = "IMAGE"
const val MediaVideo = "VIDEO"

// مرفقات البورد (صور/فيديو) — تُنسخ داخل ملفات التطبيق الخاصة فيبقى الوصول إليها مضموناً
@Entity(tableName = "project_media",
    foreignKeys = [ForeignKey(entity = Project::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId")])
data class ProjectMedia(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val kind: String = MediaImage,
    val uri: String,
    val orderIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

// ملاحظة البورد بنمط Keep — ملاحظة واحدة لكل بورد
@Entity(tableName = "project_notes",
    foreignKeys = [ForeignKey(entity = Project::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId"), Index(value = ["syncId"], unique = true)])
data class ProjectNote(
    @PrimaryKey val projectId: Long,
    val title: String = "",
    val body: String = "",
    @ColumnInfo(defaultValue = "0") val colorIndex: Int = 0,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    @ColumnInfo(defaultValue = "") val syncId: String = newSyncId()
)


@Entity(tableName = "app_settings", indices = [Index(value = ["syncId"], unique = true)])
data class AppSettings(
    @PrimaryKey val id: Int = 1,
    val isDarkMode: Boolean = true,
    val stockHighThreshold: Int = 10,
    val stockMediumThreshold: Int = 5,
    val defaultLaborCost: Double = 0.0,
    val profitFromDate: Long? = null,
    val profitToDate: Long? = null,
    val isFirstLaunch: Boolean = true,
    // هوية النشاط والفاتورة: الحقل الفارغ يرجع إلى الافتراضي أدناه
    @ColumnInfo(defaultValue = "") val businessName: String = "",
    @ColumnInfo(defaultValue = "") val businessTagline: String = "",
    @ColumnInfo(defaultValue = "") val businessPhone: String = "",
    @ColumnInfo(defaultValue = "") val invoiceFooter: String = "",
    @ColumnInfo(defaultValue = "0") val showPurchaseInPdf: Boolean = false,
    @ColumnInfo(defaultValue = "0") val showProfitInPdf: Boolean = false,
    // وقت التسليم اليومي الذي يُدمج مع أي تاريخ تسليم يُختار من التقويم
    @ColumnInfo(defaultValue = "18") val deliveryHour: Int = 18,
    @ColumnInfo(defaultValue = "0") val deliveryMinute: Int = 0,
    // تنبيهان قبل التسليم: قبل أيام وقبل ساعات (0 = معطّل)
    @ColumnInfo(defaultValue = "1") val reminderDaysBefore: Int = 1,
    @ColumnInfo(defaultValue = "3") val reminderHoursBefore: Int = 3,
    // وضع إخفاء الأسعار بالبصمة
    @ColumnInfo(defaultValue = "0") val pricesHidden: Boolean = false,
    @ColumnInfo(defaultValue = "") val syncId: String = newSyncId()
)

const val DefaultBusinessName = "بوردي 🍀"
const val DefaultBusinessTagline = "حلول متكاملة لتأسيسات الكهرباء"

fun AppSettings.invoiceTitle(): String = businessName.ifBlank { DefaultBusinessName }
fun AppSettings.invoiceTagline(): String = businessTagline.ifBlank { DefaultBusinessTagline }