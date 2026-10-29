package com.bordy.app.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.bordy.app.data.*
import com.bordy.app.utils.BoardMediaStore
import com.bordy.app.utils.DEFAULT_VARIANT_NAME
import com.bordy.app.utils.componentLabel
import com.bordy.app.utils.TagPalette
import com.bordy.app.utils.billablePurchaseTotal
import com.bordy.app.utils.billableSellTotal
import com.bordy.app.utils.cleanString
import com.bordy.app.utils.formatCurrency
import com.bordy.app.utils.formatDateWithDay
import com.bordy.app.utils.isSellBelowPurchase
import com.bordy.app.utils.namesEqual
import com.bordy.app.utils.normalizeItemName
import com.bordy.app.utils.normalizeTagName
import com.bordy.app.utils.pricesEqual
import com.bordy.app.utils.sellBelowPurchaseMessage
import com.bordy.app.utils.tagColorIndexForName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs

// سقف السجل: يحفظ آخر العمليات فقط حتى لا تتضخم القاعدة على المدى الطويل
private const val MaxActivityLogs = 2000
private const val MergeWindowMs = 120_000L
private const val MergeWindowLongMs = 300_000L

class MainViewModel(private val db: AppDatabase) : ViewModel() {

    // نقرتان سريعتان على قطعة بلا أحجام قد تُنشئان حجمين أساسيين لولا التسلسل
    private val baseVariantLock = Mutex()

