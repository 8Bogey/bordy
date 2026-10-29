package com.bordy.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * تحويل سجلات Room إلى حمولات سحابية والعكس.
 * المراجع تُنقل دائماً بـ syncId لا بالمفتاح المحلي، حتى يبقى المعنى نفسه على أي جهاز.
 */
object SyncCodec {

    const val SCHEMA_VERSION = 1

    val TYPES = listOf(
        "suppliers", "components", "tags", "component_tags", "component_variants",
        "supplier_offers", "cart_items", "templates", "template_items", "projects",
        "project_items", "project_notes", "business_settings"
    )

    /** ترتيب الإرسال: الأب قبل الابن، والحذف بالعكس. */
    private val TYPE_ORDER = listOf(
        "business_settings", "suppliers", "components", "tags", "component_variants",
        "component_tags", "supplier_offers", "cart_items", "templates", "template_items",
        "projects", "project_items", "project_notes"
    )

    fun orderOf(type: String): Int = TYPE_ORDER.indexOf(type).let { if (it < 0) TYPE_ORDER.size else it }

    class MissingReference(val entityType: String, val entityId: String) :
        Exception("مرجع غير موجود محلياً: $entityType/$entityId")

    fun isEntityType(type: String) = TYPES.contains(type)

    // ---------------------------------------------------------------- الحمولة المحلية

