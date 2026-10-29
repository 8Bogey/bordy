package com.bordy.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabase.Callback
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Supplier::class, Component::class, ComponentVariant::class, SupplierOffer::class,
        Template::class, TemplateItem::class, Project::class, ProjectItem::class, AppSettings::class,
        Tag::class, ComponentTag::class, CartItem::class, ProjectMedia::class, ProjectNote::class,
        ActivityLog::class,
        SyncState::class, SyncShadow::class, SyncOutboxOperation::class, SyncOutboxMutation::class,
        SyncTombstone::class, SyncConflictEntity::class, SyncDirty::class],
    version = 26, exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun supplierDao(): SupplierDao
    abstract fun componentDao(): ComponentDao
    abstract fun templateDao(): TemplateDao
    abstract fun projectDao(): ProjectDao
    abstract fun settingsDao(): SettingsDao
    abstract fun tagDao(): TagDao
    abstract fun cartDao(): CartDao
    abstract fun mediaDao(): MediaDao
    abstract fun noteDao(): NoteDao
    abstract fun activityLogDao(): ActivityLogDao
    abstract fun syncDao(): SyncDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN receivedAmount REAL NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE project_items ADD COLUMN isShortage INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE project_items ADD COLUMN shortageRemaining INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE project_items ADD COLUMN shortageFromStock INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE project_items ADD COLUMN shortageManual INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE project_items ADD COLUMN shortageAddedQty INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE components ADD COLUMN stockCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE project_items ADD COLUMN shortageManualPurchase REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE project_items ADD COLUMN shortageManualSell REAL NOT NULL DEFAULT 0")
                db.execSQL("UPDATE project_items SET shortageManualPurchase = lastKnownPurchase * shortageManual, shortageManualSell = sellPrice * shortageManual WHERE isShortage = 1 AND shortageManual > 0")
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE components ADD COLUMN purchasePrice REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE components ADD COLUMN sellPrice REAL NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE component_variants ADD COLUMN isBase INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_settings ADD COLUMN businessName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN businessTagline TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN businessPhone TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN invoiceFooter TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN showPurchaseInPdf INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN showProfitInPdf INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // template_items: variantId Ø£ØµØ¨Ø­ Ø§Ø®ØªÙŠØ§Ø±ÙŠØ§Ù‹ + Ø­Ù‚ÙˆÙ„ Ø§Ù„Ø¹Ù†ØµØ± Ø§Ù„ÙŠØ¯ÙˆÙŠ
                db.execSQL(
                    "CREATE TABLE `template_items_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`templateId` INTEGER NOT NULL, " +
                        "`variantId` INTEGER, " +
                        "`quantity` INTEGER NOT NULL, " +
                        "`orderIndex` INTEGER NOT NULL, " +
                        "`manualName` TEXT NOT NULL DEFAULT '', " +
                        "`purchasePrice` REAL NOT NULL DEFAULT 0, " +
                        "`sellPrice` REAL NOT NULL DEFAULT 0, " +
                        "`isManual` INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL(
                    "INSERT INTO `template_items_new` (`id`, `templateId`, `variantId`, `quantity`, `orderIndex`) " +
                        "SELECT `id`, `templateId`, `variantId`, `quantity`, `orderIndex` FROM `template_items`"
                )
                db.execSQL("DROP TABLE `template_items`")
                db.execSQL("ALTER TABLE `template_items_new` RENAME TO `template_items`")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cart_items` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`offerId` INTEGER NOT NULL, " +
                        "`quantity` INTEGER NOT NULL, " +
                        "`orderIndex` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`offerId`) REFERENCES `supplier_offers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cart_items_offerId` ON `cart_items` (`offerId`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `project_media` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`projectId` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`uri` TEXT NOT NULL, " +
                        "`orderIndex` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_project_media_projectId` ON `project_media` (`projectId`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `project_notes` (" +
                        "`projectId` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`body` TEXT NOT NULL, " +
                        "`colorIndex` INTEGER NOT NULL DEFAULT 0, " +
                        "`updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`projectId`), " +
                        "FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_project_notes_projectId` ON `project_notes` (`projectId`)")
            }
        }

        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `activity_logs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`category` TEXT NOT NULL, " +
                        "`action` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`details` TEXT NOT NULL, " +
                        "`entityKey` TEXT NOT NULL, " +
                        "`repeatCount` INTEGER NOT NULL, " +
                        "`isEdited` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_logs_createdAt` ON `activity_logs` (`createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_logs_category` ON `activity_logs` (`category`)")
            }
        }

        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activity_logs ADD COLUMN changeDirection INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE activity_logs ADD COLUMN changeLabel TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE activity_logs ADD COLUMN changeBefore INTEGER")
                db.execSQL("ALTER TABLE activity_logs ADD COLUMN changeAfter INTEGER")
            }
        }

        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE templates ADD COLUMN laborCost REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN deliveryHour INTEGER NOT NULL DEFAULT 18")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN deliveryMinute INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN reminderDaysBefore INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN reminderHoursBefore INTEGER NOT NULL DEFAULT 3")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN pricesHidden INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // هوية سحابية ثابتة لكل سجل: تُضاف وتُملأ بقيم فريدة قبل بناء الفهرس الفريد
                val syncedTables = listOf(
                    "suppliers", "components", "tags", "component_tags", "component_variants",
                    "supplier_offers", "cart_items", "templates", "template_items", "projects",
                    "project_items", "project_notes", "app_settings"
                )
                for (table in syncedTables) {
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `syncId` TEXT NOT NULL DEFAULT ''")
                    db.execSQL("UPDATE `$table` SET `syncId` = lower(hex(randomblob(16))) WHERE `syncId` = ''")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_syncId` ON `$table` (`syncId`)")
                }

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_state` (`id` INTEGER NOT NULL, `accountId` TEXT, " +
                        "`cursor` INTEGER NOT NULL, `accountEpoch` INTEGER NOT NULL, `deviceEpoch` INTEGER NOT NULL, " +
                        "`dataSchemaVersion` INTEGER NOT NULL, `restoreMode` INTEGER NOT NULL, " +
                        "`lastSyncAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_shadow` (`entityType` TEXT NOT NULL, `entityId` TEXT NOT NULL, " +
                        "`revision` INTEGER NOT NULL, `schemaVersion` INTEGER NOT NULL, `payloadJson` TEXT, " +
                        "`deleted` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`entityType`, `entityId`))"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `sync_state` (`id`, `accountId`, `cursor`, `accountEpoch`, `deviceEpoch`, " +
                        "`dataSchemaVersion`, `restoreMode`, `lastSyncAt`, `updatedAt`) " +
                        "VALUES (1, NULL, 0, 0, 0, 1, 0, 0, 0)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_outbox_operations` (`operationId` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `retryCount` INTEGER NOT NULL, " +
                        "`lastError` TEXT, PRIMARY KEY(`operationId`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_operations_state` ON `sync_outbox_operations` (`state`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_operations_createdAt` ON `sync_outbox_operations` (`createdAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_outbox_mutations` (`operationId` TEXT NOT NULL, " +
                        "`mutationIndex` INTEGER NOT NULL, `entityType` TEXT NOT NULL, `entityId` TEXT NOT NULL, " +
                        "`action` TEXT NOT NULL, `baseRevision` INTEGER NOT NULL, `schemaVersion` INTEGER NOT NULL, " +
                        "`payloadJson` TEXT, `patchJson` TEXT, `baseValuesJson` TEXT, `mergePolicy` TEXT NOT NULL, " +
                        "PRIMARY KEY(`operationId`, `mutationIndex`), " +
                        "FOREIGN KEY(`operationId`) REFERENCES `sync_outbox_operations`(`operationId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_mutations_operationId` ON `sync_outbox_mutations` (`operationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_mutations_entityType_entityId` ON `sync_outbox_mutations` (`entityType`, `entityId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_tombstones` (`entityType` TEXT NOT NULL, `entityId` TEXT NOT NULL, " +
                        "`baseRevision` INTEGER NOT NULL, `schemaVersion` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`entityType`, `entityId`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_tombstones_createdAt` ON `sync_tombstones` (`createdAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_conflicts` (`conflictId` TEXT NOT NULL, `operationId` TEXT NOT NULL, " +
                        "`entityType` TEXT NOT NULL, `entityId` TEXT NOT NULL, `reason` TEXT NOT NULL, `localJson` TEXT, " +
                        "`serverJson` TEXT, `serverRevision` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`conflictId`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_conflicts_status` ON `sync_conflicts` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_conflicts_entityType_entityId` ON `sync_conflicts` (`entityType`, `entityId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_dirty` (`entityType` TEXT NOT NULL, `entityId` TEXT NOT NULL, " +
                        "`markedAt` INTEGER NOT NULL, PRIMARY KEY(`entityType`, `entityId`))"
                )
                SyncTriggers.install(db)
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "bordy.db")
                    // 19â†’20 Ø¨Ù„Ø§ Ù‡Ø¬Ø±Ø©: rebuilding Ù…ØªØ¹Ù…Ù‘Ø¯ Ù„Ù„ØªØ®Ù„Ù‘Øµ Ù…Ù† Ø¹Ù…ÙˆØ¯ tags Ø§Ù„Ù†ØµÙŠ Ø§Ù„Ù‚Ø¯ÙŠÙ…
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            SyncTriggers.install(db)
                        }
                    })
                    .addMigrations(MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26)
                    .build().also { INSTANCE = it }
            }

        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}