    val suppliers = db.supplierDao().getAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val components = db.componentDao().getAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val variants = db.componentDao().getAllVariants().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val offers = db.componentDao().getAllOffers().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val templates  = db.templateDao().getAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val templateItems = db.templateDao().getAllItems().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val projects   = db.projectDao().getAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allProjectItems = db.projectDao().getAllItems().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings   = db.settingsDao().get().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val tags = db.tagDao().getAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val componentTags = db.tagDao().getAllLinks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // وسوم كل قطعة جاهزة للعرض والتصفية مباشرة في الواجهة
    val tagsByComponent: StateFlow<Map<Long, List<Tag>>> = combine(tags, componentTags) { allTags, links ->
        val byId = HashMap<Long, Tag>(allTags.size)
        for (t in allTags) byId[t.id] = t
        val grouped = HashMap<Long, MutableList<Tag>>()
        for (l in links) {
            val tag = byId[l.tagId] ?: continue
            val bucket = grouped[l.componentId]
            if (bucket == null) grouped[l.componentId] = mutableListOf(tag) else bucket.add(tag)
        }
        for (list in grouped.values) list.sortBy { it.name.lowercase() }
        grouped
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // كم قطعة تستخدم كل وسم — يظهر على شرائط التصفية
    val tagUsage: StateFlow<Map<Long, Int>> = componentTags.map { links ->
        val counts = HashMap<Long, Int>()
        for (l in links) counts[l.tagId] = (counts[l.tagId] ?: 0) + 1
        counts
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // ===== سجل العمليات =====
    val activityLogs = db.activityLogDao().getAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activityLogCount = db.activityLogDao().count().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun editActivityLog(entry: ActivityLog, title: String, details: String) {
        val cleanTitle = title.trim().take(120)
        if (cleanTitle.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                db.activityLogDao().update(
                    entry.copy(
                        title = cleanTitle,
                        details = details.trim().take(600),
                        isEdited = true
                    )
                )
            }
        }
    }

    fun deleteActivityLog(id: Long) {
        viewModelScope.launch { runCatching { db.activityLogDao().deleteById(id) } }
    }

    fun clearActivityLog() {
        viewModelScope.launch { runCatching { db.activityLogDao().clear() } }
    }

    // يُكتب السجل بعد نجاح العملية فقط، ودمج النقرات المتتابعة يتم في سطر واحد
    private suspend fun record(
        category: LogCategory,
        action: LogAction,
        title: String,
        details: String = "",
        entityKey: String = "",
        mergeWindowMs: Long = 0L,
        changeDirection: Int = 0,
        changeLabel: String = "",
        changeBefore: Int? = null,
        changeAfter: Int? = null
    ) {
        runCatching {
            val dao = db.activityLogDao()
            val cleanTitle = title.trim().ifBlank { category.label }.take(120)
            val structured = changeDirection != 0 && changeBefore != null && changeAfter != null && changeLabel.isNotBlank()
            fun changeText(before: Int): String =
                "قطعة: ${if (changeDirection > 0) "زيادة" else "نقصان"} $changeLabel من $before إلى ${changeAfter ?: 0}"
            val cleanDetails = (if (structured) changeText(changeBefore!!) else details.trim()).take(600)
            val now = System.currentTimeMillis()
            var merged = false
            if (mergeWindowMs > 0L && entityKey.isNotEmpty()) {
                val latest = dao.latestOnce()
                if (latest != null && !latest.isEdited && latest.entityKey == entityKey &&
                    latest.category == category.name && latest.action == action.name &&
                    now - latest.createdAt in 0L..mergeWindowMs
                ) {
                    // الدمج يحافظ على نقطة البداية الأولى ويحدّث النهاية والعدد الحالي
                    val mergedBefore = if (structured) latest.changeBefore ?: changeBefore else null
                    dao.update(
                        latest.copy(
                            createdAt = now,
                            details = if (structured) changeText(mergedBefore!!) else cleanDetails,
                            repeatCount = latest.repeatCount + 1,
                            changeDirection = if (structured) changeDirection else latest.changeDirection,
                            changeLabel = if (structured) changeLabel else latest.changeLabel,
                            changeBefore = if (structured) mergedBefore else latest.changeBefore,
                            changeAfter = if (structured) changeAfter else latest.changeAfter
                        )
                    )
                    merged = true
                }
            }
            if (!merged) {
                dao.insert(
                    ActivityLog(
                        createdAt = now,
                        category = category.name,
                        action = action.name,
                        title = cleanTitle,
                        details = cleanDetails,
                        entityKey = entityKey,
                        changeDirection = if (structured) changeDirection else 0,
                        changeLabel = if (structured) changeLabel else "",
                        changeBefore = if (structured) changeBefore else null,
                        changeAfter = if (structured) changeAfter else null
                    )
                )
                dao.trim(MaxActivityLogs)
            }
        }
    }

    private fun value(v: Double): String = formatCurrency(v)
    private fun yesNo(b: Boolean): String = if (b) "نعم" else "لا"
    private fun dateOrNone(d: Long?): String = d?.let { formatDateWithDay(it) } ?: "غير محدد"
    private fun clockText(hour: Int, minute: Int): String =
        "%02d:%02d".format(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
    private fun statusLabel(s: String): String = when (s) {
        "DONE" -> "تم"
        "FUTURE" -> "مستقبل"
        else -> "قيد العمل"
    }

    private suspend fun projectName(projectId: Long): String =
        db.projectDao().getByIdOnce(projectId)?.customerName?.takeIf { it.isNotBlank() } ?: "بورد محذوف"

    private suspend fun supplierName(supplierId: Long): String =
        db.supplierDao().getByIdOnce(supplierId)?.name?.takeIf { it.isNotBlank() } ?: "مزود محذوف"

    private suspend fun pieceLabel(variantId: Long?): String {
        if (variantId == null) return "قطعة يدوية"
        val variant = db.componentDao().getVariantOnce(variantId) ?: return "قطعة محذوفة"
        val comp = db.componentDao().getComponentOnce(variant.componentId)
        return componentLabel(comp?.name, variant, "قطعة محذوفة")
    }

    private suspend fun templateName(templateId: Long): String =
        db.templateDao().getByIdOnce(templateId)?.name?.takeIf { it.isNotBlank() } ?: "قالب محذوف"

    private suspend fun templateItemLabel(item: TemplateItem): String =
        if (item.isManual || item.variantId == null) item.manualName.ifBlank { "قطعة يدوية" }
        else pieceLabel(item.variantId)

    private fun dimensionsValue(t: Template): String =
        "${t.boxWidth.cleanString()} × ${t.boxHeight.cleanString()} × ${t.boxDepth.cleanString()} سم"

    private fun dimensionsText(t: Template): String =
        if (t.boxWidth > 0.0 || t.boxHeight > 0.0 || t.boxDepth > 0.0) " · الأبعاد ${dimensionsValue(t)}" else ""

    fun createProject(name: String, onDone: (Long) -> Unit) {
        if (!com.bordy.app.license.IntegrityChecks.validateSession()) return
        viewModelScope.launch {
            val defaultLabor = db.settingsDao().getOnce()?.defaultLaborCost ?: 0.0
            val id = db.projectDao().insert(Project(customerName = name, laborCost = defaultLabor))
            record(
                LogCategory.BOARDS, LogAction.ADD, name,
                buildString {
                    append("بورد جديد للعميل $name")
                    if (defaultLabor > 0.0) append(" · أجور افتراضية ${value(defaultLabor)}")
                }
            )
            onDone(id)
        }
    }

    fun deleteProject(context: Context, p: Project) {
        val app = context.applicationContext
        viewModelScope.launch {
            if (p.status != "DONE") {
                val projectItems = db.projectDao().getItems(p.id).first()
                for (item in projectItems) {
                    if (item.variantId != null) {
                        if (item.fromStock && item.quantity > 0) {
                            db.componentDao().adjustStock(item.variantId, item.quantity)
                        }
                        if (item.isShortage && item.shortageFromStock > 0) {
                            db.componentDao().adjustStock(item.variantId, item.shortageFromStock)
                        }
                    }
                }
            }
            db.projectDao().getItems(p.id).first().forEach { db.projectDao().deleteItem(it) }
            db.projectDao().delete(p)
            withContext(Dispatchers.IO) { BoardMediaStore.deleteProjectFolder(app, p.id) }
            record(
                LogCategory.BOARDS, LogAction.DELETE, p.customerName,
                if (p.status != "DONE") "حُذف البورد وأُعيد مخزون قطعه"
                else "حُذف البورد المكتمل"
            )
        }
    }

    fun updateProjectStatus(p: Project, status: String) {
        if (status == p.status) return
        viewModelScope.launch {
            val completedAt = when {
                status != "DONE" -> null
                p.completedAt != null -> p.completedAt
                else -> System.currentTimeMillis()
            }
            db.projectDao().update(p.copy(status = status, completedAt = completedAt))
            record(
                LogCategory.BOARDS,
                if (status == "DONE") LogAction.COMPLETE else LogAction.STATUS,
                p.customerName,
                "الحالة: من «${statusLabel(p.status)}» إلى «${statusLabel(status)}»"
            )
        }
    }
    fun updateLaborCost(p: Project, cost: Double) {
        viewModelScope.launch {
            val safe = cost.coerceAtLeast(0.0)
            if (pricesEqual(p.laborCost, safe)) return@launch
            db.projectDao().update(p.copy(laborCost = safe))
            record(LogCategory.BOARDS, LogAction.EDIT, p.customerName, "أجور العمل: من ${value(p.laborCost)} إلى ${value(safe)}")
        }
    }
    fun updateReceivedAmount(p: Project, amount: Double) {
        viewModelScope.launch {
            val safe = amount.coerceAtLeast(0.0)
            if (pricesEqual(p.receivedAmount, safe)) return@launch
            db.projectDao().update(p.copy(receivedAmount = safe))
            record(LogCategory.BOARDS, LogAction.EDIT, p.customerName, "المبلغ المستلم: من ${value(p.receivedAmount)} إلى ${value(safe)}")
        }
    }
    fun setDueDate(p: Project, date: Long?) {
        viewModelScope.launch {
            if (p.dueDate == date) return@launch
            db.projectDao().update(p.copy(dueDate = date))
            record(
                LogCategory.BOARDS, LogAction.EDIT, p.customerName,
                if (date == null) "أُزيل موعد التسليم"
                else "موعد التسليم: ${formatDateWithDay(date)}"
            )
        }
    }

    fun getProject(id: Long): Flow<Project?> = db.projectDao().getById(id)
    fun getProjectItems(id: Long): Flow<List<ProjectItem>> = db.projectDao().getItems(id)

    fun addComponentToProject(projectId: Long, variantId: Long, qty: Int, onResult: (String, Boolean, Int) -> Unit) {
        viewModelScope.launch {
            val variant = db.componentDao().getAllVariants().first().find { it.id == variantId }
            if (variant == null) { onResult("القطعة غير موجودة", false, 0); return@launch }
            addVariantToProject(projectId, variant, qty, onResult)
        }
    }

    // القطعة بلا أحجام تُعامل كقطعة أساسية واحدة: يُنشأ حجمها الأساسي خلف الكواليس عند أول استخدام
    fun addBaseComponentToProject(projectId: Long, componentId: Long, qty: Int, onResult: (String, Boolean, Int) -> Unit) {
        viewModelScope.launch {
            val variant = ensureBaseVariant(componentId)
            if (variant == null) { onResult("القطعة غير موجودة", false, 0); return@launch }
            addVariantToProject(projectId, variant, qty, onResult)
        }
    }

    private suspend fun ensureBaseVariant(componentId: Long): ComponentVariant? = baseVariantLock.withLock {
        val comp = db.componentDao().getAllOnce().find { it.id == componentId } ?: return@withLock null
        val existing = db.componentDao().getAllVariantsOnce().filter { it.componentId == componentId }
        if (existing.isNotEmpty()) return@withLock existing.firstOrNull { it.isBase } ?: existing.first()
        val variant = ComponentVariant(
            componentId = comp.id,
            specName = comp.name,
            defaultPurchasePrice = comp.purchasePrice.coerceAtLeast(0.0),
            defaultSellPrice = comp.sellPrice.coerceAtLeast(0.0),
            stockCount = comp.stockCount.coerceAtLeast(0),
            isBase = true
        )
        val id = db.componentDao().insertVariant(variant)
        if (comp.stockCount != 0) db.componentDao().updateComponent(comp.copy(stockCount = 0))
        variant.copy(id = id)
    }

    private suspend fun addVariantToProject(projectId: Long, variant: ComponentVariant, qty: Int, onResult: (String, Boolean, Int) -> Unit) {
        val pulledQty = minOf(qty, variant.stockCount).coerceAtLeast(0)

        if (pulledQty > 0) {
            db.componentDao().updateVariant(variant.copy(stockCount = variant.stockCount - pulledQty))

            val existingItems = db.projectDao().getItems(projectId).first()
            val existingItem = existingItems.find {
                !it.isManual && !it.isShortage && it.variantId == variant.id && it.fromStock &&
                    pricesEqual(it.sellPrice, variant.defaultSellPrice) &&
                    pricesEqual(it.lastKnownPurchase, variant.defaultPurchasePrice)
            }

            if (existingItem != null) {
                db.projectDao().updateItem(existingItem.copy(quantity = existingItem.quantity + pulledQty))
            } else {
                val nextOrder = (existingItems.maxOfOrNull { it.orderIndex } ?: -1) + 1
                val item = ProjectItem(
                    projectId = projectId, variantId = variant.id, quantity = pulledQty,
                    sellPrice = variant.defaultSellPrice, fromStock = true,
                    lastKnownPurchase = variant.defaultPurchasePrice, isManual = false,
                    orderIndex = nextOrder
                )
                db.projectDao().insertItem(item)
            }
        }

        val missing = qty - pulledQty
        if (missing > 0) upsertShortageItem(projectId, variant, missing, pulledQty)

        val msg = when {
            missing > 0 && pulledQty > 0 -> "تمت إضافة $pulledQty، وأُضيف نقص $missing كعنصر مؤقت"
            missing > 0 -> "المخزون فارغ — أُضيف نقص $missing كعنصر مؤقت"
            else -> "تمت إضافة $pulledQty"
        }
        record(
            LogCategory.BOARDS, LogAction.ADD, projectName(projectId),
            buildString {
                append("قطعة: ${pieceLabel(variant.id)}")
                append(" · من المخزون $pulledQty")
                if (missing > 0) append(" · نقص $missing كعنصر مؤقت")
            }
        )
        onResult(msg, true, pulledQty)
    }

    private suspend fun upsertShortageItem(projectId: Long, variant: ComponentVariant, missing: Int, addedFromStock: Int) {
        if (missing <= 0) return
        val currentItems = db.projectDao().getItems(projectId).first()
        val existing = currentItems.find {
            it.isShortage && it.variantId == variant.id &&
                it.shortageRemaining > 0 && pricesEqual(it.sellPrice, variant.defaultSellPrice) &&
                pricesEqual(it.lastKnownPurchase, variant.defaultPurchasePrice)
        }
        if (existing != null) {
            db.projectDao().updateItem(
                existing.copy(
                    quantity = existing.quantity + missing,
                    shortageRemaining = existing.shortageRemaining + missing,
                    shortageAddedQty = existing.shortageAddedQty + addedFromStock
                )
            )
        } else {
            val nextOrder = (currentItems.maxOfOrNull { it.orderIndex } ?: -1) + 1
            db.projectDao().insertItem(
                ProjectItem(
                    projectId = projectId, variantId = variant.id, quantity = missing,
                    sellPrice = variant.defaultSellPrice, fromStock = false,
                    lastKnownPurchase = variant.defaultPurchasePrice, isManual = false,
                    orderIndex = nextOrder, isShortage = true,
                    shortageRemaining = missing, shortageAddedQty = addedFromStock
                )
            )
        }
    }

    fun fulfillShortageItem(item: ProjectItem, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val fresh = db.projectDao().getItems(item.projectId).first().find { it.id == item.id } ?: return@launch
            if (!fresh.isShortage || fresh.shortageRemaining <= 0) return@launch
            val variantId = fresh.variantId ?: return@launch
            val variant = db.componentDao().getAllVariantsOnce().find { it.id == variantId } ?: return@launch
            val pulled = db.componentDao().decrementStockIfAvailable(variantId)
            if (pulled <= 0) { onResult("المخزون فارغ — استخدم التوفير اليدوي"); return@launch }
            val target = ensureShortageGeneration(fresh, variant)
            if (db.projectDao().consumeShortagePiece(target.id) <= 0) {
                db.componentDao().adjustStock(variantId, 1)
                return@launch
            }
            db.projectDao().markShortageFromStock(target.id)
            record(
                LogCategory.BOARDS, LogAction.EDIT, projectName(fresh.projectId),
                "توفير نقص من المخزون · ${pieceLabel(variantId)}"
            )
            onResult(
                if (target.id == fresh.id) "تم سحب قطعة من المخزون"
                else "تم سحب قطعة من المخزون بسعرها الحالي في بطاقة منفصلة"
            )
        }
    }

    // بطاقة النقص تحمل لقطة سعر قديمة؛ عند تغيّر السعر تُفصل الكمية المتبقية في بطاقة جديدة بالسعر الحالي
    private suspend fun ensureShortageGeneration(fresh: ProjectItem, variant: ComponentVariant): ProjectItem {
        val sameGeneration = pricesEqual(fresh.sellPrice, variant.defaultSellPrice) &&
            pricesEqual(fresh.lastKnownPurchase, variant.defaultPurchasePrice)
        if (sameGeneration) return fresh

        val all = db.projectDao().getItems(fresh.projectId).first()
        val pending = fresh.shortageRemaining
        val fulfilled = (fresh.quantity - pending).coerceAtLeast(0)
        // بطاقة لم يُوفَّ منها شيء تنتقل كاملة للجيل الجديد، فتبقى فارغة بلا معنى
        if (fulfilled == 0) db.projectDao().deleteItem(fresh)
        else db.projectDao().updateItem(fresh.copy(quantity = fulfilled, shortageRemaining = 0))

        val existing = all.find {
            it.id != fresh.id && it.isShortage && it.variantId == fresh.variantId && it.shortageRemaining > 0 &&
                pricesEqual(it.sellPrice, variant.defaultSellPrice) &&
                pricesEqual(it.lastKnownPurchase, variant.defaultPurchasePrice)
        }
        if (existing != null) {
            val updated = existing.copy(
                quantity = existing.quantity + pending,
                shortageRemaining = existing.shortageRemaining + pending
            )
            db.projectDao().updateItem(updated)
            return updated
        }

        val nextOrder = (all.maxOfOrNull { it.orderIndex } ?: -1) + 1
        val newItem = ProjectItem(
            projectId = fresh.projectId, variantId = fresh.variantId, quantity = pending,
            sellPrice = variant.defaultSellPrice, fromStock = false,
            lastKnownPurchase = variant.defaultPurchasePrice, isManual = false,
            orderIndex = nextOrder, isShortage = true,
            shortageRemaining = pending, shortageAddedQty = 0
        )
        val id = db.projectDao().insertItem(newItem)
        return newItem.copy(id = id)
    }

    fun fulfillShortageItemManually(item: ProjectItem, purchasePrice: Double, sellPrice: Double, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val fresh = db.projectDao().getItems(item.projectId).first().find { it.id == item.id } ?: return@launch
            if (!fresh.isShortage || fresh.shortageRemaining <= 0) return@launch
            if (db.projectDao().consumeShortagePiece(fresh.id) <= 0) return@launch
            db.projectDao().markShortageManualWithPrices(fresh.id, purchasePrice, sellPrice)
            record(
                LogCategory.BOARDS, LogAction.EDIT, projectName(fresh.projectId),
                "توفير نقص يدوياً · ${pieceLabel(fresh.variantId)} · شراء ${value(purchasePrice)} · بيع ${value(sellPrice)}"
            )
            onResult("تم توفير قطعة يدوياً بأسعار هذا المشروع")
        }
    }

