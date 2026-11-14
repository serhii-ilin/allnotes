package org.qosp.notes.data.sync.fs.vault

import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.core.SyncNote

/**
 * Matches vault files to notes by path, then by the front-matter id, then by a unique body.
 * A rename keeps the note. A second file carrying one id is a copy, so it stays unmatched and is
 * imported as its own note; an id never hides a file.
 */
object VaultNoteIdentity {
    data class Match(val localNoteId: Long, val remote: SyncNote, val moved: Boolean)

    data class Resolution(
        val matches: List<Match>,
        /** New paths of moved matches. The remote loop must not import these as new notes. */
        val skipImportPaths: Set<String>,
    ) {
        fun remoteFor(localNoteId: Long): SyncNote? = matches.find { it.localNoteId == localNoteId }?.remote
    }

    fun noteId(remote: SyncNote): String? =
        VaultNoteDocument.parse(remote.content.orEmpty()).frontMatter.id?.takeIf { it.isNotBlank() }

    fun resolve(locals: List<Note>, mappings: List<IdMapping>, remotes: List<SyncNote>): Resolution {
        val localsById = locals.filterNot { it.isDeleted }.associateBy { it.id }
        val active = mappings.filter { mapping ->
            !mapping.isDeletedLocally && localsById[mapping.localNoteId] != null
        }
        val byPath = remotes.associateBy { pathOf(it) }
        val byUid = remotes.mapNotNull { remote -> noteId(remote)?.let { it to remote } }
            .groupBy({ it.first }, { it.second })

        val matches = mutableListOf<Match>()
        val claimed = mutableSetOf<String>()

        fun claim(remote: SyncNote) {
            claimed.add(pathOf(remote))
        }

        for (mapping in active) {
            val path = VaultPaths.vaultMappingPath(mapping.storageUri)?.let(VaultPaths::normalizeNotePath) ?: continue
            val pathHit = byPath[path] ?: continue
            matches.add(Match(mapping.localNoteId, pathHit, moved = false))
            claim(pathHit)
        }

        for (mapping in active) {
            if (matches.any { it.localNoteId == mapping.localNoteId }) continue
            val uid = VaultMappingState.resolve(mapping.extras)?.noteUid?.takeIf { it.isNotBlank() } ?: continue
            val files = byUid[uid].orEmpty().filter { pathOf(it) !in claimed }
            // The newest file keeps the note when the mapped path is gone; the others are copies.
            val chosen = files.maxByOrNull { it.lastModified } ?: continue
            val mapped = VaultPaths.vaultMappingPath(mapping.storageUri)?.let(VaultPaths::normalizeNotePath)
            matches.add(Match(mapping.localNoteId, chosen, moved = mapped != pathOf(chosen)))
            claim(chosen)
        }

        val unresolved = active.filter { mapping -> matches.none { it.localNoteId == mapping.localNoteId } }
            .filter { VaultMappingState.resolve(it.extras)?.noteUid.isNullOrBlank() }
        val pool = remotes.filter { pathOf(it) !in claimed && noteId(it) == null }
        val poolByBody = pool.groupBy { remoteBodyHash(it) }
        val candidates = unresolved.mapNotNull { mapping ->
            val note = localsById[mapping.localNoteId] ?: return@mapNotNull null
            val hash = bodyHash(note) ?: return@mapNotNull null
            val group = poolByBody[hash].orEmpty()
            if (group.size != 1) return@mapNotNull null
            Triple(mapping, hash, group.single())
        }
        val remoteUses = candidates.groupingBy { pathOf(it.third) }.eachCount()
        val hashUses = candidates.groupingBy { it.second }.eachCount()
        for ((mapping, hash, remote) in candidates) {
            if (remoteUses[pathOf(remote)] != 1 || hashUses[hash] != 1) continue
            val mapped = VaultPaths.vaultMappingPath(mapping.storageUri)?.let(VaultPaths::normalizeNotePath)
            matches.add(Match(mapping.localNoteId, remote, moved = mapped != pathOf(remote)))
            claim(remote)
        }

        val skip = matches.filter { it.moved }.mapTo(mutableSetOf()) { pathOf(it.remote) }
        return Resolution(matches, skip)
    }

    private fun pathOf(remote: SyncNote): String = VaultPaths.normalizeNotePath(remote.idStr)

    private fun noteBody(note: Note): String =
        (if (note.isList) note.taskListToMd() else note.content).trimEnd()

    /** Null for a blank body, which would match every empty file. */
    private fun bodyHash(note: Note): String? {
        val body = noteBody(note)
        if (body.isBlank()) return null
        return VaultContentHash.ofText(body)
    }

    private fun remoteBodyHash(remote: SyncNote): String =
        VaultContentHash.ofText(VaultNoteDocument.parse(remote.content.orEmpty()).body.trimEnd())
}
