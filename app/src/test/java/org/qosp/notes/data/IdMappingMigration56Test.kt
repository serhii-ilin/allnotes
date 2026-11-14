package org.qosp.notes.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IdMappingMigration56Test {

  private fun writableV5(context: Context): SupportSQLiteDatabase {
    val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
      .name("migration56_room")
      .callback(
        object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(5) {
          override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
              """
              CREATE TABLE cloud_ids (
                  mappingId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                  localNoteId INTEGER NOT NULL,
                  remoteNoteId INTEGER,
                  provider TEXT,
                  extras TEXT,
                  isDeletedLocally INTEGER NOT NULL,
                  isBeingUpdated INTEGER NOT NULL DEFAULT 0,
                  storageUri TEXT
              )
              """.trimIndent(),
            )
          }

          override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        },
      )
      .build()
    return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
  }

  @Test
  fun blocker4_7_migration56_keepsVaultPathRowOverContentUri() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = writableV5(context)
    db.execSQL(
      """
      INSERT INTO cloud_ids (localNoteId, remoteNoteId, provider, extras, isDeletedLocally, isBeingUpdated, storageUri)
      VALUES (1, NULL, 'FILE_STORAGE', NULL, 0, 0, 'content://old/tree/note.md')
      """.trimIndent(),
    )
    db.execSQL(
      """
      INSERT INTO cloud_ids (localNoteId, remoteNoteId, provider, extras, isDeletedLocally, isBeingUpdated, storageUri)
      VALUES (1, NULL, 'FILE_STORAGE', NULL, 0, 0, 'Work/Foo.md')
      """.trimIndent(),
    )
    AppDatabase.MIGRATION_5_6.migrate(db)
    val c = db.query("SELECT storageUri FROM cloud_ids WHERE localNoteId = 1 AND provider = 'FILE_STORAGE'")
    assertTrue(c.moveToFirst())
    assertEquals("Work/Foo.md", c.getString(0))
    c.close()
    val count = db.query("SELECT COUNT(*) FROM cloud_ids WHERE localNoteId = 1")
    count.moveToFirst()
    assertEquals(1, count.getInt(0))
    count.close()
    db.close()
  }
}