    fun addManualComponentToProject(projectId: Long, name: String, purchasePrice: Double, sellPrice: Double, qty: Int) {
        if (name.isBlank() || qty < 1) return
        viewModelScope.launch {
            val safePurchase = purchasePrice.coerceAtLeast(0.0)
            val safeSell = sellPrice.coerceAtLeast(0.0)
            val existingItems = db.projectDao().getItems(projectId).first()
            val existingItem = existingItems.find { it.isManual && it.manualName.equals(name, ignoreCase = true) && pricesEqual(it.sellPrice, safeSell) && pricesEqual(it.lastKnownPurchase, safePurchase) }
            if (existingItem != null) {
                db.projectDao().updateItem(existingItem.copy(quantity = existingItem.quantity + qty))
            } else {
                val nextOrder = (existingItems.maxOfOrNull { it.orderIndex } ?: -1) + 1
                val item = ProjectItem(
                    projectId = projectId, variantId = null, manualName = name, quantity = qty,
                    sellPrice = safeSell, fromStock = false, lastKnownPurchase = safePurchase,
                    isManual = true, orderIndex = nextOrder
                )
                db.projectDao().insertItem(item)
            }
            record(
                LogCategory.BOARDS, LogAction.ADD, projectName(projectId),
                "قطعة يدوية: $name · الكمية $qty · شراء ${value(safePurchase)} · بيع ${value(safeSell)}"
            )
        }
    }

    private suspend fun recordItemQuantity(projectId: Long, itemId: Long, label: String, before: Int, after: Int) {
        val diff = after - before
        if (diff == 0) return
        record(
            LogCategory.BOARDS, LogAction.EDIT, projectName(projectId),
            entityKey = "project-item:$itemId:$diff",
            mergeWindowMs = MergeWindowMs,
            changeDirection = if (diff > 0) 1 else -1,
            changeLabel = label,
            changeBefore = before,
            changeAfter = after
        )
    }

