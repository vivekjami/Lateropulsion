package com.lateropulsion.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * One JSON configuration for every config file and export. snake_case keys, `type` discriminator,
 * strict by default so a typo in a protocol file fails loudly instead of silently changing therapy.
 */
@OptIn(ExperimentalSerializationApi::class)
public object LpJson {
    public val strict: Json = Json {
        namingStrategy = JsonNamingStrategy.SnakeCase
        classDiscriminator = "type"
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** For reading files written by newer app versions: unknown keys are tolerated. */
    public val lenient: Json = Json(from = strict) {
        ignoreUnknownKeys = true
    }
}
