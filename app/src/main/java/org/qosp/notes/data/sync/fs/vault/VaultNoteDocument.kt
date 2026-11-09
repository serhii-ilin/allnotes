package org.qosp.notes.data.sync.fs.vault

import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.NoteColor
import org.qosp.notes.data.model.NoteTask

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
)

data class VaultNoteDocument(
    val frontMatter: VaultFrontMatter,
    val body: String,
) {
    fun renderFullText(): String {
        val fm = buildFrontMatterYaml(frontMatter)
        return buildString {
            append("---\n")
            append(fm)
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
            """^---\s*\r?\n([\s\S]*?)\r?\n---\s*\r?\n?([\s\S]*)$""",
            RegexOption.MULTILINE,
        )

        private val knownRootKeys = setOf(
            "title", "pinned", "archived", "hidden", "isList", "markdown",
            "color", "notebook", "created", "modified", "tags",
        )

        fun parse(raw: String): VaultNoteDocument {
            val match = frontMatterRegex.find(raw.trim())
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
                tags = note.tags.map { it.name }.ifEmpty { emptyList() },
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
            )
        }

        fun mergePreservedFromDisk(existingRaw: String?, document: VaultNoteDocument): VaultNoteDocument {
            if (existingRaw.isNullOrBlank()) return document
            val preserved = parse(existingRaw).frontMatter.preservedYaml
            if (preserved.isBlank()) return document
            return document.copy(frontMatter = document.frontMatter.copy(preservedYaml = preserved))
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
                            "title" -> title = trimmed.substringAfter(":").trim().trim('"')
                            "notebook" -> notebook = trimmed.substringAfter(":").trim().trim('"')
                            "created" -> created = trimmed.substringAfter(":").trim().toLongOrNull()
                            "modified" -> modified = trimmed.substringAfter(":").trim().toLongOrNull()
                            "color" -> {
                                val raw = trimmed.substringAfter(":").trim().trim('"')
                                color = runCatching { NoteColor.valueOf(raw) }.getOrNull()
                            }
                            "tags" -> {
                                tags = if ('[' in trimmed) {
                                    val rest = trimmed.substringAfter("[").substringBefore("]")
                                    rest.split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
                                } else {
                                    val collected = mutableListOf<String>()
                                    i++
                                    while (i < lines.size && (lines[i].startsWith(" ") || lines[i].startsWith("-"))) {
                                        val item = lines[i].trim().removePrefix("-").trim().trim('"')
                                        if (item.isNotEmpty()) collected.add(item)
                                        i++
                                    }
                                    i--
                                    collected
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
            ) to preserved.toString().trimEnd()
        }

        private fun isYamlContinuation(line: String): Boolean {
            val t = line.trim()
            if (t.isEmpty()) return true
            return line.startsWith(" ") || line.startsWith("\t") || t.startsWith("-")
        }

        private fun buildFrontMatterYaml(fm: VaultFrontMatter): String = buildString {
            fm.title?.let { append("title: \"${escape(it)}\"\n") }
            append("pinned: ${fm.pinned ?: false}\n")
            append("archived: ${fm.archived ?: false}\n")
            append("hidden: ${fm.hidden ?: false}\n")
            append("isList: ${fm.isList ?: false}\n")
            append("markdown: ${fm.markdown ?: true}\n")
            append("color: \"${(fm.color ?: NoteColor.Default).name}\"\n")
            if (fm.notebook != null) append("notebook: \"${escape(fm.notebook)}\"\n")
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
            fm.created?.let { append("created: $it\n") }
            fm.modified?.let { append("modified: $it\n") }
        }

        private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
