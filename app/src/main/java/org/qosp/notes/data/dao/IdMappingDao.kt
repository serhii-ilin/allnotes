package org.qosp.notes.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.preferences.CloudService

@Dao
interface IdMappingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg mappings: IdMapping)

    @Update
    suspend fun update(vararg mappings: IdMapping)

    @Query("UPDATE cloud_ids SET extras = :extras WHERE localNoteId = :localId AND provider = :cloudService")
    suspend fun updateNoteExtras(localId: Long, cloudService: CloudService, extras: String?)

    @Query("DELETE FROM cloud_ids WHERE localNoteId IN (:ids)")
    suspend fun deleteByLocalId(vararg ids: Long)

    @Query("UPDATE cloud_ids SET isDeletedLocally = 1 WHERE localNoteId IN (:ids)")
    suspend fun setNotesToBeDeleted(vararg ids: Long)

    @Query("DELETE from cloud_ids WHERE provider = :cloudService")
    suspend fun deleteAllMappingsFor(cloudService: CloudService)

    @Query("DELETE from cloud_ids WHERE provider = :cloudService AND isDeletedLocally = 0")
    suspend fun deleteActiveMappingsFor(cloudService: CloudService)

    @Query("SELECT * FROM cloud_ids WHERE localNoteId = :localId AND provider = :provider LIMIT 1")
    suspend fun getByLocalIdAndProvider(localId: Long, provider: CloudService): IdMapping?

    @Query("SELECT * FROM cloud_ids WHERE localNoteId = :localId AND provider IS NOT NULL AND remoteNoteId IS NOT NULL")
    suspend fun getAllByLocalId(localId: Long): List<IdMapping>

    @Query("SELECT * FROM cloud_ids WHERE provider = :provider")
    suspend fun getAllByCloudService(provider: CloudService): List<IdMapping>

    @Query("SELECT count(mappingId) FROM cloud_ids WHERE provider = :provider")
    suspend fun getCountByCloudService(provider: CloudService): Int

    @Query(
        """
        SELECT count(mappingId) FROM cloud_ids
        WHERE provider = :provider AND isDeletedLocally = 0
        AND storageUri = :path AND localNoteId != :excludeLocalId
        """,
    )
    suspend fun countActiveMappingsAtPath(
        provider: CloudService,
        path: String,
        excludeLocalId: Long,
    ): Int

    @Query(
        """
        SELECT storageUri FROM cloud_ids
        WHERE provider = :provider AND isDeletedLocally = 0
        AND storageUri IS NOT NULL AND localNoteId != :excludeLocalId
        """,
    )
    suspend fun getActiveStorageUris(provider: CloudService, excludeLocalId: Long): List<String>
}

/**
 * Like [IdMappingDao.countActiveMappingsAtPath], folding case when the volume does: `Note.md` and `note.md`
 * are one file there. Folds in Kotlin like the vault lookups do, since SQLite's `lower()` is ASCII-only.
 */
suspend fun IdMappingDao.countActiveMappingsAtPath(
    provider: CloudService,
    path: String,
    excludeLocalId: Long,
    ignoreCase: Boolean,
): Int {
    if (!ignoreCase) return countActiveMappingsAtPath(provider, path, excludeLocalId)
    return getActiveStorageUris(provider, excludeLocalId).count { it.equals(path, ignoreCase = true) }
}