    suspend fun localPayload(db: AppDatabase, type: String, syncId: String): JSONObject? {
        val dao = db.syncDao()
        return when (type) {
            "suppliers" -> dao.supplierBySyncId(syncId)?.let { s ->
                JSONObject()
                    .put("name", s.name).put("phone", s.phone).put("url", s.url)
                    .put("rating", s.rating).put("defaultMinOrder", s.defaultMinOrder)
                    .put("deliveryCost", s.deliveryCost).put("orderIndex", s.orderIndex)
            }
            "components" -> dao.componentBySyncId(syncId)?.let { c ->
                JSONObject()
                    .put("name", c.name).put("brand", c.brand).put("origin", c.origin)
                    .put("customFields", c.customFields).put("orderIndex", c.orderIndex)
                    .put("stockCount", c.stockCount).put("purchasePrice", c.purchasePrice)
                    .put("sellPrice", c.sellPrice)
            }
            "tags" -> dao.tagBySyncId(syncId)?.let { t ->
                JSONObject().put("name", t.name).put("colorIndex", t.colorIndex).put("createdAt", t.createdAt)
            }
            "component_tags" -> dao.linkBySyncId(syncId)?.let { link ->
                val component = dao.componentById(link.componentId) ?: return@let null
                val tag = dao.tagById(link.tagId) ?: return@let null
                JSONObject().put("componentId", component.syncId).put("tagId", tag.syncId)
            }
            "component_variants" -> dao.variantBySyncId(syncId)?.let { v ->
                val component = dao.componentById(v.componentId) ?: return@let null
                JSONObject()
                    .put("componentId", component.syncId).put("specName", v.specName)
                    .put("defaultPurchasePrice", v.defaultPurchasePrice)
                    .put("defaultSellPrice", v.defaultSellPrice)
                    .put("stockCount", v.stockCount).put("isBase", v.isBase)
            }
            "supplier_offers" -> dao.offerBySyncId(syncId)?.let { o ->
                val variant = dao.variantById(o.variantId) ?: return@let null
                val supplier = dao.supplierById(o.supplierId) ?: return@let null
                JSONObject()
                    .put("variantId", variant.syncId).put("supplierId", supplier.syncId)
                    .put("purchasePrice", o.purchasePrice).put("minOrder", o.minOrder)
            }
            "cart_items" -> dao.cartBySyncId(syncId)?.let { c ->
                val offer = dao.offerById(c.offerId) ?: return@let null
                JSONObject()
                    .put("offerId", offer.syncId).put("quantity", c.quantity).put("orderIndex", c.orderIndex)
            }
            "templates" -> dao.templateBySyncId(syncId)?.let { t ->
                JSONObject()
                    .put("name", t.name).put("boxWidth", t.boxWidth).put("boxHeight", t.boxHeight)
                    .put("boxDepth", t.boxDepth).put("createdAt", t.createdAt).put("laborCost", t.laborCost)
            }
            "template_items" -> dao.templateItemBySyncId(syncId)?.let { i ->
                val template = dao.templateById(i.templateId) ?: return@let null
                val variantSync = i.variantId?.let { dao.variantById(it)?.syncId }
                JSONObject()
                    .put("templateId", template.syncId)
                    .put("variantId", variantSync ?: JSONObject.NULL)
                    .put("quantity", i.quantity).put("orderIndex", i.orderIndex)
                    .put("manualName", i.manualName).put("purchasePrice", i.purchasePrice)
                    .put("sellPrice", i.sellPrice).put("isManual", i.isManual)
            }
            "projects" -> dao.projectBySyncId(syncId)?.let { p ->
                JSONObject()
                    .put("customerName", p.customerName)
                    .put("dueDate", p.dueDate ?: JSONObject.NULL)
                    .put("status", p.status)
                    .put("completedAt", p.completedAt ?: JSONObject.NULL)
                    .put("laborCost", p.laborCost).put("receivedAmount", p.receivedAmount)
                    .put("createdAt", p.createdAt)
            }
            "project_items" -> dao.projectItemBySyncId(syncId)?.let { i ->
                val parent = dao.projectById(i.projectId) ?: return@let null
                val variantSync = i.variantId?.let { dao.variantById(it)?.syncId }
                JSONObject()
                    .put("projectId", parent.syncId)
                    .put("variantId", variantSync ?: JSONObject.NULL)
                    .put("manualName", i.manualName).put("quantity", i.quantity)
                    .put("sellPrice", i.sellPrice).put("fromStock", i.fromStock)
                    .put("isCustomSell", i.isCustomSell).put("lastKnownPurchase", i.lastKnownPurchase)
                    .put("isManual", i.isManual).put("orderIndex", i.orderIndex)
                    .put("isShortage", i.isShortage).put("shortageRemaining", i.shortageRemaining)
                    .put("shortageFromStock", i.shortageFromStock).put("shortageManual", i.shortageManual)
                    .put("shortageAddedQty", i.shortageAddedQty)
                    .put("shortageManualPurchase", i.shortageManualPurchase)
                    .put("shortageManualSell", i.shortageManualSell)
            }
            "project_notes" -> dao.noteBySyncId(syncId)?.let { n ->
                val project = dao.projectById(n.projectId) ?: return@let null
                JSONObject()
                    .put("projectId", project.syncId).put("title", n.title).put("body", n.body)
                    .put("colorIndex", n.colorIndex).put("updatedAt", n.updatedAt)
            }
            "business_settings" -> dao.settingsOnce()?.takeIf { it.syncId == syncId }?.let { s ->
                JSONObject()
                    .put("stockHighThreshold", s.stockHighThreshold)
                    .put("stockMediumThreshold", s.stockMediumThreshold)
                    .put("defaultLaborCost", s.defaultLaborCost)
                    .put("businessName", s.businessName).put("businessTagline", s.businessTagline)
                    .put("businessPhone", s.businessPhone).put("invoiceFooter", s.invoiceFooter)
                    .put("showPurchaseInPdf", s.showPurchaseInPdf)
                    .put("showProfitInPdf", s.showProfitInPdf)
                    .put("deliveryHour", s.deliveryHour).put("deliveryMinute", s.deliveryMinute)
                    .put("reminderDaysBefore", s.reminderDaysBefore)
                    .put("reminderHoursBefore", s.reminderHoursBefore)
            }
            else -> null
        }
    }

