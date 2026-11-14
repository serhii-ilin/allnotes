package org.qosp.notes.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.qosp.notes.data.dao.IdMappingDao
import org.qosp.notes.data.dao.NoteDao
import org.qosp.notes.data.dao.NoteTagDao
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.dao.ReminderDao
import org.qosp.notes.data.dao.TagDao
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.NoteEntity
import org.qosp.notes.data.model.NoteTagJoin
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.model.Reminder
import org.qosp.notes.data.model.Tag

@Database(
    entities = [
        NoteEntity::class,
        NoteTagJoin::class,
        Notebook::class,
        Tag::class,
        Reminder::class,
        IdMapping::class,
    ],
    version = 6,
    exportSchema = true
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract val noteDao: NoteDao
    abstract val notebookDao: NotebookDao
    abstract val noteTagDao: NoteTagDao
    abstract val tagDao: TagDao
    abstract val reminderDao: ReminderDao
    abstract val idMappingDao: IdMappingDao

    companion object {
        const val DB_NAME = "notes_database"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    execSQL("ALTER TABLE notes ADD COLUMN isCompactPreview INTEGER NOT NULL DEFAULT (0)")
                }
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    execSQL("ALTER TABLE notes ADD COLUMN screenAlwaysOn INTEGER NOT NULL DEFAULT (0)")
                }
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    execSQL("ALTER TABLE cloud_ids ADD COLUMN storageUri TEXT")
                }
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    execSQL("CREATE INDEX IF NOT EXISTS cloud_ids_id_index ON cloud_ids (localNoteId)")
                    execSQL("CREATE INDEX IF NOT EXISTS cloud_ids_id_provider_index ON cloud_ids (localNoteId, provider)")
                }
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS cloud_ids_id_provider_index")
                val cursor = db.query(
                    """
                    SELECT localNoteId, provider FROM cloud_ids
                    GROUP BY localNoteId, provider HAVING COUNT(*) > 1
                    """.trimIndent(),
                )
                while (cursor.moveToNext()) {
                    val localId = cursor.getLong(0)
                    val provider = cursor.getString(1)
                    val rows = db.query(
                        "SELECT mappingId, storageUri FROM cloud_ids WHERE localNoteId = ? AND provider = ?",
                        arrayOf(localId, provider),
                    )
                    var winnerId = -1L
                    while (rows.moveToNext()) {
                        val id = rows.getLong(0)
                        val uri = rows.getString(1)
                        val isVault = uri != null && !uri.contains("://")
                        if (isVault) {
                            winnerId = id
                            break
                        }
                    }
                    rows.close()
                    if (winnerId < 0) {
                        val fallback = db.query(
                            "SELECT MAX(mappingId) FROM cloud_ids WHERE localNoteId = ? AND provider = ?",
                            arrayOf(localId, provider),
                        )
                        if (fallback.moveToFirst()) winnerId = fallback.getLong(0)
                        fallback.close()
                    }
                    if (winnerId >= 0) {
                        db.execSQL(
                            "DELETE FROM cloud_ids WHERE localNoteId = ? AND provider = ? AND mappingId != ?",
                            arrayOf(localId, provider, winnerId),
                        )
                    }
                }
                cursor.close()
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS cloud_ids_local_provider_unique " +
                        "ON cloud_ids (localNoteId, provider)",
                )
            }
        }
    }
}
