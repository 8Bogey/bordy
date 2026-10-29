package com.bordy.app.data

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * كل كتابة محلية تُسجَّل في sync_dirty من داخل قاعدة البيانات نفسها،
 * فالمزامنة لا تعتمد على أن تتذكّر كل شاشة أو دالة حفظ أن تُخبر المحرّك.
 */
object SyncTriggers {

    private val tables = listOf(
        "suppliers" to "suppliers",
        "components" to "components",
        "tags" to "tags",
        "component_tags" to "component_tags",
        "component_variants" to "component_variants",
        "supplier_offers" to "supplier_offers",
        "cart_items" to "cart_items",
        "templates" to "templates",
        "template_items" to "template_items",
        "projects" to "projects",
        "project_items" to "project_items",
        "project_notes" to "project_notes",
        "app_settings" to "business_settings"
    )

    fun createTableSql(): String =
        "CREATE TABLE IF NOT EXISTS `sync_dirty` (`entityType` TEXT NOT NULL, `entityId` TEXT NOT NULL, " +
            "`markedAt` INTEGER NOT NULL, PRIMARY KEY(`entityType`, `entityId`))"

    fun install(db: SupportSQLiteDatabase) {
        db.execSQL(createTableSql())
        for ((table, entityType) in tables) {
            for (event in listOf("INSERT", "UPDATE", "DELETE")) {
                val column = if (event == "DELETE") "OLD" else "NEW"
                val name = "sync_dirty_${table}_${event.lowercase()}"
                // يُعاد إنشاء المشغّل دائماً حتى تصل قواعد البيانات القديمة إلى الصيغة الحالية.
                // INSERT OR REPLACE يفشل بخطأ PRIMARY KEY داخل معاملة متداخلة على أندرويد الحديث،
                // أما الإدراج مع ON CONFLICT DO UPDATE فيمرّ على مسار التحديث نفسه دائماً.
                db.execSQL("DROP TRIGGER IF EXISTS `$name`")
                db.execSQL(
                    "CREATE TRIGGER `$name` AFTER $event ON `$table` BEGIN " +
                        "INSERT INTO `sync_dirty` (`entityType`, `entityId`, `markedAt`) " +
                        "VALUES ('$entityType', $column.`syncId`, CAST(strftime('%s','now') AS INTEGER) * 1000) " +
                        "ON CONFLICT(`entityType`, `entityId`) DO UPDATE SET `markedAt` = excluded.`markedAt`; " +
                        "END"
                )
            }
        }
    }

    fun dropAll(db: SupportSQLiteDatabase) {
        for ((table, _) in tables) {
            for (event in listOf("insert", "update", "delete")) {
                db.execSQL("DROP TRIGGER IF EXISTS `sync_dirty_${table}_$event`")
            }
        }
        db.execSQL("DROP TABLE IF EXISTS `sync_dirty`")
    }
}