    suspend fun localExists(db: AppDatabase, type: String, syncId: String): Boolean {
        val dao = db.syncDao()
        return when (type) {
            "suppliers" -> dao.supplierBySyncId(syncId) != null
            "components" -> dao.componentBySyncId(syncId) != null
            "tags" -> dao.tagBySyncId(syncId) != null
            "component_tags" -> dao.linkBySyncId(syncId) != null
            "component_variants" -> dao.variantBySyncId(syncId) != null
            "supplier_offers" -> dao.offerBySyncId(syncId) != null
            "cart_items" -> dao.cartBySyncId(syncId) != null
            "templates" -> dao.templateBySyncId(syncId) != null
            "template_items" -> dao.templateItemBySyncId(syncId) != null
            "projects" -> dao.projectBySyncId(syncId) != null
            "project_items" -> dao.projectItemBySyncId(syncId) != null
            "project_notes" -> dao.noteBySyncId(syncId) != null
            "business_settings" -> dao.settingsOnce()?.syncId == syncId
            else -> false
        }
    }

    // ---------------------------------------------------------------- تطبيق حمولة سحابية

    suspend fun apply(db: AppDatabase, type: String, syncId: String, payload: JSONObject?) {
        if (payload == null) {
            deleteLocal(db, type, syncId)
            return
        }
        when (type) {
            "suppliers" -> applySupplier(db, syncId, payload)
            "components" -> applyComponent(db, syncId, payload)
            "tags" -> applyTag(db, syncId, payload)
            "component_tags" -> applyLink(db, syncId, payload)
            "component_variants" -> applyVariant(db, syncId, payload)
            "supplier_offers" -> applyOffer(db, syncId, payload)
            "cart_items" -> applyCart(db, syncId, payload)
            "templates" -> applyTemplate(db, syncId, payload)
            "template_items" -> applyTemplateItem(db, syncId, payload)
            "projects" -> applyProject(db, syncId, payload)
            "project_items" -> applyProjectItem(db, syncId, payload)
            "project_notes" -> applyNote(db, syncId, payload)
            "business_settings" -> applySettings(db, syncId, payload)
        }
    }

    private fun JSONObject.str(name: String) = getString(name)
    private fun JSONObject.num(name: String) = getDouble(name)
    private fun JSONObject.int(name: String) = getInt(name)
    private fun JSONObject.long(name: String) = getLong(name)
    private fun JSONObject.bool(name: String) = getBoolean(name)
    private fun JSONObject.longOrNull(name: String): Long? =
        if (isNull(name)) null else getLong(name)

    private suspend fun applySupplier(db: AppDatabase, syncId: String, o: JSONObject) {
        val dao = db.syncDao()
        val existing = dao.supplierBySyncId(syncId)
        val row = Supplier(
            id = existing?.id ?: 0,
            name = o.str("name"), phone = o.str("phone"), url = o.str("url"),
            rating = o.int("rating"), defaultMinOrder = o.int("defaultMinOrder"),
            deliveryCost = o.num("deliveryCost"), orderIndex = o.int("orderIndex"),
            syncId = syncId
        )
        if (existing == null) dao.insertSupplierIgnore(row) else dao.updateSupplier(row)
    }

    private suspend fun applyComponent(db: AppDatabase, syncId: String, o: JSONObject) {
        val dao = db.syncDao()
        val existing = dao.componentBySyncId(syncId)
        val row = Component(
            id = existing?.id ?: 0,
            name = o.str("name"),
            imageUri = existing?.imageUri ?: "",
            brand = o.str("brand"), origin = o.str("origin"), customFields = o.str("customFields"),
            orderIndex = o.int("orderIndex"), stockCount = o.int("stockCount"),
            purchasePrice = o.num("purchasePrice"), sellPrice = o.num("sellPrice"),
            syncId = syncId
        )
        if (existing == null) dao.insertComponentIgnore(row) else dao.updateComponent(row)
    }

    private suspend fun applyTag(db: AppDatabase, syncId: String, o: JSONObject) {
        val dao = db.syncDao()
        val existing = dao.tagBySyncId(syncId)
        val row = Tag(
            id = existing?.id ?: 0, name = o.str("name"), colorIndex = o.int("colorIndex"),
            createdAt = o.long("createdAt"), syncId = syncId
        )
        if (existing == null) dao.insertTagIgnore(row) else dao.updateTag(row)
    }

