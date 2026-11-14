package org.qosp.notes.data.sync.fs.vault

import org.qosp.notes.data.model.Attachment

object AttachmentExtensions {
    private val recognizedExtensions = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg",
        "mp4", "mkv", "webm", "mov", "3gp",
        "mp3", "m4a", "wav", "ogg", "aac", "flac",
        "pdf", "zip", "txt", "md", "json", "xml", "bin",
    )

    fun hasRecognizedExtension(fileName: String): Boolean {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot >= fileName.length - 1) return false
        return fileName.substring(dot + 1).lowercase() in recognizedExtensions
    }

    fun preferredExtension(mime: String?, type: Attachment.Type): String {
        val m = mime?.trim()?.lowercase().orEmpty()
        if (m.isNotEmpty()) {
            when (m) {
                "image/jpeg", "image/jpg" -> return ".jpg"
                "image/png" -> return ".png"
                "image/gif" -> return ".gif"
                "image/webp" -> return ".webp"
                "image/heic", "image/heif" -> return ".heic"
                "image/svg+xml" -> return ".svg"
                "text/plain" -> return ".txt"
                "application/octet-stream" -> return ".bin"
                "video/mp4" -> return ".mp4"
                "video/webm" -> return ".webm"
                "video/x-matroska" -> return ".mkv"
                "video/quicktime" -> return ".mov"
                "video/3gpp" -> return ".3gp"
                "audio/mp4", "audio/x-m4a", "audio/m4a" -> return ".m4a"
                "audio/mpeg", "audio/mp3" -> return ".mp3"
                "audio/ogg", "application/ogg" -> return ".ogg"
                "audio/wav", "audio/x-wav" -> return ".wav"
                "audio/aac" -> return ".aac"
                "audio/flac" -> return ".flac"
                "application/pdf" -> return ".pdf"
            }
            if (m.startsWith("image/")) {
                val sub = m.substringAfter('/')
                if (sub == "jpeg") return ".jpg"
                if (sub.isNotBlank() && sub !in setOf("svg+xml")) return ".$sub"
            }
            if (m.startsWith("video/")) {
                val sub = m.substringAfter('/')
                if (sub.isNotBlank()) return ".$sub"
            }
            if (m.startsWith("audio/")) {
                val sub = m.substringAfter('/')
                if (sub == "mpeg") return ".mp3"
                if (sub.isNotBlank()) return ".$sub"
            }
        }
        return when (type) {
            Attachment.Type.IMAGE -> ".jpg"
            Attachment.Type.VIDEO -> ".mp4"
            Attachment.Type.AUDIO -> ".m4a"
            Attachment.Type.GENERIC -> ".bin"
        }
    }

    fun ensureFileNameHasExtension(baseName: String, mime: String?, type: Attachment.Type): String {
        if (hasRecognizedExtension(baseName)) return baseName
        val ext = preferredExtension(mime, type)
        if (ext.isEmpty()) return baseName
        val dot = baseName.lastIndexOf('.')
        val stem = if (dot > 0) baseName.substring(0, dot) else baseName
        return stem + ext
    }
}
