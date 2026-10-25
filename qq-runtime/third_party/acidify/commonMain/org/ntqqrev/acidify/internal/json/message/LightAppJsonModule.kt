package org.ntqqrev.acidify.internal.json.message

import kotlinx.serialization.json.Json

internal val lightAppJsonModule = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}