    private suspend fun applyLink(db: AppDatabase, syncId: String, o: JSONObject) {
        val dao = db.syncDao()
        val component = dao.componentBySyncId(o.str("componentId"))
            ?: throw MissingReference("components", o.str("componentId"))
        val tag = dao.tagBySyncId(o.str("tagId"))
            ?: throw MissingReference("tags", o.str("tagId"))
        val existing = dao.linkBySyncId(syncId)
        if (existing != null) {
            if (existing.componentId != component.id || existing.tagId != tag.id) {
                dao.deleteLinkByIds(existing.componentId, existing.tagId)
                dao.insertLinkIgnore(ComponentTag(component.id, tag.id, syncId))
            }
        } else {
            dao.insertLinkIgnore(ComponentTag(component.id, tag.id, syncId))
        }
    }

    private suspend fun applyVariant(db: AppDatabase, syncId: String, o: JSONObject) {
        val dao = db.syncDao()
        val component = dao.componentBySyncId(o.str("componentId"))
            ?: throw MissingReference("components", o.str("componentId"))
        val existing = dao.variantBySyncId(syncId)
        val row = ComponentVariant(
            id = existing?.id ?: 0, componentId = component.id, specName = o.str("specName"),
            defaultPurchasePrice = o.num("defaultPurchasePrice"),
            defaultSellPrice = o.num("defaultSellPrice"),
            stockCount = o.int("stockCount"), isBase = o.bool("isBase"), syncId = syncId
        )
        if (existing == null) dao.insertVariantIgnore(row) else dao.updateVariant(row)
    }

    private suspend fun applyOffer(db: AppDatabase, syncId: String, o: JSONObject) {
        val dao = db.syncDao()
        val variant = dao.variantBySyncId(o.str("variantId"))
            ?: throw MissingReference("component_variants", o.str("variantId"))
        val supplier = dao.supplierBySyncId(o.str("supplierId"))
            ?: throw MissingReference("suppliers", o.str("supplierId"))
        val existing = dao.offerBySyncId(syncId)
        val row = SupplierOffer(
            id = existing?.id ?: 0, variantId = variant.id, supplierId = supplier.id,
            purchasePrice = o.num("purchasePrice"), minOrder = o.int("minOrder"), syncId = syncId
        )
        if (existing == null) dao.insertOfferIgnore(row) else dao.updateOffer(row)
    }

    private suspend fun applyCart(db: AppDatabase, syncId: String, o: JSONObject) {
        val dao = db.syncDao()
        val offer = dao.offerBySyncId(o.str("offerId"))
            ?: throw MissingReference("supplier_offers", o.str("offerId"))
        val existing = dao.cartBySyncId(syncId)
        val row = CartItem(
            id = existing?.id ?: 0, offerId = offer.id, quantity = o.int("quantity"),
            orderIndex = o.int("orderIndex"), syncId = syncId
        )
        if (existing == null) dao.insertCartIgnore(row) else dao.updateCart(row)
    }

    private suspend fun applyTemplate(db: AppDatabase, syncId: String, o: JSONObject) {
        val dao = db.syncDao()
        val existing = dao.templateBySyncId(syncId)
        val row = Template(
            id = existing?.id ?: 0, name = o.str("name"),
            imageUri = existing?.imageUri ?: "",
            boxWidth = o.num("boxWidth"), boxHeight = o.num("boxHeight"), boxDepth = o.num("boxDepth"),
            createdAt = o.long("createdAt"), laborCost = o.num("laborCost"), syncId = syncId
        )
        if (existing == null) dao.insertTemplateIgnore(row) else dao.updateTemplate(row)
    }

