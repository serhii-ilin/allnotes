package org.qosp.notes.data.sync.fs.vault

import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.NoteColor
import org.qosp.notes.data.model.NoteTask
import org.qosp.notes.data.model.Tag

data class VaultFrontMatter(
    val title: String? = null,
    val pinned: Boolean? = null,
    val archived: Boolean? = null,
    val hidden: Boolean? = null,
    val color: NoteColor? = null,
    val tags: List<String>? = null,
    val notebook: String? = null,
    val isList: Boolean? = null,
    val created: Long? = null,
    val modified: Long? = null,
    val markdown: Boolean? = null,
    /** Unparsed / foreign YAML (Obsidian keys, nested blocks) preserved verbatim on round-trip. */
    val preservedYaml: String = "",
    /** Known Quillpad keys whose on-disk formatting must round-trip verbatim (Obsidian-style). */
    val knownKeysVerbatim: String = "",
)

data class VaultNoteDocument(
    val frontMatter: VaultFrontMatter,
    val body: String,
) {
    fun renderFullText(): String {
        val skipKeys = frontMatter.knownKeysVerbatim.lines()
            .mapNotNull { line ->
                line.trim().substringBefore(':').trim().takeIf { it.isNotEmpty() }
            }
            .toSet()
        return buildString {
            append("---\n")
            if (frontMatter.knownKeysVerbatim.isNotBlank()) {
                append(frontMatter.knownKeysVerbatim.trimEnd())
                append('\n')
            }
            append(buildFrontMatterYaml(frontMatter, skipKeys))
            if (frontMatter.preservedYaml.isNotBlank()) {
                append(frontMatter.preservedYaml.trimEnd())
                append('\n')
            }
            append("---\n")
            append(body.trimEnd())
            append('\n')
        }
    }

    companion object {
        private val frontMatterRegex = Regex(
            """\A---\s*\r?\n([\s\S]*?)\r?\n---\s*\r?\n?([\s\S]*)$""",
        )

        private val knownRootKeys = setOf(
            "title", "pinned", "archived", "hidden", "isList", "markdown",
            "color", "notebook", "created", "modified", "tags",
        )

        fun parse(raw: String): VaultNoteDocument {
            val match = frontMatterRegex.find(raw)
            if (match == null) {
                return VaultNoteDocument(VaultFrontMatter(), raw)
            }
            val yaml = match.groupValues[1]
            val body = match.groupValues[2]
            val (fm, preserved) = parseYamlWithPreservation(yaml)
            return VaultNoteDocument(fm.copy(preservedYaml = preserved), body)
        }

        fun fromNote(
            note: Note,
            noteRelativePath: String,
            assignedNotebookName: String? = null,
            exportNotebookExplicitly: Boolean = false,
        ): VaultNoteDocument {
            val titleFromPath = VaultPaths.noteBaseName(noteRelativePath)
            val pathNotebook = VaultPaths.parentPath(noteRelativePath)
                .takeIf { it.isNotBlank() }
                ?.substringAfterLast('/')
            val notebookForYaml = when {
                exportNotebookExplicitly -> assignedNotebookName ?: ""
                note.notebookId != null -> assignedNotebookName
                else -> pathNotebook?.takeIf { note.notebookId == null }
            }
            val fm = VaultFrontMatter(
                title = note.title.ifBlank { titleFromPath }.takeIf { it != titleFromPath },
                pinned = note.isPinned,
                archived = note.isArchived,
                hidden = note.isHidden,
                color = note.color,
                tags = note.tags.map { it.name }.sorted().ifEmpty { emptyList() },
                notebook = notebookForYaml,
                isList = note.isList,
                created = note.creationDate,
                modified = note.modifiedDate,
                markdown = note.isMarkdownEnabled,
            )
            val body = if (note.isList) note.taskListToMd() else note.content
            return VaultNoteDocument(fm, body)
        }

        fun toNote(
            doc: VaultNoteDocument,
            noteRelativePath: String,
            lastModified: Long,
            attachments: List<Attachment> = emptyList(),
            existing: Note? = null,
        ): Note {
            val fm = doc.frontMatter
            val title = fm.title?.takeIf { it.isNotBlank() }
                ?: VaultPaths.noteBaseName(noteRelativePath)
            val isList = when {
                fm.isList == true -> true
                fm.isList == false -> false
                existing != null -> existing.isList
                else -> false
            }
            val content = if (isList) "" else doc.body
            val tasks = if (isList) doc.body.mdToTaskList() else emptyList()
            return Note(
                title = title,
                content = content,
                isList = isList,
                taskList = if (isList) tasks else existing?.taskList ?: emptyList(),
                isArchived = fm.archived ?: existing?.isArchived ?: false,
                isHidden = fm.hidden ?: existing?.isHidden ?: false,
                isPinned = fm.pinned ?: existing?.isPinned ?: false,
                isMarkdownEnabled = fm.markdown?.let { it } ?: existing?.isMarkdownEnabled ?: true,
                creationDate = fm.created ?: existing?.creationDate ?: lastModified,
                modifiedDate = fm.modified ?: lastModified,
                color = fm.color ?: existing?.color ?: NoteColor.Default,
                attachments = attachments,
                tags = fm.tags?.map { name ->
                    existing?.tags?.find { it.name == name }
                        ?: Tag(id = 0L, name = name)
                } ?: existing?.tags ?: emptyList(),
            )
        }

        fun mergePreservedFromDisk(existingRaw: String?, document: VaultNoteDocument): VaultNoteDocument {
            if (existingRaw.isNullOrBlank()) return document
            val prior = parse(existingRaw).frontMatter
            return document.copy(
                frontMatter = document.frontMatter.copy(
                    preservedYaml = prior.preservedYaml,
                    knownKeysVerbatim = prior.knownKeysVerbatim,
                ),
            )
        }

        private fun String.mdToTaskList(): List<NoteTask> {
            val regex = Regex("^\\s*[-+*] *\\[([ xX])](.*)$")
            val tasks = mutableListOf<NoteTask>()
            lines().forEachIndexed { index, line ->
                val result = regex.find(line)
                val task = result?.let {
                    val checked = it.groupValues[1].lowercase() == "x"
                    NoteTask(id = index.toLong(), content = it.groupValues[2].trim(), isDone = checked)
                } ?: tasks.removeLastOrNull()?.let { t -> t.copy(content = t.content + line.trim()) }
                task?.let { tasks.add(it) }
            }
            return tasks
        }

        private fun parseYamlWithPreservation(yaml: String): Pair<VaultFrontMatter, String> {
            var pinned: Boolean? = null
            var archived: Boolean? = null
            var hidden: Boolean? = null
            var isList: Boolean? = null
            var markdown: Boolean? = null
            var title: String? = null
            var notebook: String? = null
            var color: NoteColor? = null
            var created: Long? = null
            var modified: Long? = null
            var tags: List<String>? = null
            val preserved = StringBuilder()
            val knownVerbatim = StringBuilder()
            val lines = yaml.lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                val trimmed = line.trim()
                val rootKey = trimmed.substringBefore(':').trim()
                when {
                    trimmed.isEmpty() -> {
                        preserved.appendLine(line)
                        i++
                    }
                    rootKey in knownRootKeys && !line.startsWith(" ") -> {
                        when (rootKey) {
                            "pinned" -> pinned = trimmed.substringAfter(":").trim().toBooleanStrictOrNull()
                            "archived" -> archived = trimmed.substringAfter(":").trim().toBooleanStrictOrNull()
                            "hidden" -> hidden = trimmed.substringAfter(":").trim().toBooleanStrictOrNull()
                            "isList" -> isList = trimmed.substringAfter(":").trim().toBooleanStrictOrNull()
                            "markdown" -> markdown = trimmed.substringAfter(":").trim().toBooleanStrictOrNull()
                            "title" -> title = unescapeYamlScalar(trimmed.substringAfter(":").trim())
                            "notebook" -> notebook = unescapeYamlScalar(trimmed.substringAfter(":").trim())
                            "created" -> {
                                val rawVal = trimmed.substringAfter(":").trim()
                                created = parseEpoch(rawVal)
                                if (rawVal.toLongOrNull() == null && created != null) {
                                    knownVerbatim.appendLine(line)
                                } else if (created == null) {
                                    knownVerbatim.appendLine(line)
                                }
                            }
                            "modified" -> {
                                val rawVal = trimmed.substringAfter(":").trim()
                                modified = parseEpoch(rawVal)
                                if (rawVal.toLongOrNull() == null && modified != null) {
                                    knownVerbatim.appendLine(line)
                                } else if (modified == null) {
                                    knownVerbatim.appendLine(line)
                                }
                            }
                            "color" -> {
                                val raw = trimmed.substringAfter(":").trim().trim('"')
                                color = runCatching { NoteColor.valueOf(raw) }.getOrNull()
                            }
                            "tags" -> {
                                tags = if ('[' in trimmed) {
                                    val rest = trimmed.substringAfter("[").substringBeforeLast("]")
                                    VaultYamlTags.parseInlineList(rest)
                                } else if (trimmed.substringAfter(":").trim().isEmpty()) {
                                    val collected = mutableListOf<String>()
                                    val blockStart = i
                                    i++
                                    while (i < lines.size && (lines[i].startsWith(" ") || lines[i].startsWith("-"))) {
                                        val item = lines[i].trim().removePrefix("-").trim().trim('"')
                                        if (item.isNotEmpty()) collected.add(item)
                                        i++
                                    }
                                    i--
                                    val block = lines.subList(blockStart, i + 1).joinToString("\n")
                                    knownVerbatim.appendLine(block)
                                    collected
                                } else {
                                    val scalar = unescapeYamlScalar(trimmed.substringAfter(":").trim())
                                    tags = listOf(scalar).filter { it.isNotEmpty() }.ifEmpty { null }
                                    knownVerbatim.appendLine(line)
                                    tags
                                }
                            }
                        }
                        i++
                    }
                    else -> {
                        preserved.appendLine(line)
                        i++
                        while (i < lines.size && isYamlContinuation(lines[i])) {
                            preserved.appendLine(lines[i])
                            i++
                        }
                    }
                }
            }
            return VaultFrontMatter(
                title = title,
                pinned = pinned,
                archived = archived,
                hidden = hidden,
                color = color,
                tags = tags,
                notebook = notebook,
                isList = isList,
                created = created,
                modified = modified,
                markdown = markdown,
                knownKeysVerbatim = knownVerbatim.toString().trimEnd(),
            ) to preserved.toString().trimEnd()
        }

        private fun parseEpoch(raw: String): Long? {
            val t = raw.trim().trim('"')
            t.toLongOrNull()?.let { return it }
            return runCatching { java.time.Instant.parse(t).epochSecond }.getOrNull()
                ?: runCatching {
                    java.time.LocalDate.parse(t).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()
                }.getOrNull()
        }

        private fun isYamlContinuation(line: String): Boolean {
            val t = line.trim()
            if (t.isEmpty()) return true
            return line.startsWith(" ") || line.startsWith("\t") || t.startsWith("-")
        }

        private fun buildFrontMatterYaml(fm: VaultFrontMatter, skipKeys: Set<String> = emptySet()): String = buildString {
            if ("title" !in skipKeys) {
                fm.title?.let { append("title: \"${escape(it)}\"\n") }
            }
            if ("pinned" !in skipKeys) append("pinned: ${fm.pinned ?: false}\n")
            if ("archived" !in skipKeys) append("archived: ${fm.archived ?: false}\n")
            if ("hidden" !in skipKeys) append("hidden: ${fm.hidden ?: false}\n")
            if ("isList" !in skipKeys) append("isList: ${fm.isList ?: false}\n")
            if ("markdown" !in skipKeys) append("markdown: ${fm.markdown ?: true}\n")
            if ("color" !in skipKeys) append("color: \"${(fm.color ?: NoteColor.Default).name}\"\n")
            if ("notebook" !in skipKeys && fm.notebook != null) append("notebook: \"${escape(fm.notebook)}\"\n")
            if ("tags" !in skipKeys) {
                val tagList = fm.tags
                if (tagList != null) {
                    if (tagList.isEmpty()) {
                        append("tags: []\n")
                    } else {
                        append("tags: [")
                        append(tagList.joinToString(", ") { "\"${escape(it)}\"" })
                        append("]\n")
                    }
                }
            }
            if ("created" !in skipKeys) fm.created?.let { append("created: $it\n") }
            if ("modified" !in skipKeys) fm.modified?.let { append("modified: $it\n") }
        }

        private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

        private fun unescapeYamlScalar(raw: String): String {
            val t = raw.trim()
            if (t.startsWith('"') && t.endsWith('"') && t.length >= 2) {
                return unescape(t.substring(1, t.length - 1))
            }
            if (t.startsWith('\'') && t.endsWith('\'') && t.length >= 2) {
                return t.substring(1, t.length - 1).replace("''", "'")
            }
            return t
        }

        private fun unescape(s: String) = buildString {
            var i = 0
            while (i < s.length) {
                if (s[i] == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        '\\' -> append('\\')
                        '"' -> append('"')
                        'n' -> append('\n')
                        't' -> append('\t')
                        else -> { append(s[i + 1]) }
                    }
                    i += 2
                } else {
                    append(s[i])
                    i++
                }
            }
        }
    }
}
