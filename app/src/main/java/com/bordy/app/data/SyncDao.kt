package com.bordy.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {

    // ---------- السجلات المتغيّرة محلياً ----------
    @Query("SELECT * FROM sync_dirty ORDER BY markedAt ASC")
    suspend fun dirtyRows(): List<SyncDirty>

    @Query("SELECT COUNT(*) FROM sync_dirty") suspend fun dirtyCountOnce(): Int

    @Query("SELECT COUNT(*) FROM sync_dirty") fun dirtyCount(): Flow<Int>

    @Query("SELECT * FROM sync_dirty WHERE entityType = :type AND entityId = :id")
    suspend fun dirtyRow(type: String, id: String): SyncDirty?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun markDirty(row: SyncDirty)

    @Query("DELETE FROM sync_dirty WHERE entityType = :type AND entityId = :id")
    suspend fun clearDirty(type: String, id: String)

    @Query("DELETE FROM sync_dirty") suspend fun clearAllDirty()

    // ---------- حالة المزامنة ----------
    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun stateOnce(): SyncState?

    @Query("SELECT * FROM sync_state WHERE id = 1")
    fun state(): Flow<SyncState?>

    @Upsert suspend fun saveState(state: SyncState)

    @Query("SELECT cursor FROM sync_state WHERE id = 1") suspend fun cursorOnce(): Long?

    // ---------- ظلّ الخادم ----------
    @Query("SELECT * FROM sync_shadow WHERE entityType = :type")
    suspend fun shadowsOf(type: String): List<SyncShadow>

    @Query("SELECT * FROM sync_shadow WHERE entityType = :type AND entityId = :id")
    suspend fun shadow(type: String, id: String): SyncShadow?

    @Query("SELECT * FROM sync_shadow")
    suspend fun allShadows(): List<SyncShadow>

    @Upsert suspend fun putShadow(shadow: SyncShadow)

    @Query("DELETE FROM sync_shadow WHERE entityType = :type AND entityId = :id")
    suspend fun deleteShadow(type: String, id: String)

    @Query("DELETE FROM sync_shadow") suspend fun clearShadows()

    // ---------- صندوق الصادر ----------
    @Query("SELECT * FROM sync_outbox_operations WHERE state IN ('pending','sending') ORDER BY createdAt ASC")
    suspend fun pendingOperations(): List<SyncOutboxOperation>

    @Query("SELECT * FROM sync_outbox_operations WHERE state = 'failed' ORDER BY createdAt ASC LIMIT :limit")
    suspend fun failedOperations(limit: Int = 20): List<SyncOutboxOperation>

    @Query("SELECT * FROM sync_outbox_operations WHERE operationId = :id")
    suspend fun operation(id: String): SyncOutboxOperation?

    @Query("SELECT * FROM sync_outbox_mutations WHERE operationId = :id ORDER BY mutationIndex ASC")
    suspend fun mutationsOf(id: String): List<SyncOutboxMutation>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOperation(operation: SyncOutboxOperation)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMutations(mutations: List<SyncOutboxMutation>)

    @Query("UPDATE sync_outbox_operations SET state = :state, lastError = :error WHERE operationId = :id")
    suspend fun setOperationState(id: String, state: String, error: String? = null)

    @Query("UPDATE sync_outbox_operations SET retryCount = retryCount + 1, lastError = :error WHERE operationId = :id")
    suspend fun markOperationRetry(id: String, error: String?)

    @Query("DELETE FROM sync_outbox_operations WHERE operationId = :id")
    suspend fun deleteOperation(id: String)

    @Query("SELECT COUNT(*) FROM sync_outbox_operations") suspend fun outboxCount(): Int

    @Query("SELECT COUNT(*) FROM sync_outbox_operations WHERE state IN ('pending','sending')")
    fun pendingCount(): Flow<Int>

    // ---------- شواهد الحذف ----------
    @Query("SELECT * FROM sync_tombstones") suspend fun tombstonesOnce(): List<SyncTombstone>

    @Query("SELECT * FROM sync_tombstones WHERE entityType = :type AND entityId = :id")
    suspend fun tombstone(type: String, id: String): SyncTombstone?

    @Upsert suspend fun putTombstone(tombstone: SyncTombstone)

    @Query("DELETE FROM sync_tombstones WHERE entityType = :type AND entityId = :id")
    suspend fun deleteTombstone(type: String, id: String)

    @Query("DELETE FROM sync_tombstones") suspend fun clearTombstones()

    // ---------- التعارضات ----------
    @Query("SELECT * FROM sync_conflicts ORDER BY createdAt DESC")
    fun conflicts(): Flow<List<SyncConflictEntity>>

    @Query("SELECT COUNT(*) FROM sync_conflicts WHERE status = 'open'")
    fun openConflictCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_conflicts WHERE status = 'open'")
    suspend fun openConflictCountOnce(): Int

    @Query("SELECT * FROM sync_conflicts WHERE status = 'open' ORDER BY createdAt ASC")
    suspend fun openConflicts(): List<SyncConflictEntity>

    @Upsert suspend fun putConflict(conflict: SyncConflictEntity)

    @Query("UPDATE sync_conflicts SET status = :status WHERE conflictId = :id")
    suspend fun setConflictStatus(id: String, status: String)

    // الكيان صار مطابقاً للخادم مرة أخرى، فالتعارض المفتوح عليه لم يعد قائماً
    @Query("UPDATE sync_conflicts SET status = :status WHERE entityType = :type AND entityId = :id AND status = 'open'")
    suspend fun settleOpenConflicts(type: String, id: String, status: String = "resolved_local")

    @Query("SELECT * FROM sync_conflicts WHERE status = 'pending_apply' ORDER BY createdAt ASC")
    suspend fun pendingApplies(): List<SyncConflictEntity>

    @Query("DELETE FROM sync_conflicts WHERE conflictId = :id")
    suspend fun deleteConflict(id: String)

    @Query("DELETE FROM sync_conflicts") suspend fun clearConflicts()

    // ---------- كيانات العلامات ----------

    @Query("SELECT * FROM tags") suspend fun allTags(): List<Tag>

    @Query("SELECT * FROM tags WHERE syncId = :syncId LIMIT 1")
    suspend fun tagBySyncId(syncId: String): Tag?

    @Query("SELECT * FROM component_tags WHERE syncId = :syncId LIMIT 1")
    suspend fun linkBySyncId(syncId: String): ComponentTag?

    @Query("SELECT * FROM component_tags") suspend fun allLinks(): List<ComponentTag>

    @Query("SELECT * FROM suppliers") suspend fun allSuppliers(): List<Supplier>

    @Query("SELECT * FROM suppliers WHERE syncId = :syncId LIMIT 1")
    suspend fun supplierBySyncId(syncId: String): Supplier?

    @Query("SELECT * FROM project_notes") suspend fun allNotes(): List<ProjectNote>

    @Query("SELECT * FROM project_notes WHERE syncId = :syncId LIMIT 1")
    suspend fun noteBySyncId(syncId: String): ProjectNote?

    @Query("SELECT * FROM components WHERE syncId = :syncId LIMIT 1")
    suspend fun componentBySyncId(syncId: String): Component?

    @Query("SELECT * FROM component_variants WHERE syncId = :syncId LIMIT 1")
    suspend fun variantBySyncId(syncId: String): ComponentVariant?

    @Query("SELECT * FROM supplier_offers WHERE syncId = :syncId LIMIT 1")
    suspend fun offerBySyncId(syncId: String): SupplierOffer?

    @Query("SELECT * FROM cart_items WHERE syncId = :syncId LIMIT 1")
    suspend fun cartBySyncId(syncId: String): CartItem?

    @Query("SELECT * FROM templates WHERE syncId = :syncId LIMIT 1")
    suspend fun templateBySyncId(syncId: String): Template?

    @Query("SELECT * FROM template_items WHERE syncId = :syncId LIMIT 1")
    suspend fun templateItemBySyncId(syncId: String): TemplateItem?

    @Query("SELECT * FROM projects WHERE syncId = :syncId LIMIT 1")
    suspend fun projectBySyncId(syncId: String): Project?

    @Query("SELECT * FROM project_items WHERE syncId = :syncId LIMIT 1")
    suspend fun projectItemBySyncId(syncId: String): ProjectItem?

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun settingsOnce(): AppSettings?

    @Query("SELECT DISTINCT projectId FROM project_media")
    suspend fun projectIdsWithMedia(): List<Long>

    // ---------- قراءة بالمفتاح المحلي (لبناء حمولات المراجع) ----------
    @Query("SELECT * FROM components WHERE id = :id") suspend fun componentById(id: Long): Component?
    @Query("SELECT * FROM component_variants WHERE id = :id") suspend fun variantById(id: Long): ComponentVariant?
    @Query("SELECT * FROM tags WHERE id = :id") suspend fun tagById(id: Long): Tag?
    @Query("SELECT * FROM suppliers WHERE id = :id") suspend fun supplierById(id: Long): Supplier?
    @Query("SELECT * FROM supplier_offers WHERE id = :id") suspend fun offerById(id: Long): SupplierOffer?
    @Query("SELECT * FROM templates WHERE id = :id") suspend fun templateById(id: Long): Template?
    @Query("SELECT * FROM projects WHERE id = :id") suspend fun projectById(id: Long): Project?

    // ---------- إدخال/تحديث بلا حذف ضمني (REPLACE يحذف الصف فيقتل المفاتيح الأجنبية) ----------

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSupplierIgnore(row: Supplier): Long
    @Update suspend fun updateSupplier(row: Supplier)
    @Query("DELETE FROM suppliers WHERE id = :id") suspend fun deleteSupplierById(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertComponentIgnore(row: Component): Long
    @Update suspend fun updateComponent(row: Component)
    @Query("DELETE FROM components WHERE id = :id") suspend fun deleteComponentById(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTagIgnore(row: Tag): Long
    @Update suspend fun updateTag(row: Tag)
    @Query("DELETE FROM tags WHERE id = :id") suspend fun deleteTagById(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertLinkIgnore(row: ComponentTag)
    @Query("DELETE FROM component_tags WHERE componentId = :componentId AND tagId = :tagId")
    suspend fun deleteLinkByIds(componentId: Long, tagId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertVariantIgnore(row: ComponentVariant): Long
    @Update suspend fun updateVariant(row: ComponentVariant)
    @Query("DELETE FROM component_variants WHERE id = :id") suspend fun deleteVariantById(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertOfferIgnore(row: SupplierOffer): Long
    @Update suspend fun updateOffer(row: SupplierOffer)
    @Query("DELETE FROM supplier_offers WHERE id = :id") suspend fun deleteOfferById(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertCartIgnore(row: CartItem): Long
    @Update suspend fun updateCart(row: CartItem)
    @Query("DELETE FROM cart_items WHERE id = :id") suspend fun deleteCartById(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTemplateIgnore(row: Template): Long
    @Update suspend fun updateTemplate(row: Template)
    @Query("DELETE FROM templates WHERE id = :id") suspend fun deleteTemplateById(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTemplateItemIgnore(row: TemplateItem): Long
    @Update suspend fun updateTemplateItem(row: TemplateItem)
    @Query("DELETE FROM template_items WHERE id = :id") suspend fun deleteTemplateItemById(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertProjectIgnore(row: Project): Long
    @Update suspend fun updateProject(row: Project)
    @Query("DELETE FROM projects WHERE id = :id") suspend fun deleteProjectById(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertProjectItemIgnore(row: ProjectItem): Long
    @Update suspend fun updateProjectItem(row: ProjectItem)
    @Query("DELETE FROM project_items WHERE id = :id") suspend fun deleteProjectItemById(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertNoteIgnore(row: ProjectNote): Long
    @Update suspend fun updateNote(row: ProjectNote)
    @Query("DELETE FROM project_notes WHERE projectId = :projectId") suspend fun deleteNoteByProject(projectId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSettingsIgnore(row: AppSettings): Long
    @Update suspend fun updateSettings(row: AppSettings)
}