    private suspend fun applyTemplateItem(db: AppDatabase, syncId: String, o: JSONObject) {
        val dao = db.syncDao()
        val template = dao.templateBySyncId(o.str("templateId"))
            ?: throw MissingReference("templates", o.str("templateId"))
        val variantId = if (o.isNull("variantId")) null else {
            val variantSync = o.str("variantId")
            dao.variantBySyncId(variantSync)?.id
                ?: throw MissingReference("component_variants", variantSync)
        }
        val existing = dao.templateItemBySyncId(syncId)
        val row = TemplateItem(
            id = existing?.id ?: 0, templateId = template.id, variantId = variantId,
            quantity = o.int("quantity"), orderIndex = o.int("orderIndex"),
            manualName = o.str("manualName"), purchasePrice = o.num("purchasePrice"),
            sellPrice = o.num("sellPrice"), isManual = o.bool("isManual"), syncId = syncId
        )
        if (existing == null) dao.insertTemplateItemIgnore(row) else dao.updateTemplateItem(row)
    }

    private suspend fun applyProject(db: AppDatabase, syncId: String, o: JSONObject) {
        val dao = db.syncDao()
        val existing = dao.projectBySyncId(syncId)
        val row = Project(
            id = existing?.id ?: 0, customerName = o.str("customerName"),
            dueDate = o.longOrNull("dueDate"), status = o.str("status"),
            completedAt = o.longOrNull("completedAt"), laborCost = o.num("laborCost"),
            receivedAmount = o.num("receivedAmount"), createdAt = o.long("createdAt"), syncId = syncId
        )
        if (existing == null) dao.insertProjectIgnore(row) else dao.updateProject(row)
    }

    private suspend fun applyProjectItem(db: AppDatabase, syncId: String, o: JSONObject) {
        val dao = db.syncDao()
        val project = dao.projectBySyncId(o.str("projectId"))
            ?: throw MissingReference("projects", o.str("projectId"))
        val variantId = if (o.isNull("variantId")) null else {
            val variantSync = o.str("variantId")
            dao.variantBySyncId(variantSync)?.id
                ?: throw MissingReference("component_variants", variantSync)
        }
        val existing = dao.projectItemBySyncId(syncId)
        val row = ProjectItem(
            id = existing?.id ?: 0, projectId = project.id, variantId = variantId,
            manualName = o.str("manualName"), quantity = o.int("quantity"),
            sellPrice = o.num("sellPrice"), fromStock = o.bool("fromStock"),
            isCustomSell = o.bool("isCustomSell"), lastKnownPurchase = o.num("lastKnownPurchase"),
            isManual = o.bool("isManual"), orderIndex = o.int("orderIndex"),
            isShortage = o.bool("isShortage"), shortageRemaining = o.int("shortageRemaining"),
            shortageFromStock = o.int("shortageFromStock"), shortageManual = o.int("shortageManual"),
            shortageAddedQty = o.int("shortageAddedQty"),
            shortageManualPurchase = o.num("shortageManualPurchase"),
            shortageManualSell = o.num("shortageManualSell"), syncId = syncId
        )
        if (existing == null) dao.insertProjectItemIgnore(row) else dao.updateProjectItem(row)
    }

    private suspend fun applyNote(db: AppDatabase, syncId: String, o: JSONObject) {
        val dao = db.syncDao()
        val project = dao.projectBySyncId(o.str("projectId"))
            ?: throw MissingReference("projects", o.str("projectId"))
        val existing = dao.noteBySyncId(syncId)
        if (existing != null && existing.projectId != project.id) dao.deleteNoteByProject(existing.projectId)
        val row = ProjectNote(
            projectId = project.id, title = o.str("title"), body = o.str("body"),
            colorIndex = o.int("colorIndex"), updatedAt = o.long("updatedAt"), syncId = syncId
        )
        if (existing == null) dao.insertNoteIgnore(row) else dao.updateNote(row)
    }

