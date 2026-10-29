package com.bordy.app.data

import androidx.room.*
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SupplierDao {
    @Query("SELECT * FROM suppliers ORDER BY orderIndex ASC, name ASC")
    fun getAll(): Flow<List<Supplier>>
    @Query("SELECT * FROM suppliers WHERE id = :id")
    suspend fun getByIdOnce(id: Long): Supplier?
    @Query("SELECT COUNT(*) FROM supplier_offers WHERE supplierId = :id")
    suspend fun countOffers(id: Long): Int
    @Upsert suspend fun insert(s: Supplier): Long
    @Update suspend fun update(s: Supplier)
    @Delete suspend fun delete(s: Supplier)
}

@Dao
interface ActivityLogDao {
    @Query("SELECT * FROM activity_logs ORDER BY createdAt DESC, id DESC")
    fun getAll(): Flow<List<ActivityLog>>
    @Query("SELECT COUNT(*) FROM activity_logs")
    fun count(): Flow<Int>
    @Query("SELECT * FROM activity_logs ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun latestOnce(): ActivityLog?
    @Insert suspend fun insert(log: ActivityLog): Long
    @Update suspend fun update(log: ActivityLog)
    @Query("DELETE FROM activity_logs WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM activity_logs") suspend fun clear()
    @Query("DELETE FROM activity_logs WHERE id NOT IN (SELECT id FROM activity_logs ORDER BY createdAt DESC, id DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}

@Dao
interface ComponentDao {
    @Query("SELECT * FROM components ORDER BY orderIndex ASC, name ASC")
    fun getAll(): Flow<List<Component>>
    @Query("SELECT * FROM components")
    suspend fun getAllOnce(): List<Component>
    @Query("SELECT * FROM components WHERE id = :id")
    suspend fun getComponentOnce(id: Long): Component?
    @Query("SELECT * FROM component_variants ORDER BY id ASC")
    fun getAllVariants(): Flow<List<ComponentVariant>>
    @Query("SELECT * FROM component_variants")
    suspend fun getAllVariantsOnce(): List<ComponentVariant>
    @Query("SELECT * FROM component_variants WHERE id = :id")
    suspend fun getVariantOnce(id: Long): ComponentVariant?
    @Query("SELECT * FROM supplier_offers")
    fun getAllOffers(): Flow<List<SupplierOffer>>
    @Query("SELECT * FROM supplier_offers")
    suspend fun getAllOffersOnce(): List<SupplierOffer>
    @Query("SELECT * FROM supplier_offers WHERE id = :id")
    suspend fun getOfferOnce(id: Long): SupplierOffer?

    @Upsert suspend fun insertComponent(c: Component): Long
    @Update suspend fun updateComponent(c: Component)
    @Delete suspend fun deleteComponent(c: Component)

    @Upsert suspend fun insertVariant(v: ComponentVariant): Long
    @Update suspend fun updateVariant(v: ComponentVariant)
    @Delete suspend fun deleteVariant(v: ComponentVariant)

    @Upsert suspend fun insertOffer(o: SupplierOffer)
    @Update suspend fun updateOffer(o: SupplierOffer)
    @Delete suspend fun deleteOffer(o: SupplierOffer)

    @Query("UPDATE component_variants SET stockCount = stockCount + :delta WHERE id = :id")
    suspend fun adjustStock(id: Long, delta: Int)

    @Query("UPDATE component_variants SET stockCount = MAX(0, stockCount + :delta) WHERE id = :id")
    suspend fun adjustStockClamped(id: Long, delta: Int)

    @Query("UPDATE component_variants SET stockCount = stockCount - 1 WHERE id = :id AND stockCount > 0")
    suspend fun decrementStockIfAvailable(id: Long): Int

    @Query("UPDATE components SET stockCount = MAX(0, stockCount + :delta) WHERE id = :id")
    suspend fun adjustComponentStockClamped(id: Long, delta: Int)

    @Query("UPDATE supplier_offers SET purchasePrice = MAX(0, purchasePrice + :delta) WHERE id = :id")
    suspend fun adjustOfferPrice(id: Long, delta: Double)

    // البورد المكتمل لا يمنع حذف القطعة من المخزون — يتعامل مع القطعة عند إعادة فتحه
    @Query("SELECT COUNT(*) FROM project_items WHERE variantId IN (SELECT id FROM component_variants WHERE componentId = :componentId) AND projectId IN (SELECT id FROM projects WHERE status != 'DONE')")
    suspend fun countActiveProjectItemsUsingComponent(componentId: Long): Int
    @Query("SELECT COUNT(DISTINCT templateId) FROM template_items WHERE variantId IN (SELECT id FROM component_variants WHERE componentId = :componentId)")
    suspend fun countTemplatesUsingComponent(componentId: Long): Int
    @Query("SELECT * FROM template_items WHERE variantId IN (SELECT id FROM component_variants WHERE componentId = :componentId)")
    suspend fun templateItemsUsingComponent(componentId: Long): List<TemplateItem>
    @Query("SELECT COUNT(*) FROM project_items WHERE variantId = :variantId AND projectId IN (SELECT id FROM projects WHERE status != 'DONE')")
    suspend fun countActiveProjectItemsUsingVariant(variantId: Long): Int
    @Query("SELECT COUNT(DISTINCT templateId) FROM template_items WHERE variantId = :variantId")
    suspend fun countTemplatesUsingVariant(variantId: Long): Int
    @Query("SELECT * FROM template_items WHERE variantId = :variantId")
    suspend fun templateItemsUsingVariant(variantId: Long): List<TemplateItem>
    @Query("SELECT COUNT(*) FROM supplier_offers WHERE variantId = :variantId")
    suspend fun countOffersUsingVariant(variantId: Long): Int
    @Query("SELECT COUNT(*) FROM supplier_offers WHERE variantId IN (SELECT id FROM component_variants WHERE componentId = :componentId)")
    suspend fun countOffersUsingComponent(componentId: Long): Int
    @Query("SELECT * FROM project_items WHERE variantId IN (SELECT id FROM component_variants WHERE componentId = :componentId)")
    suspend fun projectItemsUsingComponent(componentId: Long): List<ProjectItem>
    @Query("SELECT * FROM project_items WHERE variantId = :variantId")
    suspend fun projectItemsUsingVariant(variantId: Long): List<ProjectItem>
}

@Dao
interface TemplateDao {
    @Query("SELECT * FROM templates ORDER BY createdAt DESC")
    fun getAll(): Flow<List<Template>>
    @Query("SELECT * FROM templates")
    suspend fun getAllOnce(): List<Template>
    @Query("SELECT * FROM templates WHERE id = :id")
    suspend fun getByIdOnce(id: Long): Template?
    @Query("SELECT * FROM template_items WHERE id = :id")
    suspend fun getItemOnce(id: Long): TemplateItem?
    @Query("SELECT * FROM template_items WHERE templateId = :tid ORDER BY orderIndex ASC")
    fun getItems(tid: Long): Flow<List<TemplateItem>>
    @Query("SELECT * FROM template_items WHERE templateId = :tid ORDER BY orderIndex ASC")
    suspend fun getItemsOnce(tid: Long): List<TemplateItem>
    @Query("SELECT * FROM template_items")
    suspend fun getAllItemsOnce(): List<TemplateItem>
    @Query("SELECT * FROM template_items ORDER BY templateId ASC, orderIndex ASC")
    fun getAllItems(): Flow<List<TemplateItem>>
    @Upsert suspend fun insert(t: Template): Long
    @Update suspend fun update(t: Template)
    @Upsert suspend fun insertItem(i: TemplateItem): Long
    // الحد الأدنى 1: القالب لا يحمل قطعة بصفر
    @Query("UPDATE template_items SET quantity = MAX(quantity + :delta, 1) WHERE id = :id")
    suspend fun changeItemQuantity(id: Long, delta: Int)
    @Delete suspend fun delete(t: Template)
    @Delete suspend fun deleteItem(i: TemplateItem)
    @Query("DELETE FROM template_items WHERE templateId = :tid") suspend fun deleteItemsOfTemplate(tid: Long)
}

@Dao
interface CartDao {
    @Query("SELECT * FROM cart_items ORDER BY orderIndex ASC, id ASC")
    fun getAll(): Flow<List<CartItem>>
    @Query("SELECT * FROM cart_items")
    suspend fun getAllOnce(): List<CartItem>
    @Query("SELECT * FROM cart_items WHERE id = :id")
    suspend fun getByIdOnce(id: Long): CartItem?
    @Upsert suspend fun insert(c: CartItem): Long
    @Update suspend fun update(c: CartItem)
    @Delete suspend fun delete(c: CartItem)
    @Query("DELETE FROM cart_items") suspend fun clear()
    @Query("UPDATE cart_items SET quantity = :qty WHERE id = :id") suspend fun setQuantity(id: Long, qty: Int)
    // التحديث في القاعدة بدل القيمة المحسوبة في الواجهة: نقرات متتابعة تُجمع كلها ولا تسقط
    @Query("UPDATE cart_items SET quantity = MAX(quantity + :delta, 1) WHERE id = :id")
    suspend fun changeQuantity(id: Long, delta: Int)
    @Query("DELETE FROM cart_items WHERE id = :id") suspend fun deleteById(id: Long)
}

@Dao
interface MediaDao {
    @Query("SELECT * FROM project_media WHERE projectId = :pid ORDER BY orderIndex ASC, createdAt ASC")
    fun getForProject(pid: Long): Flow<List<ProjectMedia>>
    @Query("SELECT * FROM project_media")
    suspend fun getAllOnce(): List<ProjectMedia>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(m: ProjectMedia): Long
    @Update suspend fun update(m: ProjectMedia)
    @Delete suspend fun delete(m: ProjectMedia)
    @Query("DELETE FROM project_media WHERE projectId = :pid") suspend fun deleteForProject(pid: Long)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM project_notes WHERE projectId = :pid")
    fun getForProject(pid: Long): Flow<ProjectNote?>
    @Upsert suspend fun save(n: ProjectNote)
    @Query("DELETE FROM project_notes WHERE projectId = :pid") suspend fun deleteForProject(pid: Long)
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun getAll(): Flow<List<Project>>
    @Query("SELECT * FROM projects")
    suspend fun getAllOnce(): List<Project>
    @Query("SELECT * FROM projects WHERE id = :id")
    fun getById(id: Long): Flow<Project?>
    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getByIdOnce(id: Long): Project?
    @Query("SELECT * FROM project_items WHERE projectId = :pid ORDER BY orderIndex ASC")
    fun getItems(pid: Long): Flow<List<ProjectItem>>
    @Query("SELECT * FROM project_items ORDER BY projectId ASC, orderIndex ASC")
    fun getAllItems(): Flow<List<ProjectItem>>
    @Query("SELECT * FROM project_items")
    suspend fun getAllItemsOnce(): List<ProjectItem>
    @Upsert suspend fun insert(p: Project): Long
    @Update suspend fun update(p: Project)
    @Delete suspend fun delete(p: Project)
    @Upsert suspend fun insertItem(i: ProjectItem): Long
    @Update suspend fun updateItem(i: ProjectItem)
    @Query("UPDATE project_items SET quantity = MAX(quantity + :delta, 1) WHERE id = :id")
    suspend fun changeItemQuantity(id: Long, delta: Int)
    @Delete suspend fun deleteItem(i: ProjectItem)

    @Query("UPDATE project_items SET shortageRemaining = shortageRemaining - 1 WHERE id = :id AND isShortage = 1 AND shortageRemaining > 0")
    suspend fun consumeShortagePiece(id: Long): Int
    @Query("UPDATE project_items SET shortageFromStock = shortageFromStock + 1 WHERE id = :id")
    suspend fun markShortageFromStock(id: Long)
    @Query("UPDATE project_items SET shortageManual = shortageManual + 1, shortageManualPurchase = shortageManualPurchase + :purchase, shortageManualSell = shortageManualSell + :sell WHERE id = :id")
    suspend fun markShortageManualWithPrices(id: Long, purchase: Double, sell: Double)
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 1") fun get(): Flow<AppSettings?>
    @Query("SELECT * FROM app_settings WHERE id = 1") suspend fun getOnce(): AppSettings?
    @Upsert suspend fun save(s: AppSettings)
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE ASC")
    fun getAll(): Flow<List<Tag>>
    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllOnce(): List<Tag>
    @Query("SELECT * FROM component_tags")
    fun getAllLinks(): Flow<List<ComponentTag>>
    @Query("SELECT tagId FROM component_tags WHERE componentId = :componentId")
    suspend fun tagIdsOf(componentId: Long): List<Long>

    @Query("SELECT * FROM tags WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByNormalizedName(name: String): Tag?

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTag(t: Tag): Long
    @Update suspend fun updateTag(t: Tag)
    @Delete suspend fun deleteTag(t: Tag)
    @Query("UPDATE tags SET name = :name WHERE id = :id") suspend fun renameTag(id: Long, name: String)
    @Query("UPDATE tags SET colorIndex = :colorIndex WHERE id = :id") suspend fun setTagColor(id: Long, colorIndex: Int)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertLink(link: ComponentTag)
    @Delete suspend fun deleteLink(link: ComponentTag)
    @Query("DELETE FROM component_tags WHERE componentId = :componentId") suspend fun clearLinks(componentId: Long)
    @Query("DELETE FROM component_tags WHERE tagId = :tagId") suspend fun clearTagLinks(tagId: Long)
    @Query("SELECT * FROM component_tags WHERE tagId = :tagId") suspend fun linksOfTag(tagId: Long): List<ComponentTag>
    @Query("DELETE FROM tags WHERE id = :tagId") suspend fun deleteTagById(tagId: Long)
    @Query("DELETE FROM tags WHERE id NOT IN (SELECT tagId FROM component_tags)")
    suspend fun deleteUnusedTags()
}