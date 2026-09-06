package com.lateropulsion.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * One JSON configuration for every config file and export. snake_case keys, `type` discriminator,
 * strict by default so a typo in a protocol file fails loudly instead of silently changing therapy.
 *
 * NaN is a value in this domain, not an error: a session with no episodes has no mean episode duration, a block with
 * one valid sample has no SD, and the reports print "—" for it. Non-finite doubles are therefore allowed so such a
 * session can be saved and read back (REQ-DAT-004); before this, "Confirm and save" failed on every calm session.
 */
@OptIn(ExperimentalSerializationApi::class)
public object LpJson {
    public val strict: Json = Json {
        namingStrategy = JsonNamingStrategy.SnakeCase
        classDiscriminator = "type"
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
        allowSpecialFloatingPointValues = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** For reading files written by newer app versions: unknown keys are tolerated. */
    public val lenient: Json = Json(from = strict) {
        ignoreUnknownKeys = true
    }
}
