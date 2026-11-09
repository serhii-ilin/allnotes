package org.qosp.notes.data.sync.fs.vault

import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.NoteColor
import org.qosp.notes.data.model.NoteTask

/**
 * Portable on-disk representation: YAML front matter + Markdown body.
 * See [docs/folder-vault-sync.md].
 */
data class VaultFrontMatter(
    val title: String? = null,
    val pinned: Boolean? = null,
    val archived: Boolean? = null,
    val hidden: Boolean? = null,
    val color: NoteColor? = null,
    val tags: List<String> = emptyList(),
    val notebook: String? = null,
    val isList: Boolean? = null,
    val created: Long? = null,
    val modified: Long? = null,
    val markdown: Boolean? = null,
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

        fun parse(raw: String): VaultNoteDocument {
            val match = frontMatterRegex.find(raw.trim())
            if (match == null) {
                return VaultNoteDocument(VaultFrontMatter(), raw)
            }
            val yaml = match.groupValues[1]
            val body = match.groupValues[2]
            return VaultNoteDocument(parseYaml(yaml), body)
        }

        fun fromNote(note: Note, noteRelativePath: String): VaultNoteDocument {
            val titleFromPath = VaultPaths.noteBaseName(noteRelativePath)
            val fm = VaultFrontMatter(
                title = note.title.ifBlank { titleFromPath }.takeIf { it != titleFromPath },
                pinned = note.isPinned.takeIf { it },
                archived = note.isArchived.takeIf { it },
                hidden = note.isHidden.takeIf { it },
                color = note.color.takeIf { it != NoteColor.Default },
                tags = note.tags.map { it.name },
                notebook = null, // notebook name resolved at export time if needed
                isList = note.isList.takeIf { it },
                created = note.creationDate,
                modified = note.modifiedDate,
                markdown = note.isMarkdownEnabled.takeIf { !it },
            )
            val body = if (note.isList) note.taskListToMd() else note.content
            return VaultNoteDocument(fm, body)
        }

        fun toNote(
            doc: VaultNoteDocument,
            noteRelativePath: String,
            lastModified: Long,
            attachments: List<Attachment> = emptyList(),
        ): Note {
            val fm = doc.frontMatter
            val title = fm.title?.takeIf { it.isNotBlank() }
                ?: VaultPaths.noteBaseName(noteRelativePath)
            val isList = fm.isList == true
            val content = if (isList) "" else doc.body
            val tasks = if (isList) doc.body.mdToTaskList() else emptyList()
            return Note(
                title = title,
                content = content,
                isList = isList,
                taskList = tasks,
                isArchived = fm.archived == true,
                isHidden = fm.hidden == true,
                isPinned = fm.pinned == true,
                isMarkdownEnabled = fm.markdown != false,
                creationDate = fm.created ?: lastModified,
                modifiedDate = fm.modified ?: lastModified,
                color = fm.color ?: NoteColor.Default,
                attachments = attachments,
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

        private fun parseYaml(yaml: String): VaultFrontMatter {
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
            val tags = mutableListOf<String>()
            yaml.lines().forEach { line ->
                val t = line.trim()
                when {
                    t.startsWith("pinned:") -> pinned = t.substringAfter(":").trim().toBooleanStrictOrNull()
                    t.startsWith("archived:") -> archived = t.substringAfter(":").trim().toBooleanStrictOrNull()
                    t.startsWith("hidden:") -> hidden = t.substringAfter(":").trim().toBooleanStrictOrNull()
                    t.startsWith("isList:") -> isList = t.substringAfter(":").trim().toBooleanStrictOrNull()
                    t.startsWith("markdown:") -> markdown = t.substringAfter(":").trim().toBooleanStrictOrNull()
                    t.startsWith("title:") -> title = t.substringAfter(":").trim().trim('"')
                    t.startsWith("notebook:") -> notebook = t.substringAfter(":").trim().trim('"')
                    t.startsWith("created:") -> created = t.substringAfter(":").trim().toLongOrNull()
                    t.startsWith("modified:") -> modified = t.substringAfter(":").trim().toLongOrNull()
                    t.startsWith("color:") -> {
                        val raw = t.substringAfter(":").trim().trim('"')
                        color = runCatching { NoteColor.valueOf(raw) }.getOrNull()
                    }
                    t.startsWith("tags:") -> {
                        val rest = t.substringAfter("[").substringBefore("]")
                        rest.split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }.let { tags.addAll(it) }
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
            )
        }

        private fun buildFrontMatterYaml(fm: VaultFrontMatter): String = buildString {
            fm.title?.let { append("title: \"${escape(it)}\"\n") }
            fm.pinned?.let { append("pinned: $it\n") }
            fm.archived?.let { append("archived: $it\n") }
            fm.hidden?.let { append("hidden: $it\n") }
            fm.isList?.let { append("isList: $it\n") }
            fm.markdown?.let { append("markdown: $it\n") }
            fm.color?.let { append("color: \"${it.name}\"\n") }
            fm.notebook?.let { append("notebook: \"${escape(it)}\"\n") }
            if (fm.tags.isNotEmpty()) {
                append("tags: [")
                append(fm.tags.joinToString(", ") { "\"${escape(it)}\"" })
                append("]\n")
            }
            fm.created?.let { append("created: $it\n") }
            fm.modified?.let { append("modified: $it\n") }
        }

        private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
