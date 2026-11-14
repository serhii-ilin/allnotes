package org.qosp.notes.data.sync.fs.vault.history

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * One encoding for every history file. Unknown fields are ignored so a newer device's lines read here, and
 * absent optional fields (`id`, `path`, `to`, `name`) are left out of the line instead of written as null.
 */
@OptIn(ExperimentalSerializationApi::class)
object HistoryJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
}
