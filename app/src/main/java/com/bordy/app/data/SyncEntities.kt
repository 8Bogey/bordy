package com.bordy.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

fun newSyncId(): String = UUID.randomUUID().toString().replace("-", "")

@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey val id: Int = 1,
    val accountId: String? = null,
    val cursor: Long = 0,
    val accountEpoch: Long = 0,
    val deviceEpoch: Long = 0,
    val dataSchemaVersion: Int = 1,
    val restoreMode: Boolean = false,
    val lastSyncAt: Long = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "sync_shadow", primaryKeys = ["entityType", "entityId"])
data class SyncShadow(
    val entityType: String,
    val entityId: String,
    val revision: Long,
    val schemaVersion: Int,
    val payloadJson: String?,
    val deleted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "sync_outbox_operations",
    indices = [Index("state"), Index("createdAt")]
)
data class SyncOutboxOperation(
    @PrimaryKey val operationId: String = newSyncId(),
    val state: String = "pending",
    val createdAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val lastError: String? = null
)

@Entity(
    tableName = "sync_outbox_mutations",
    primaryKeys = ["operationId", "mutationIndex"],
    foreignKeys = [
        ForeignKey(
            entity = SyncOutboxOperation::class,
            parentColumns = ["operationId"],
            childColumns = ["operationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("operationId"), Index(value = ["entityType", "entityId"])]
)
data class SyncOutboxMutation(
    val operationId: String,
    val mutationIndex: Int,
    val entityType: String,
    val entityId: String,
    val action: String,
    val baseRevision: Long,
    val schemaVersion: Int = 1,
    val payloadJson: String? = null,
    val patchJson: String? = null,
    val baseValuesJson: String? = null,
    val mergePolicy: String = "strict"
)

@Entity(
    tableName = "sync_tombstones",
    primaryKeys = ["entityType", "entityId"],
    indices = [Index("createdAt")]
)
data class SyncTombstone(
    val entityType: String,
    val entityId: String,
    val baseRevision: Long,
    val schemaVersion: Int = 1,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "sync_conflicts",
    indices = [Index("status"), Index(value = ["entityType", "entityId"])]
)
data class SyncConflictEntity(
    @PrimaryKey val conflictId: String,
    val operationId: String,
    val entityType: String,
    val entityId: String,
    val reason: String,
    val localJson: String?,
    val serverJson: String?,
    val serverRevision: Long,
    val status: String = "open",
    val createdAt: Long = System.currentTimeMillis()
)

// يُملأ بمُشغّلات SQLite عند أي كتابة محلية، فلا تحتاج شاشة أو دالة حفظ أن تتذكّر المزامنة
@Entity(tableName = "sync_dirty", primaryKeys = ["entityType", "entityId"])
data class SyncDirty(
    val entityType: String,
    val entityId: String,
    val markedAt: Long = System.currentTimeMillis()
)