    private suspend fun applySettings(db: AppDatabase, syncId: String, o: JSONObject) {
        val dao = db.syncDao()
        val existing = dao.settingsOnce()
        val row = AppSettings(
            id = 1,
            isDarkMode = existing?.isDarkMode ?: true,
            stockHighThreshold = o.int("stockHighThreshold"),
            stockMediumThreshold = o.int("stockMediumThreshold"),
            defaultLaborCost = o.num("defaultLaborCost"),
            profitFromDate = existing?.profitFromDate,
            profitToDate = existing?.profitToDate,
            isFirstLaunch = existing?.isFirstLaunch ?: false,
            businessName = o.str("businessName"), businessTagline = o.str("businessTagline"),
            businessPhone = o.str("businessPhone"), invoiceFooter = o.str("invoiceFooter"),
            showPurchaseInPdf = o.bool("showPurchaseInPdf"),
            showProfitInPdf = o.bool("showProfitInPdf"),
            deliveryHour = o.int("deliveryHour"), deliveryMinute = o.int("deliveryMinute"),
            reminderDaysBefore = o.int("reminderDaysBefore"),
            reminderHoursBefore = o.int("reminderHoursBefore"),
            pricesHidden = existing?.pricesHidden ?: false,
            syncId = syncId
        )
        if (existing == null) dao.insertSettingsIgnore(row) else dao.updateSettings(row)
    }

    private suspend fun deleteLocal(db: AppDatabase, type: String, syncId: String) {
        val dao = db.syncDao()
        when (type) {
            "suppliers" -> dao.supplierBySyncId(syncId)?.let { dao.deleteSupplierById(it.id) }
            "components" -> dao.componentBySyncId(syncId)?.let { dao.deleteComponentById(it.id) }
            "tags" -> dao.tagBySyncId(syncId)?.let { dao.deleteTagById(it.id) }
            "component_tags" -> dao.linkBySyncId(syncId)?.let { dao.deleteLinkByIds(it.componentId, it.tagId) }
            "component_variants" -> dao.variantBySyncId(syncId)?.let { dao.deleteVariantById(it.id) }
            "supplier_offers" -> dao.offerBySyncId(syncId)?.let { dao.deleteOfferById(it.id) }
            "cart_items" -> dao.cartBySyncId(syncId)?.let { dao.deleteCartById(it.id) }
            "templates" -> dao.templateBySyncId(syncId)?.let { dao.deleteTemplateById(it.id) }
            "template_items" -> dao.templateItemBySyncId(syncId)?.let { dao.deleteTemplateItemById(it.id) }
            "projects" -> dao.projectBySyncId(syncId)?.let { dao.deleteProjectById(it.id) }
            "project_items" -> dao.projectItemBySyncId(syncId)?.let { dao.deleteProjectItemById(it.id) }
            "project_notes" -> dao.noteBySyncId(syncId)?.let { dao.deleteNoteByProject(it.projectId) }
            // إعدادات النشاط لا تُحذف: تبقى الصف الوحيد وتُستبدل حمولتها عند الحاجة
        }
    }

    // ---------------------------------------------------------------- مقارنة ودمج

    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted()
            .joinToString(prefix = "{", postfix = "}") { key ->
                JSONObject.quote(key) + ":" + canonical(value.opt(key))
            }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.opt(it)) }
        is Boolean -> value.toString()
        is Number -> normalizeNumber(value)
        else -> JSONObject.quote(value.toString())
    }

    private fun normalizeNumber(value: Number): String {
        val d = value.toDouble()
        return if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 9.2e18) d.toLong().toString() else d.toString()
    }

    /** الحقول التي تختلف بين ظلّ الخادم والسجل المحلي، مع قيم الأساس. */
    fun diff(base: JSONObject?, current: JSONObject): Pair<JSONObject, JSONObject> {
        val patch = JSONObject()
        val baseValues = JSONObject()
        for (key in current.keys()) {
            val now = current.opt(key)
            val before = base?.opt(key)
            if (base == null || canonical(now) != canonical(before)) {
                patch.put(key, now ?: JSONObject.NULL)
                baseValues.put(key, before ?: JSONObject.NULL)
            }
        }
        return patch to baseValues
    }
}
