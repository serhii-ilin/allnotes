package org.qosp.notes.data.repo

import org.qosp.notes.data.dao.IdMappingDao
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.preferences.CloudService

class IdMappingRepository(private val idMappingDao: IdMappingDao) {

    suspend fun insert(vararg mappings: IdMapping) = idMappingDao.insert(*mappings)

    suspend fun update(vararg mappings: IdMapping) = idMappingDao.update(*mappings)

    suspend fun getAllByLocalId(localId: Long) = idMappingDao.getAllByLocalId(localId)

    suspend fun getAllByProvider(provider: CloudService) = idMappingDao.getAllByCloudService(provider)

}