    fun changeProjectItemQuantity(
        projectId: Long,
        itemId: Long,
        delta: Int,
        onStockLimit: (Int) -> Unit = {},
        onPriceChanged: (Int, Double, Double) -> Unit = { _, _, _ -> }
    ) {
        if (delta == 0) return
        viewModelScope.launch {
            val fresh = db.projectDao().getItems(projectId).first().find { it.id == itemId } ?: return@launch
            if (fresh.isShortage) return@launch

            val variant = if (fresh.variantId != null) db.componentDao().getAllVariantsOnce().find { it.id == fresh.variantId } else null
            if (variant == null || !fresh.fromStock) {
                db.projectDao().changeItemQuantity(itemId, delta)
                val label = if (fresh.variantId == null || fresh.isManual) fresh.manualName.ifBlank { "قطعة يدوية" }
                            else pieceLabel(fresh.variantId)
                recordItemQuantity(projectId, itemId, label, fresh.quantity, fresh.quantity + delta)
                return@launch
            }

            if (delta < 0) {
                // لا تنقص تحت قطعة واحدة: القطع المُعاد لها تعود للمخزون
                val back = minOf(-delta, fresh.quantity - 1)
                if (back <= 0) return@launch
                db.componentDao().adjustStock(variant.id, back)
                db.projectDao().changeItemQuantity(itemId, -back)
                recordItemQuantity(projectId, itemId, pieceLabel(variant.id), fresh.quantity, fresh.quantity - back)
                return@launch
            }

            // كل قطعة تُسحب من المخزون تُحتسب بسعرها الحالي: عند تغيّر السعر تُضاف في بطاقة جيل جديد
            val added = minOf(delta, variant.stockCount).coerceAtLeast(0)
            if (added < delta) onStockLimit(variant.stockCount)
            if (added <= 0) return@launch
            db.componentDao().adjustStock(variant.id, -added)

            val sameGeneration = pricesEqual(fresh.sellPrice, variant.defaultSellPrice) &&
                pricesEqual(fresh.lastKnownPurchase, variant.defaultPurchasePrice)
            if (sameGeneration) {
                db.projectDao().changeItemQuantity(itemId, added)
                recordItemQuantity(projectId, itemId, pieceLabel(variant.id), fresh.quantity, fresh.quantity + added)
                return@launch
            }

            val all = db.projectDao().getItems(projectId).first()
            val target = all.find {
                it.id != fresh.id && !it.isManual && !it.isShortage && it.fromStock &&
                    it.variantId == variant.id &&
                    pricesEqual(it.sellPrice, variant.defaultSellPrice) &&
                    pricesEqual(it.lastKnownPurchase, variant.defaultPurchasePrice)
            }
            if (target != null) {
                db.projectDao().changeItemQuantity(target.id, added)
            } else {
                val nextOrder = (all.maxOfOrNull { it.orderIndex } ?: -1) + 1
                db.projectDao().insertItem(
                    ProjectItem(
                        projectId = projectId, variantId = variant.id, quantity = added,
                        sellPrice = variant.defaultSellPrice, fromStock = true,
                        lastKnownPurchase = variant.defaultPurchasePrice, isManual = false,
                        orderIndex = nextOrder
                    )
                )
            }
            val beforeQty = target?.quantity ?: 0
            if (target != null) {
                recordItemQuantity(projectId, target.id, pieceLabel(variant.id), beforeQty, beforeQty + added)
            } else {
                // بطاقة جيل جديد: لا معرّف لها بعد، فيُربط السجل بمفتاح الحجم بدل معرّف القطعة المضغوطة
                record(
                    LogCategory.BOARDS, LogAction.EDIT, projectName(projectId),
                    entityKey = "project-newcard:${variant.id}",
                    mergeWindowMs = MergeWindowMs,
                    changeDirection = 1,
                    changeLabel = pieceLabel(variant.id),
                    changeBefore = 0,
                    changeAfter = added
                )
            }
            onPriceChanged(added, variant.defaultPurchasePrice, variant.defaultSellPrice)
        }
    }
    fun removeProjectItem(item: ProjectItem) {
        viewModelScope.launch {
            if (item.variantId != null) {
                if (item.fromStock && item.quantity > 0) {
                    db.componentDao().adjustStock(item.variantId, item.quantity)
                }
                if (item.isShortage && item.shortageFromStock > 0) {
                    db.componentDao().adjustStock(item.variantId, item.shortageFromStock)
                }
            }
            db.projectDao().deleteItem(item)
            record(
                LogCategory.BOARDS, LogAction.DELETE, projectName(item.projectId),
                "قطعة: ${if (item.isManual) item.manualName.ifBlank { "قطعة يدوية" } else pieceLabel(item.variantId)} · الكمية ${item.quantity}"
            )
        }
    }
    fun reorderProjectItems(items: List<ProjectItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            // الحفظ من صفوف حديثة حتى لا تُرجع إعادة الترتيب حقولاً قديمة كالكمية
            db.withTransaction {
                val fresh = db.projectDao().getItems(items.first().projectId).first().associateBy { it.id }
                items.forEachIndexed { index, item ->
                    fresh[item.id]?.let { db.projectDao().updateItem(it.copy(orderIndex = index)) }
                }
            }
            val projectId = items.first().projectId
            record(
                LogCategory.BOARDS, LogAction.REORDER, projectName(projectId),
                "أُعيد ترتيب قطع البورد",
                entityKey = "project-order:$projectId",
                mergeWindowMs = MergeWindowLongMs
            )
        }
    }

    fun addSupplier(s: Supplier) {
        viewModelScope.launch {
            val existing = db.supplierDao().getAll().first()
            val nextOrder = (existing.maxOfOrNull { it.orderIndex } ?: -1) + 1
            val safe = s.copy(orderIndex = nextOrder, deliveryCost = s.deliveryCost.coerceAtLeast(0.0), defaultMinOrder = s.defaultMinOrder.coerceAtLeast(1))
            db.supplierDao().insert(safe)
            record(
                LogCategory.SUPPLIERS, LogAction.ADD, safe.name,
                buildString {
                    if (safe.phone.isNotBlank()) append("هاتف ${safe.phone} · ")
                    append("توصيل ${value(safe.deliveryCost)} · أقل كمية ${safe.defaultMinOrder}")
                }
            )
        }
    }
    fun updateSupplier(s: Supplier) {
        viewModelScope.launch {
            val safe = s.copy(deliveryCost = s.deliveryCost.coerceAtLeast(0.0), defaultMinOrder = s.defaultMinOrder.coerceAtLeast(1))
            val old = db.supplierDao().getByIdOnce(safe.id)
            db.supplierDao().update(safe)
            if (old == null) return@launch
            val changes = mutableListOf<String>()
            if (old.name.trim() != safe.name.trim()) changes += "الاسم: من «${old.name}» إلى «${safe.name}»"
            if (old.phone.trim() != safe.phone.trim()) changes += "الهاتف: ${safe.phone.ifBlank { "فارغ" }}"
            if (!pricesEqual(old.deliveryCost, safe.deliveryCost)) changes += "التوصيل: من ${value(old.deliveryCost)} إلى ${value(safe.deliveryCost)}"
            if (old.defaultMinOrder != safe.defaultMinOrder) changes += "أقل كمية: من ${old.defaultMinOrder} إلى ${safe.defaultMinOrder}"
            if (changes.isNotEmpty()) record(LogCategory.SUPPLIERS, LogAction.EDIT, safe.name, changes.joinToString("\n"))
        }
    }
    fun deleteSupplier(s: Supplier) {
        viewModelScope.launch {
            val linkedOffers = db.supplierDao().countOffers(s.id)
            db.supplierDao().delete(s)
            record(
                LogCategory.SUPPLIERS, LogAction.DELETE, s.name,
                if (linkedOffers > 0) "حُذف المزود وفُكّت $linkedOffers روابط أسعار" else "حُذف المزود"
            )
        }
    }
    fun reorderSuppliers(items: List<Supplier>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            db.withTransaction {
                val fresh = db.supplierDao().getAll().first().associateBy { it.id }
                items.forEachIndexed { index, item ->
                    fresh[item.id]?.let { db.supplierDao().update(it.copy(orderIndex = index)) }
                }
            }
            record(
                LogCategory.SUPPLIERS, LogAction.REORDER, "المزودون",
                "أُعيد ترتيب قائمة المزودين",
                entityKey = "supplier-order",
                mergeWindowMs = MergeWindowLongMs
            )
        }
    }

    // قواعد المالك: اسم القطعة فريد في المخزون، والبيع لا يقل عن الشراء بعد تحديد السعرين
    private suspend fun componentRejection(name: String, purchase: Double, sell: Double, selfId: Long): String? {
        val clean = normalizeItemName(name)
        if (clean.isEmpty()) return "اكتب اسم القطعة"
        val all = db.componentDao().getAllOnce()
        all.firstOrNull { it.id != selfId && namesEqual(it.name, clean) }?.let {
            return "يوجد قطعة بنفس الاسم: ${it.name}"
        }
        if (isSellBelowPurchase(sell, purchase)) return sellBelowPurchaseMessage(sell, purchase)
        return null
    }

    // الحجم الافتراضي يُسمّى ويُعاد تسميته بحرية، لكن لا يتكرر اسم بين أحجام القطعة نفسها
    private suspend fun variantRejection(componentId: Long, spec: String, purchase: Double, sell: Double, selfId: Long): String? {
        val clean = normalizeItemName(spec)
        if (clean.isEmpty()) return "اكتب اسم الحجم"
        if (isSellBelowPurchase(sell, purchase)) return sellBelowPurchaseMessage(sell, purchase)
        val comp = db.componentDao().getAllOnce().find { it.id == componentId } ?: return null
        val dup = db.componentDao().getAllVariantsOnce().firstOrNull {
            it.componentId == componentId && it.id != selfId &&
                // الاسم الخفي لحجم القطعة الاعتيادية ليس اسماً حقيقياً — سيصبح «إفتراضي» عند أول حجم
                !(it.isBase && namesEqual(it.specName, comp.name)) &&
                namesEqual(it.specName, clean)
        }
        if (dup != null) return "هذا الاسم مستخدم في حجم آخر: ${dup.specName}"
        return null
    }

    fun addPlainComponent(name: String, imageUri: String, purchasePrice: Double, sellPrice: Double, stock: Int, onDone: (Component) -> Unit = {}, onRejected: (String) -> Unit = {}) {
        viewModelScope.launch {
            componentRejection(name, purchasePrice, sellPrice, 0L)?.let { onRejected(it); return@launch }
            val cleanName = normalizeItemName(name)
            val existing = db.componentDao().getAll().first()
            val nextOrder = (existing.maxOfOrNull { it.orderIndex } ?: -1) + 1
            val comp = Component(
                name = cleanName,
                imageUri = imageUri,
                orderIndex = nextOrder,
                stockCount = stock.coerceAtLeast(0),
                purchasePrice = purchasePrice.coerceAtLeast(0.0),
                sellPrice = sellPrice.coerceAtLeast(0.0)
            )
            val id = db.componentDao().insertComponent(comp)
            record(
                LogCategory.INVENTORY, LogAction.ADD, cleanName,
                "قطعة جديدة · شراء ${value(comp.purchasePrice)} · بيع ${value(comp.sellPrice)} · مخزون ${comp.stockCount}"
            )
            onDone(comp.copy(id = id))
        }
    }

    fun addComponentWithVariant(name: String, imageUri: String, purchasePrice: Double, sellPrice: Double, stock: Int, onDone: (Component, ComponentVariant) -> Unit, onRejected: (String) -> Unit = {}) {
        viewModelScope.launch {
            componentRejection(name, purchasePrice, sellPrice, 0L)?.let { onRejected(it); return@launch }
            val cleanName = normalizeItemName(name)
            val existing = db.componentDao().getAll().first()
            val nextOrder = (existing.maxOfOrNull { it.orderIndex } ?: -1) + 1
            val safePurchase = purchasePrice.coerceAtLeast(0.0)
            val safeSell = sellPrice.coerceAtLeast(0.0)
            val comp = Component(
                name = cleanName,
                imageUri = imageUri,
                orderIndex = nextOrder,
                purchasePrice = safePurchase,
                sellPrice = safeSell
            )
            val compId = db.componentDao().insertComponent(comp)
            val variant = ComponentVariant(
                componentId = compId,
                specName = comp.name,
                defaultPurchasePrice = safePurchase,
                defaultSellPrice = safeSell,
                stockCount = stock.coerceAtLeast(0),
                isBase = true
            )
            val varId = db.componentDao().insertVariant(variant)
            record(
                LogCategory.INVENTORY, LogAction.ADD, cleanName,
                "قطعة بحجم ${variant.specName} · شراء ${value(safePurchase)} · بيع ${value(safeSell)} · مخزون ${variant.stockCount}"
            )
            onDone(comp.copy(id = compId), variant.copy(id = varId))
        }
    }

    fun updateComponent(c: Component, onRejected: (String) -> Unit = {}, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            componentRejection(c.name, c.purchasePrice, c.sellPrice, c.id)?.let { onRejected(it); return@launch }
            val old = db.componentDao().getComponentOnce(c.id)
            // اسم القطعة مستقل عن اسم الحجم الافتراضي فلا يُفرض عليه
            val clean = c.copy(name = normalizeItemName(c.name))
            db.componentDao().updateComponent(clean)
            if (old != null) {
                val changes = mutableListOf<String>()
                if (!namesEqual(old.name, clean.name)) changes += "الاسم: من «${old.name}» إلى «${clean.name}»"
                if (old.imageUri != clean.imageUri) changes += if (clean.imageUri.isBlank()) "أُزيلت صورة القطعة" else "تغيّرت صورة القطعة"
                if (!pricesEqual(old.purchasePrice, clean.purchasePrice)) changes += "الشراء: من ${value(old.purchasePrice)} إلى ${value(clean.purchasePrice)}"
                if (!pricesEqual(old.sellPrice, clean.sellPrice)) changes += "البيع: من ${value(old.sellPrice)} إلى ${value(clean.sellPrice)}"
                if (old.stockCount != clean.stockCount) changes += "المخزون: من ${old.stockCount} إلى ${clean.stockCount}"
                if (changes.isNotEmpty()) record(LogCategory.INVENTORY, LogAction.EDIT, clean.name, changes.joinToString("\n"))
            }
            onDone()
        }
    }
    fun deleteComponent(c: Component, confirmed: Boolean = false, onResult: (DeleteOutcome) -> Unit = {}) {
        viewModelScope.launch {
            val inActiveProjects = db.componentDao().countActiveProjectItemsUsingComponent(c.id)
            if (inActiveProjects > 0) {
                onResult(DeleteOutcome.Blocked("لا يمكن حذف القطعة: مستخدمة في $inActiveProjects بورد غير مكتمل."))
                return@launch
            }
            val inOffers = db.componentDao().countOffersUsingComponent(c.id)
            if (inOffers > 0) {
                onResult(DeleteOutcome.Blocked("لا يمكن حذف القطعة: مرتبطة بـ $inOffers عرض مزود."))
                return@launch
            }
            val templateCount = db.componentDao().countTemplatesUsingComponent(c.id)
            if (templateCount > 0 && !confirmed) {
                onResult(DeleteOutcome.NeedsTemplateConfirm("القطعة موجودة في $templateCount قالب. حذفها يفصلها عن القوالب كبطاقات محذوفة من المخزون.", templateCount))
                return@launch
            }
            db.withTransaction {
                val variantsOfComp = db.componentDao().getAllVariantsOnce().filter { it.componentId == c.id }
                // بورد مكتمل يحتفظ باسم القطعة ليظل مقروءاً بعد حذفها من المخزون
                for (item in db.componentDao().projectItemsUsingComponent(c.id)) {
                    if (!item.isManual && item.manualName.isBlank()) {
                        db.projectDao().updateItem(item.copy(manualName = c.name))
                    }
                }
                val affected = db.componentDao().templateItemsUsingComponent(c.id)
                for (item in affected) {
                    val variant = variantsOfComp.firstOrNull { it.id == item.variantId }
                    db.templateDao().insertItem(item.copy(
                        variantId = null,
                        manualName = componentLabel(c.name, variant),
                        purchasePrice = variant?.defaultPurchasePrice ?: item.purchasePrice,
                        sellPrice = variant?.defaultSellPrice ?: item.sellPrice
                    ))
                }
                moveTemplateItemsToEnd(affected)
                db.tagDao().clearLinks(c.id)
                db.componentDao().deleteComponent(c)
                db.tagDao().deleteUnusedTags()
            }
            record(
                LogCategory.INVENTORY, LogAction.DELETE, c.name,
                if (templateCount > 0) "حُذفت القطعة من المخزون · فُصلت عن $templateCount قالب"
                else "حُذفت القطعة من المخزون"
            )
            onResult(DeleteOutcome.Deleted)
        }
    }

    // البطاقات المفصولة عن المخزون تُصفّ في آخر قالبها — ترتيب يوحي بأنها خرجت من المخزون
    private suspend fun moveTemplateItemsToEnd(items: List<TemplateItem>) {
        if (items.isEmpty()) return
        val movedIds = items.map { it.id }.toSet()
        for (templateId in items.map { it.templateId }.distinct()) {
            val fresh = db.templateDao().getItemsOnce(templateId).sortedBy { it.orderIndex }
            val ordered = fresh.filterNot { it.id in movedIds } + fresh.filter { it.id in movedIds }
            ordered.forEachIndexed { index, item -> db.templateDao().insertItem(item.copy(orderIndex = index)) }
        }
    }

    fun reorderComponents(items: List<Component>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            db.withTransaction {
                val fresh = db.componentDao().getAllOnce().associateBy { it.id }
                items.forEachIndexed { index, item ->
                    fresh[item.id]?.let { db.componentDao().updateComponent(it.copy(orderIndex = index)) }
                }
            }
            record(
                LogCategory.INVENTORY, LogAction.REORDER, "المخزون",
                "أُعيد ترتيب قطع المخزون",
                entityKey = "component-order",
                mergeWindowMs = MergeWindowLongMs
            )
        }
    }

    // ===== الوسوم =====

    // يستبدل وسوم القطعة بالكامل — المصدر الوحيد لتحرير الوسوم في الواجهة
    fun setComponentTags(componentId: Long, names: List<String>) {
        viewModelScope.launch {
            db.withTransaction {
                db.tagDao().clearLinks(componentId)
                for (raw in names) {
                    val name = normalizeTagName(raw)
                    if (name.isEmpty()) continue
                    var tag = db.tagDao().findByNormalizedName(name)
                    if (tag == null) {
                        val newId = db.tagDao().insertTag(
                            Tag(name = name, colorIndex = tagColorIndexForName(name), createdAt = System.currentTimeMillis())
                        )
                        tag = if (newId > 0) Tag(id = newId, name = name, colorIndex = tagColorIndexForName(name))
                              else db.tagDao().findByNormalizedName(name)
                    }
                    if (tag != null) db.tagDao().insertLink(ComponentTag(componentId = componentId, tagId = tag.id))
                }
                db.tagDao().deleteUnusedTags()
            }
        }
    }

    // إعادة تسمية: إن كان الاسم موجوداً تُدمج القطع تحت الوسم الموجود بدل التعارض
    fun renameTag(tagId: Long, rawName: String, onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        val name = normalizeTagName(rawName)
        if (name.isEmpty()) { onDone(false, "اكتب اسم الوسم"); return }
        viewModelScope.launch {
            var logTitle: String? = null
            var logDetails: String? = null
            db.withTransaction {
                val current = db.tagDao().getAllOnce().firstOrNull { it.id == tagId }
                if (current == null) { onDone(false, "الوسم لم يعد موجوداً"); return@withTransaction }
                if (normalizeTagName(current.name) == name) { onDone(true, ""); return@withTransaction }
                val clash = db.tagDao().findByNormalizedName(name)?.takeIf { it.id != tagId }
                if (clash == null) {
                    db.tagDao().renameTag(tagId, name)
                    logTitle = name
                    logDetails = "تغيّر اسم الوسم: من «${current.name}» إلى «$name»"
                    onDone(true, "")
                } else {
                    val moved = db.tagDao().linksOfTag(tagId)
                    db.tagDao().clearTagLinks(tagId)
                    db.tagDao().deleteTag(current)
                    for (l in moved) db.tagDao().insertLink(ComponentTag(componentId = l.componentId, tagId = clash.id))
                    logTitle = clash.name
                    logDetails = "دُمج الوسم «${current.name}» مع «${clash.name}» · ${moved.size} قطعة"
                    onDone(true, "كان يوجد وسم بالاسم نفسه — تم الدمج معه")
                }
            }
            val title = logTitle
            if (title != null) record(LogCategory.INVENTORY, LogAction.EDIT, title, logDetails ?: "")
        }
    }

    fun setTagColor(tagId: Long, colorIndex: Int) {
        viewModelScope.launch {
            val tag = db.tagDao().getAllOnce().firstOrNull { it.id == tagId } ?: return@launch
            val safe = colorIndex.mod(TagPalette.size)
            if (tag.colorIndex == safe) return@launch
            db.tagDao().setTagColor(tagId, safe)
            record(LogCategory.INVENTORY, LogAction.EDIT, tag.name, "تغيّر لون الوسم")
        }
    }

    fun deleteTag(tagId: Long) {
        viewModelScope.launch {
            val tag = db.tagDao().getAllOnce().firstOrNull { it.id == tagId } ?: return@launch
            val links = db.tagDao().linksOfTag(tagId).size
            db.withTransaction {
                db.tagDao().clearTagLinks(tagId)
                db.tagDao().deleteTagById(tagId)
            }
            record(
                LogCategory.INVENTORY, LogAction.DELETE, tag.name,
                if (links > 0) "حُذف الوسم وأُزيل عن $links قطعة" else "حُذف الوسم"
            )
        }
    }

    fun addVariant(v: ComponentVariant, onRejected: (String) -> Unit = {}, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            variantRejection(v.componentId, v.specName, v.defaultPurchasePrice, v.defaultSellPrice, 0L)?.let { onRejected(it); return@launch }
            val cleanSpec = normalizeItemName(v.specName)
            db.withTransaction {
                val comp = db.componentDao().getAllOnce().find { it.id == v.componentId } ?: return@withTransaction
                val existing = db.componentDao().getAllVariantsOnce().filter { it.componentId == v.componentId }
                if (existing.isEmpty()) {
                    // أول حجم يُضاف: تُنشأ قبله قطعة الحجم الافتراضي حاملة بيانات القطعة، فيصبح هو الحجم الثاني
                    db.componentDao().insertVariant(
                        ComponentVariant(
                            componentId = comp.id,
                            specName = DEFAULT_VARIANT_NAME,
                            defaultPurchasePrice = comp.purchasePrice.coerceAtLeast(0.0),
                            defaultSellPrice = comp.sellPrice.coerceAtLeast(0.0),
                            stockCount = comp.stockCount.coerceAtLeast(0),
                            isBase = true
                        )
                    )
                    if (comp.stockCount != 0) db.componentDao().updateComponent(comp.copy(stockCount = 0))
                } else {
                    // القطعة كانت بلا أحجام فحُملت بياناتها في حجم خفي باسمها — يصبح الآن الحجم الافتراضي
                    val base = existing.firstOrNull { it.isBase }
                    if (base != null && namesEqual(base.specName, comp.name)) {
                        db.componentDao().updateVariant(base.copy(specName = DEFAULT_VARIANT_NAME))
                    }
                }
                db.componentDao().insertVariant(v.copy(
                    specName = cleanSpec,
                    defaultPurchasePrice = v.defaultPurchasePrice.coerceAtLeast(0.0),
                    defaultSellPrice = v.defaultSellPrice.coerceAtLeast(0.0),
                    stockCount = v.stockCount.coerceAtLeast(0),
                    isBase = false
                ))
            }
            val compName = db.componentDao().getComponentOnce(v.componentId)?.name ?: "قطعة"
            record(
                LogCategory.INVENTORY, LogAction.ADD, compName,
                "حجم جديد: $cleanSpec · شراء ${value(v.defaultPurchasePrice)} · بيع ${value(v.defaultSellPrice)} · مخزون ${v.stockCount}"
            )
            onDone()
        }
    }
    fun updateVariant(v: ComponentVariant, onRejected: (String) -> Unit = {}, onDone: () -> Unit = {}, silent: Boolean = false) {
        viewModelScope.launch {
            variantRejection(v.componentId, v.specName, v.defaultPurchasePrice, v.defaultSellPrice, v.id)?.let { onRejected(it); return@launch }
            val old = db.componentDao().getVariantOnce(v.id)
            // اسم الحجم مستقل عن اسم القطعة: تعديله لا يعيد تسمية القطعة
            val clean = v.copy(
                specName = normalizeItemName(v.specName),
                defaultPurchasePrice = v.defaultPurchasePrice.coerceAtLeast(0.0),
                defaultSellPrice = v.defaultSellPrice.coerceAtLeast(0.0),
                stockCount = v.stockCount.coerceAtLeast(0)
            )
            db.componentDao().updateVariant(clean)
            val compName = db.componentDao().getComponentOnce(v.componentId)?.name
            if (old != null && compName != null) {
                if (silent) {
                    // تعديل القطعة يعدّل الحجم الوحيد معها؛ يُسجَّل هنا فرق المخزون فقط حتى لا يتكرر السجل
                    if (old.stockCount != clean.stockCount) {
                        val diff = clean.stockCount - old.stockCount
                        record(
                            LogCategory.INVENTORY, LogAction.STOCK, compName,
                            entityKey = "variant-stock:${v.id}:$diff",
                            mergeWindowMs = MergeWindowMs,
                            changeDirection = if (diff > 0) 1 else -1,
                            changeLabel = compName,
                            changeBefore = old.stockCount,
                            changeAfter = clean.stockCount
                        )
                    }
                } else {
                    val changes = mutableListOf<String>()
                    if (!namesEqual(old.specName, clean.specName)) changes += "الاسم: من «${old.specName}» إلى «${clean.specName}»"
                    if (!pricesEqual(old.defaultPurchasePrice, clean.defaultPurchasePrice)) changes += "الشراء: من ${value(old.defaultPurchasePrice)} إلى ${value(clean.defaultPurchasePrice)}"
                    if (!pricesEqual(old.defaultSellPrice, clean.defaultSellPrice)) changes += "البيع: من ${value(old.defaultSellPrice)} إلى ${value(clean.defaultSellPrice)}"
                    if (old.stockCount != clean.stockCount) changes += "المخزون: من ${old.stockCount} إلى ${clean.stockCount}"
                    if (changes.isNotEmpty()) record(
                        LogCategory.INVENTORY, LogAction.EDIT, compName,
                        "حجم ${clean.specName}\n${changes.joinToString("\n")}"
                    )
                }
            }
            onDone()
        }
    }
    fun adjustVariantStock(variantId: Long, delta: Int) {
        if (delta == 0) return
        viewModelScope.launch {
            val before = db.componentDao().getVariantOnce(variantId)?.stockCount ?: return@launch
            db.componentDao().adjustStockClamped(variantId, delta)
            val after = db.componentDao().getVariantOnce(variantId)?.stockCount ?: return@launch
            if (after == before) return@launch
            val label = pieceLabel(variantId)
            record(
                LogCategory.INVENTORY, LogAction.STOCK, label,
                entityKey = "variant-stock:$variantId:${after - before}",
                mergeWindowMs = MergeWindowMs,
                changeDirection = if (after > before) 1 else -1,
                changeLabel = label,
                changeBefore = before,
                changeAfter = after
            )
        }
    }
    fun adjustPlainComponentStock(componentId: Long, delta: Int) {
        if (delta == 0) return
        viewModelScope.launch {
            val comp = db.componentDao().getComponentOnce(componentId) ?: return@launch
            val before = comp.stockCount.coerceAtLeast(0)
            db.componentDao().adjustComponentStockClamped(componentId, delta)
            val after = db.componentDao().getComponentOnce(componentId)?.stockCount ?: return@launch
            if (after == before) return@launch
            record(
                LogCategory.INVENTORY, LogAction.STOCK, comp.name,
                entityKey = "component-stock:$componentId:${after - before}",
                mergeWindowMs = MergeWindowMs,
                changeDirection = if (after > before) 1 else -1,
                changeLabel = comp.name,
                changeBefore = before,
                changeAfter = after
            )
        }
    }
    fun adjustOfferPrice(offerId: Long, delta: Double) {
        if (delta == 0.0) return
        viewModelScope.launch {
            val offer = db.componentDao().getOfferOnce(offerId) ?: return@launch
            db.componentDao().adjustOfferPrice(offerId, delta)
            val remaining = db.componentDao().getOfferOnce(offerId)?.purchasePrice
            record(
                LogCategory.SUPPLIERS, LogAction.EDIT, supplierName(offer.supplierId),
                buildString {
                    append("سعر ${pieceLabel(offer.variantId)}: ")
                    append(if (delta > 0) "زيادة ${value(delta)}" else "إنقاص ${value(abs(delta))}")
                    if (remaining != null) append(" · أصبح ${value(remaining)}")
                },
                entityKey = "offer-price:$offerId:$delta",
                mergeWindowMs = MergeWindowMs
            )
        }
    }
    fun deleteVariant(v: ComponentVariant, confirmed: Boolean = false, onResult: (DeleteOutcome) -> Unit = {}) {
        viewModelScope.launch {
            val inActiveProjects = db.componentDao().countActiveProjectItemsUsingVariant(v.id)
            if (inActiveProjects > 0) {
                onResult(DeleteOutcome.Blocked("لا يمكن حذف الحجم: مستخدم في $inActiveProjects بورد غير مكتمل."))
                return@launch
            }
            val inOffers = db.componentDao().countOffersUsingVariant(v.id)
            if (inOffers > 0) {
                onResult(DeleteOutcome.Blocked("لا يمكن حذف الحجم: مرتبط بـ $inOffers عرض من المزودين."))
                return@launch
            }
            val templateCount = db.componentDao().countTemplatesUsingVariant(v.id)
            if (templateCount > 0 && !confirmed) {
                onResult(DeleteOutcome.NeedsTemplateConfirm("الحجم موجود في $templateCount قالب. حذفه يفصله عن القوالب كبطاقات محذوفة من المخزون.", templateCount))
                return@launch
            }
            var failure: String? = null
            db.withTransaction {
                val siblings = db.componentDao().getAllVariantsOnce().filter { it.componentId == v.componentId }
                val comp = db.componentDao().getAllOnce().find { it.id == v.componentId }
                if (siblings.isEmpty() || comp == null) {
                    failure = "تعذر العثور على الحجم."
                    return@withTransaction
                }
                if (v.isBase && siblings.size > 1) {
                    failure = "الحجم الافتراضي لا يُحذف ما دامت هناك أحجام أخرى — احذف الأحجام المضافة أولاً."
                    return@withTransaction
                }
                // بورد مكتمل يحتفظ باسم الحجم ليظل مقروءاً بعد حذفه من المخزون
                val label = componentLabel(comp.name, v)
                for (item in db.componentDao().projectItemsUsingVariant(v.id)) {
                    if (!item.isManual && item.manualName.isBlank()) {
                        db.projectDao().updateItem(item.copy(manualName = label))
                    }
                }
                val affected = db.componentDao().templateItemsUsingVariant(v.id)
                for (item in affected) {
                    db.templateDao().insertItem(item.copy(
                        variantId = null,
                        manualName = label,
                        purchasePrice = v.defaultPurchasePrice,
                        sellPrice = v.defaultSellPrice
                    ))
                }
                moveTemplateItemsToEnd(affected)
                db.componentDao().deleteVariant(v)
                val remaining = siblings.filter { it.id != v.id }
                if (remaining.isEmpty()) {
                    // حُذف آخر حجم: تعود القطعة اعتيادية ببيانات الحجم الافتراضي، ويختفي اسم الحجم
                    db.componentDao().updateComponent(comp.copy(
                        stockCount = v.stockCount.coerceAtLeast(0),
                        purchasePrice = v.defaultPurchasePrice.coerceAtLeast(0.0),
                        sellPrice = v.defaultSellPrice.coerceAtLeast(0.0)
                    ))
                } else if (remaining.size == 1 && remaining[0].isBase) {
                    // بقي الحجم الافتراضي وحده: يعود تدريجياً قطعة اعتيادية فيختفي اسمه ويبقى مخزونه
                    db.componentDao().updateVariant(remaining[0].copy(specName = comp.name))
                }
            }
            if (failure != null) onResult(DeleteOutcome.Blocked(failure!!)) else {
                val compName = db.componentDao().getComponentOnce(v.componentId)?.name ?: "قطعة"
                record(
                    LogCategory.INVENTORY, LogAction.DELETE, compName,
                    "حُذف الحجم «${v.specName}»" + if (templateCount > 0) " · فُصل عن $templateCount قالب" else ""
                )
                onResult(DeleteOutcome.Deleted)
            }
        }
    }

    // قطعة بورد تشير لحجم حُذف من المخزون: تُفصل عن المخزون وتُدار يدوياً بأسعارها المحفوظة
    fun resolveProjectItemConflict(
        item: ProjectItem,
        name: String,
        purchasePrice: Double,
        sellPrice: Double,
        onRejected: (String) -> Unit = {},
        onDone: () -> Unit = {}
    ) {
        val clean = normalizeItemName(name)
        if (clean.isEmpty()) { onRejected("اكتب اسم القطعة"); return }
        val safePurchase = purchasePrice.coerceAtLeast(0.0)
        val safeSell = sellPrice.coerceAtLeast(0.0)
        if (isSellBelowPurchase(safeSell, safePurchase)) { onRejected(sellBelowPurchaseMessage(safeSell, safePurchase)); return }
        viewModelScope.launch {
            val fresh = db.projectDao().getItems(item.projectId).first().find { it.id == item.id } ?: return@launch
            db.projectDao().updateItem(fresh.copy(
                variantId = null,
                manualName = clean,
                isManual = true,
                fromStock = false,
                isCustomSell = false,
                sellPrice = safeSell,
                lastKnownPurchase = safePurchase
            ))
            record(
                LogCategory.BOARDS, LogAction.EDIT, projectName(item.projectId),
                "أُعيد ضبط قطعة مفصولة عن المخزون: $clean · شراء ${value(safePurchase)} · بيع ${value(safeSell)}"
            )
            onDone()
        }
    }
    fun addOffer(o: SupplierOffer) {
        viewModelScope.launch {
            val safe = o.copy(purchasePrice = o.purchasePrice.coerceAtLeast(0.0), minOrder = o.minOrder.coerceAtLeast(1))
            db.componentDao().insertOffer(safe)
            record(
                LogCategory.SUPPLIERS, LogAction.ADD, supplierName(safe.supplierId),
                "عرض سعر: ${pieceLabel(safe.variantId)} · شراء ${value(safe.purchasePrice)} · أقل كمية ${safe.minOrder}"
            )
        }
    }
    fun updateOffer(o: SupplierOffer) {
        viewModelScope.launch {
            val safe = o.copy(purchasePrice = o.purchasePrice.coerceAtLeast(0.0), minOrder = o.minOrder.coerceAtLeast(1))
            val old = db.componentDao().getOfferOnce(safe.id)
            db.componentDao().updateOffer(safe)
            val label = pieceLabel(safe.variantId)
            if (old == null) {
                record(LogCategory.SUPPLIERS, LogAction.EDIT, supplierName(safe.supplierId), "تعديل عرض سعر: $label")
                return@launch
            }
            val changes = mutableListOf<String>()
            if (old.variantId != safe.variantId) changes += "القطعة: من ${pieceLabel(old.variantId)} إلى $label"
            if (!pricesEqual(old.purchasePrice, safe.purchasePrice)) changes += "الشراء: من ${value(old.purchasePrice)} إلى ${value(safe.purchasePrice)}"
            if (old.minOrder != safe.minOrder) changes += "أقل كمية: من ${old.minOrder} إلى ${safe.minOrder}"
            if (changes.isNotEmpty()) record(
                LogCategory.SUPPLIERS, LogAction.EDIT, supplierName(safe.supplierId),
                "عرض $label\n${changes.joinToString("\n")}"
            )
        }
    }
    fun deleteOffer(o: SupplierOffer) {
        viewModelScope.launch {
            val label = pieceLabel(o.variantId)
            val sup = supplierName(o.supplierId)
            db.componentDao().deleteOffer(o)
            record(LogCategory.SUPPLIERS, LogAction.DELETE, sup, "حُذف عرض سعر: $label")
        }
    }

    fun addTemplate(t: Template) {
        viewModelScope.launch {
            val safe = t.sanitized()
            db.templateDao().insert(safe)
            record(
                LogCategory.TEMPLATES, LogAction.ADD, safe.name,
                "قالب جديد" + dimensionsText(safe) +
                    if (safe.laborCost > 0) " · أجور العمل ${value(safe.laborCost)}" else ""
            )
        }
    }
    fun updateTemplate(t: Template) {
        viewModelScope.launch {
            val safe = t.sanitized()
            val old = db.templateDao().getByIdOnce(safe.id)
            db.templateDao().update(safe)
            if (old == null) return@launch
            val changes = mutableListOf<String>()
            if (old.name != safe.name) changes += "الاسم: من «${old.name}» إلى «${safe.name}»"
            if (old.imageUri != safe.imageUri) changes += if (safe.imageUri.isBlank()) "أُزيلت صورة القالب" else "تغيّرت صورة القالب"
            if (!pricesEqual(old.boxWidth, safe.boxWidth) || !pricesEqual(old.boxHeight, safe.boxHeight) ||
                !pricesEqual(old.boxDepth, safe.boxDepth)
            ) changes += "الأبعاد: من ${dimensionsValue(old)} إلى ${dimensionsValue(safe)}"
            if (!pricesEqual(old.laborCost, safe.laborCost)) {
                changes += "أجور العمل: من ${value(old.laborCost)} إلى ${value(safe.laborCost)}"
            }
            if (changes.isNotEmpty()) record(LogCategory.TEMPLATES, LogAction.EDIT, safe.name, changes.joinToString("\n"))
        }
    }
    fun deleteTemplate(t: Template) {
        viewModelScope.launch {
            val items = db.templateDao().getItems(t.id).first()
            for (item in items) db.templateDao().deleteItem(item)
            db.templateDao().delete(t)
            record(
                LogCategory.TEMPLATES, LogAction.DELETE, t.name,
                if (items.isNotEmpty()) "حُذف القالب و${items.size} قطعة بداخله" else "حُذف القالب"
            )
        }
    }
    fun getTemplateItems(tId: Long): Flow<List<TemplateItem>> = db.templateDao().getItems(tId)
    fun addTemplateItem(item: TemplateItem) {
        if (item.quantity < 1) return
        viewModelScope.launch {
            val existing = db.templateDao().getItems(item.templateId).first()
            // البطاقات المفصولة عن المخزون (variantId = null ويدها=false) لا تُدمج مع جديدة
            val existingItem = when {
                item.isManual -> existing.find {
                    it.isManual && namesEqual(it.manualName, item.manualName) &&
                        pricesEqual(it.sellPrice, item.sellPrice) && pricesEqual(it.purchasePrice, item.purchasePrice)
                }
                else -> item.variantId?.let { variantId -> existing.find { !it.isManual && it.variantId == variantId } }
            }
            if (existingItem != null) {
                db.templateDao().insertItem(existingItem.copy(quantity = existingItem.quantity + item.quantity))
            } else {
                val maxOrder = existing.maxOfOrNull { it.orderIndex } ?: -1
                db.templateDao().insertItem(item.copy(orderIndex = maxOrder + 1))
            }
            record(
                LogCategory.TEMPLATES, LogAction.ADD, templateName(item.templateId),
                "قطعة: ${templateItemLabel(item)} · الكمية ${item.quantity}"
            )
        }
    }

    // عنصر يدوي داخل القالب — كقطعة يدوية في البورد، بلا ارتباط بحجم من المخزون
    fun addManualTemplateItem(
        templateId: Long,
        name: String,
        purchasePrice: Double,
        sellPrice: Double,
        qty: Int,
        onRejected: (String) -> Unit = {},
        onDone: () -> Unit = {}
    ) {
        val clean = normalizeItemName(name)
        if (clean.isEmpty()) { onRejected("اكتب اسم العنصر"); return }
        if (qty < 1) { onRejected("الكمية يجب أن تكون قطعة واحدة على الأقل"); return }
        val safePurchase = purchasePrice.coerceAtLeast(0.0)
        val safeSell = sellPrice.coerceAtLeast(0.0)
        if (isSellBelowPurchase(safeSell, safePurchase)) { onRejected(sellBelowPurchaseMessage(safeSell, safePurchase)); return }
        viewModelScope.launch {
            addTemplateItem(TemplateItem(
                templateId = templateId, variantId = null, quantity = qty,
                manualName = clean, purchasePrice = safePurchase, sellPrice = safeSell, isManual = true
            ))
            onDone()
        }
    }

    // بطاقة قالب تشير لحجم حُذف من المخزون: تُفصل عنه وتُدار يدوياً بأسعارها المحفوظة
    fun resolveTemplateItemConflict(
        item: TemplateItem,
        name: String,
        purchasePrice: Double,
        sellPrice: Double,
        onRejected: (String) -> Unit = {},
        onDone: () -> Unit = {}
    ) {
        val clean = normalizeItemName(name)
        if (clean.isEmpty()) { onRejected("اكتب اسم القطعة"); return }
        val safePurchase = purchasePrice.coerceAtLeast(0.0)
        val safeSell = sellPrice.coerceAtLeast(0.0)
        if (isSellBelowPurchase(safeSell, safePurchase)) { onRejected(sellBelowPurchaseMessage(safeSell, safePurchase)); return }
        viewModelScope.launch {
            val fresh = db.templateDao().getItems(item.templateId).first().find { it.id == item.id } ?: return@launch
            db.templateDao().insertItem(fresh.copy(
                variantId = null,
                manualName = clean,
                purchasePrice = safePurchase,
                sellPrice = safeSell,
                isManual = true
            ))
            record(
                LogCategory.TEMPLATES, LogAction.EDIT, templateName(item.templateId),
                "أُعيد ضبط قطعة مفصولة عن المخزون: $clean · شراء ${value(safePurchase)} · بيع ${value(safeSell)}"
            )
            onDone()
        }
    }

    // ===== سلة شراء المزودين =====

    val cartItems = db.cartDao().getAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun addToCart(offerId: Long, qty: Int) {
        if (qty < 1) return
        viewModelScope.launch {
            val offer = db.componentDao().getOfferOnce(offerId) ?: return@launch
            val existing = db.cartDao().getAllOnce()
            val row = existing.find { it.offerId == offerId }
            val newQty = if (row != null) row.quantity + qty else qty
            if (row != null) db.cartDao().setQuantity(row.id, newQty)
            else db.cartDao().insert(CartItem(
                offerId = offerId, quantity = qty,
                orderIndex = (existing.maxOfOrNull { it.orderIndex } ?: -1) + 1
            ))
            record(
                LogCategory.CART, LogAction.ADD, supplierName(offer.supplierId),
                "أُضيف للسلة: ${pieceLabel(offer.variantId)} · الكمية $qty · في السلة $newQty",
                entityKey = "cart-offer:$offerId:$qty",
                mergeWindowMs = MergeWindowMs
            )
        }
    }

    fun changeCartQuantity(id: Long, delta: Int) {
        if (delta == 0) return
        viewModelScope.launch {
            val row = db.cartDao().getByIdOnce(id) ?: return@launch
            val offer = db.componentDao().getOfferOnce(row.offerId) ?: return@launch
            db.cartDao().changeQuantity(id, delta)
            val fresh = db.cartDao().getByIdOnce(id) ?: return@launch
            val diff = fresh.quantity - row.quantity
            if (diff == 0) return@launch
            record(
                LogCategory.CART, LogAction.EDIT, supplierName(offer.supplierId),
                entityKey = "cart-qty:$id:$diff",
                mergeWindowMs = MergeWindowMs,
                changeDirection = if (diff > 0) 1 else -1,
                changeLabel = pieceLabel(offer.variantId),
                changeBefore = row.quantity,
                changeAfter = fresh.quantity
            )
        }
    }

    fun removeFromCart(id: Long) {
        viewModelScope.launch {
            val row = db.cartDao().getByIdOnce(id) ?: return@launch
            val offer = db.componentDao().getOfferOnce(row.offerId)
            db.cartDao().deleteById(id)
            record(
                LogCategory.CART, LogAction.DELETE,
                offer?.let { supplierName(it.supplierId) } ?: "سلة المشتريات",
                "أُزيل من السلة: ${offer?.let { pieceLabel(it.variantId) } ?: "قطعة محذوفة"} · الكمية ${row.quantity}"
            )
        }
    }
    fun clearCart() {
        viewModelScope.launch {
            val count = db.cartDao().getAllOnce().size
            if (count == 0) return@launch
            db.cartDao().clear()
            record(LogCategory.CART, LogAction.DELETE, "سلة المشتريات", "أُفرغت السلة · $count قطعة")
        }
    }

    // ===== مرفقات البورد وملاحظته =====

    fun getProjectMedia(projectId: Long): Flow<List<ProjectMedia>> = db.mediaDao().getForProject(projectId)
    fun getProjectNote(projectId: Long): Flow<ProjectNote?> = db.noteDao().getForProject(projectId)

    // تُنسخ الملفات داخل ملفات التطبيق الخاصة: يبقى المرفق متاحاً حتى لو نُقل الملف الأصلي أو سقط إذن SAF
    fun addProjectMedia(context: Context, projectId: Long, uris: List<Uri>, onDone: (Int, Int) -> Unit = { _, _ -> }) {
        if (uris.isEmpty()) return
        val app = context.applicationContext
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    val kind = BoardMediaStore.kindOf(app, uri) ?: return@mapNotNull null
                    runCatching { BoardMediaStore.import(app, projectId, uri, kind) }
                        .getOrNull()?.let { kind to it }
                }
            }
            val existing = db.mediaDao().getForProject(projectId).first()
            var nextOrder = (existing.maxOfOrNull { it.orderIndex } ?: -1) + 1
            var images = 0
            var videos = 0
            for ((kind, file) in picked) {
                db.mediaDao().insert(ProjectMedia(
                    projectId = projectId, kind = kind, uri = Uri.fromFile(file).toString(), orderIndex = nextOrder
                ))
                nextOrder++
                if (kind == MediaVideo) videos++ else images++
            }
            if (images + videos > 0) {
                record(
                    LogCategory.BOARDS, LogAction.ADD, projectName(projectId),
                    buildString {
                        append("أُضيفت مرفقات:")
                        if (images > 0) append(" $images صورة")
                        if (videos > 0) append(" $videos فيديو")
                    }
                )
            }
            onDone(images, videos)
        }
    }

    fun deleteProjectMedia(context: Context, media: ProjectMedia) {
        val app = context.applicationContext
        viewModelScope.launch {
            val pName = projectName(media.projectId)
            val kindLabel = if (media.kind == MediaVideo) "فيديو" else "صورة"
            db.mediaDao().delete(media)
            withContext(Dispatchers.IO) { BoardMediaStore.deleteFile(app, media.uri) }
            record(LogCategory.BOARDS, LogAction.DELETE, pName, "حُذف مرفق: $kindLabel")
        }
    }

    fun saveProjectNote(note: ProjectNote) {
        viewModelScope.launch {
            val cleanTitle = note.title.trim()
            val cleanBody = note.body.trim()
            val existing = db.noteDao().getForProject(note.projectId).first()
            if (cleanTitle.isEmpty() && cleanBody.isEmpty()) {
                if (existing != null) {
                    db.noteDao().deleteForProject(note.projectId)
                    record(LogCategory.BOARDS, LogAction.DELETE, projectName(note.projectId), "حُذفت ملاحظة البورد")
                }
            } else {
                db.noteDao().save(note.copy(title = cleanTitle, body = cleanBody, updatedAt = System.currentTimeMillis()))
                if (cleanTitle != (existing?.title ?: "") || cleanBody != (existing?.body ?: "")) {
                    record(
                        LogCategory.BOARDS,
                        if (existing == null) LogAction.ADD else LogAction.EDIT,
                        projectName(note.projectId),
                        "ملاحظة البورد: ${cleanTitle.ifBlank { cleanBody.take(60) }}"
                    )
                }
            }
        }
    }

    fun deleteProjectNote(projectId: Long) {
        viewModelScope.launch {
            if (db.noteDao().getForProject(projectId).first() == null) return@launch
            db.noteDao().deleteForProject(projectId)
            record(LogCategory.BOARDS, LogAction.DELETE, projectName(projectId), "حُذفت ملاحظة البورد")
        }
    }

    // القطعة بلا أحجام تُضاف للقالب بحجمها الأساسي الذي يُنشأ خلف الكواليس
    fun addBaseComponentToTemplate(templateId: Long, componentId: Long, qty: Int) {
        if (qty < 1) return
        viewModelScope.launch {
            val variant = ensureBaseVariant(componentId) ?: return@launch
            addTemplateItem(TemplateItem(templateId = templateId, variantId = variant.id, quantity = qty))
        }
    }
    fun changeTemplateItemQuantity(itemId: Long, delta: Int) {
        if (delta == 0) return
        viewModelScope.launch {
            val item = db.templateDao().getItemOnce(itemId) ?: return@launch
            db.templateDao().changeItemQuantity(itemId, delta)
            val fresh = db.templateDao().getItemOnce(itemId)
            record(
                LogCategory.TEMPLATES, LogAction.EDIT, templateName(item.templateId),
                entityKey = "template-item:$itemId:$delta",
                mergeWindowMs = MergeWindowMs,
                changeDirection = if (delta > 0) 1 else -1,
                changeLabel = templateItemLabel(item),
                changeBefore = item.quantity,
                changeAfter = fresh?.quantity ?: (item.quantity + delta)
            )
        }
    }
    fun deleteTemplateItem(item: TemplateItem) {
        viewModelScope.launch {
            val label = templateItemLabel(item)
            db.templateDao().deleteItem(item)
            record(
                LogCategory.TEMPLATES, LogAction.DELETE, templateName(item.templateId),
                "حُذفت القطعة: $label · الكمية ${item.quantity}"
            )
        }
    }
    fun reorderTemplateItems(items: List<TemplateItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            db.withTransaction {
                val fresh = db.templateDao().getItems(items.first().templateId).first().associateBy { it.id }
                items.forEachIndexed { index, item ->
                    fresh[item.id]?.let { db.templateDao().insertItem(it.copy(orderIndex = index)) }
                }
            }
            val templateId = items.first().templateId
            record(
                LogCategory.TEMPLATES, LogAction.REORDER, templateName(templateId),
                "أُعيد ترتيب قطع القالب",
                entityKey = "template-order:$templateId",
                mergeWindowMs = MergeWindowLongMs
            )
        }
    }

    fun applyTemplateToProject(projectId: Long, templateId: Long, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val templateItems = db.templateDao().getItems(templateId).first()
            val template = db.templateDao().getByIdOnce(templateId)
            var addedPieces = 0        // عدد القطع (الأسطر) المضافة من القالب
            var addedQuantity = 0      // الكمية الإجمالية المستعملة من كل قطعة
            var totalMissing = 0
            var detachedCount = 0
            for (tItem in templateItems) {
                val freshVariants = db.componentDao().getAllVariants().first()
                val variant = tItem.variantId?.let { id -> freshVariants.find { it.id == id } }
                if (variant == null) {
                    // بطاقة يدوية أو منفصلة عن المخزون: تدخل البورد كقطعة يدوية باسمها وأسعارها
                    detachedCount++
                    val manualName = if (tItem.isManual || tItem.manualName.isNotBlank()) tItem.manualName
                                     else "قطعة من القالب"
                    val currentItems = db.projectDao().getItems(projectId).first()
                    val existingItem = currentItems.find {
                        it.isManual && namesEqual(it.manualName, manualName) &&
                            pricesEqual(it.sellPrice, tItem.sellPrice) && pricesEqual(it.lastKnownPurchase, tItem.purchasePrice)
                    }
                    if (existingItem != null) {
                        db.projectDao().updateItem(existingItem.copy(quantity = existingItem.quantity + tItem.quantity))
                    } else {
                        val nextOrder = (currentItems.maxOfOrNull { it.orderIndex } ?: -1) + 1
                        db.projectDao().insertItem(ProjectItem(
                            projectId = projectId, variantId = null, manualName = manualName,
                            quantity = tItem.quantity, sellPrice = tItem.sellPrice,
                            lastKnownPurchase = tItem.purchasePrice, isManual = true, orderIndex = nextOrder
                        ))
                    }
                    continue
                }
                val pulledQty = minOf(tItem.quantity, variant.stockCount).coerceAtLeast(0)
                if (pulledQty > 0) {
                    db.componentDao().adjustStock(variant.id, -pulledQty)
                    val currentItems = db.projectDao().getItems(projectId).first()
                    val existingItem = currentItems.find {
                        !it.isManual && !it.isShortage && it.variantId == variant.id && it.fromStock &&
                            pricesEqual(it.sellPrice, variant.defaultSellPrice) &&
                            pricesEqual(it.lastKnownPurchase, variant.defaultPurchasePrice)
                    }
                    if (existingItem != null) {
                        db.projectDao().updateItem(existingItem.copy(quantity = existingItem.quantity + pulledQty))
                    } else {
                        val nextOrder = (currentItems.maxOfOrNull { it.orderIndex } ?: -1) + 1
                        val item = ProjectItem(
                            projectId = projectId, variantId = variant.id, quantity = pulledQty,
                            sellPrice = variant.defaultSellPrice, fromStock = true,
                            lastKnownPurchase = variant.defaultPurchasePrice, isManual = false,
                            orderIndex = nextOrder
                        )
                        db.projectDao().insertItem(item)
                    }
                    addedPieces++
                    addedQuantity += pulledQty
                }
                val missing = tItem.quantity - pulledQty
                if (missing > 0) {
                    upsertShortageItem(projectId, variant, missing, pulledQty)
                    totalMissing += missing
                }
            }
            // أجور عمل القالب تُسحب معه وتُضاف فوق أجور البورد الحالية، وتبقى قابلة للتعديل عليه
            val templateLabor = template?.laborCost ?: 0.0
            if (templateLabor > 0) {
                val fresh = db.projectDao().getByIdOnce(projectId)
                if (fresh != null) db.projectDao().update(fresh.copy(laborCost = fresh.laborCost + templateLabor))
            }
            val msg = buildString {
                append("تم سحب $addedPieces قطعة بكمية $addedQuantity.")
                if (totalMissing > 0) append(" أُضيف نقص بكمية $totalMissing كقطع مؤقتة.")
                if (detachedCount > 0) append(" أُضيفت $detachedCount قطعة يدوية من القالب.")
                if (templateLabor > 0) append(" أُضيفت أجور عمل ${value(templateLabor)}.")
            }
            record(
                LogCategory.BOARDS, LogAction.ADD, projectName(projectId),
                "$msg · من قالب «${templateName(templateId)}»"
            )
            onResult(msg)
        }
    }

    // المظهر يُحفظ وحده من صف حديث حتى لا يُكتب فوق إعدادات لم تُحمَّل بعد
    fun setDarkMode(isDark: Boolean) {
        viewModelScope.launch {
            val old = db.settingsDao().getOnce()
            if (old?.isDarkMode == isDark) return@launch
            db.settingsDao().save((old ?: AppSettings()).copy(isDarkMode = isDark))
            record(LogCategory.SETTINGS, LogAction.EDIT, "الإعدادات", "المظهر: ${if (isDark) "داكن" else "فاتح"}")
        }
    }

    // الوضع لا يُبدَّل إلا عبر المصادقة. القلب يُقرأ من المخزَّن لا من لقطة الواجهة،
    // فاللقطة القديمة تجعل الزر أحادي الاتجاه ولا يستطيع الرجوع
    fun togglePricesHidden() {
        viewModelScope.launch {
            val old = db.settingsDao().getOnce() ?: AppSettings()
            db.settingsDao().save(old.copy(pricesHidden = !old.pricesHidden))
        }
    }

    fun saveSettings(s: AppSettings) {
        viewModelScope.launch {
            val old = db.settingsDao().getOnce()
            val safe = if (old != null && old.pricesHidden != s.pricesHidden) s.copy(pricesHidden = old.pricesHidden) else s
            db.settingsDao().save(safe)
            if (old == null) return@launch
            val changes = mutableListOf<String>()
            if (old.isDarkMode != s.isDarkMode) changes += "المظهر: ${if (s.isDarkMode) "داكن" else "فاتح"}"
            if (old.stockHighThreshold != s.stockHighThreshold) changes += "حد المخزون المرتفع: من ${old.stockHighThreshold} إلى ${s.stockHighThreshold}"
            if (old.stockMediumThreshold != s.stockMediumThreshold) changes += "حد المخزون المنخفض: من ${old.stockMediumThreshold} إلى ${s.stockMediumThreshold}"
            if (!pricesEqual(old.defaultLaborCost, s.defaultLaborCost)) changes += "أجور العمل الافتراضية: من ${value(old.defaultLaborCost)} إلى ${value(s.defaultLaborCost)}"
            if (old.profitFromDate != s.profitFromDate || old.profitToDate != s.profitToDate) {
                changes += "فترة تقرير الأرباح: من ${dateOrNone(old.profitFromDate)} إلى ${dateOrNone(s.profitToDate)}"
            }
            if (old.businessName.trim() != s.businessName.trim()) changes += "اسم النشاط: ${s.businessName.trim().ifBlank { "الافتراضي" }}"
            if (old.businessTagline.trim() != s.businessTagline.trim()) changes += "وصف النشاط: ${s.businessTagline.trim().ifBlank { "الافتراضي" }}"
            if (old.businessPhone.trim() != s.businessPhone.trim()) changes += "هاتف النشاط: ${s.businessPhone.trim().ifBlank { "فارغ" }}"
            if (old.invoiceFooter.trim() != s.invoiceFooter.trim()) changes += "تذييل الفاتورة: ${s.invoiceFooter.trim().ifBlank { "فارغ" }}"
            if (old.showPurchaseInPdf != s.showPurchaseInPdf) changes += "إظهار الشراء في الفاتورة: ${yesNo(s.showPurchaseInPdf)}"
            if (old.showProfitInPdf != s.showProfitInPdf) changes += "إظهار الربح في الفاتورة: ${yesNo(s.showProfitInPdf)}"
            if (old.deliveryHour != s.deliveryHour || old.deliveryMinute != s.deliveryMinute) {
                changes += "وقت التسليم الافتراضي: من ${clockText(old.deliveryHour, old.deliveryMinute)} إلى ${clockText(s.deliveryHour, s.deliveryMinute)}"
            }
            if (old.reminderDaysBefore != s.reminderDaysBefore) {
                changes += "تنبيه التسليم قبل أيام: من ${old.reminderDaysBefore} إلى ${s.reminderDaysBefore}"
            }
            if (old.reminderHoursBefore != s.reminderHoursBefore) {
                changes += "تنبيه التسليم قبل ساعات: من ${old.reminderHoursBefore} إلى ${s.reminderHoursBefore}"
            }
            if (changes.isNotEmpty()) record(LogCategory.SETTINGS, LogAction.EDIT, "الإعدادات", changes.joinToString("\n"))
        }
    }

    suspend fun calculateProfit(from: Long, to: Long): Double {
        // نضمن شمول اليوم الأخير بالكامل حتى نهاية اليوم 23:59:59.999
        val endOfDay = to + 86400000L - 1L
        val allProjects = db.projectDao().getAll().first()
        val filtered = allProjects.filter { it.status == "DONE" && it.completedAt != null && it.completedAt in from..endOfDay }
        var totalProfit = 0.0
        for (p in filtered) {
            val items = db.projectDao().getItems(p.id).first()
            val revenue = items.sumOf { it.billableSellTotal }
            val cost = items.sumOf { it.billablePurchaseTotal }
            totalProfit += (revenue - cost) + p.laborCost
        }
        return totalProfit
    }

    class Factory(private val db: AppDatabase) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T { @Suppress("UNCHECKED_CAST") return MainViewModel(db) as T }
    }
}

// نتيجة حذف قطعة أو حجم من المخزون: الحذف مسموح إلا إذا كان مستخدماً في بورد غير مكتمل
sealed class DeleteOutcome {
    object Deleted : DeleteOutcome()
    data class Blocked(val message: String) : DeleteOutcome()
    data class NeedsTemplateConfirm(val message: String, val templateCount: Int) : DeleteOutcome()
}
