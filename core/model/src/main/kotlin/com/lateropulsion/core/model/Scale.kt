package com.lateropulsion.core.model

import kotlinx.serialization.Serializable

/**
 * Versioned clinical scale definition (config/scales/ JSON files). The app renders items dynamically
 * and records [version] with every assessment (REQ-PAT-010).
 */
@Serializable
public data class ScaleDefinition(
    val code: String,
    val version: String,
    val name: String,
    val description: String = "",
    val rangeMin: Double,
    val rangeMax: Double,
    val higherIsWorse: Boolean,
    val scoring: ScoringRule = ScoringRule.SUM,
    val sections: List<ScaleSection>,
    /** Redistribution status; verified in Phase 0 before clinical use. */
    val licenseNote: String,
    val reference: String,
    /** For SSQ-style weighted subscales: section id -> weight. */
    val subscaleWeights: Map<String, Double> = emptyMap(),
    val totalWeight: Double? = null,
) {
    public val items: List<ScaleItem> get() = sections.flatMap { it.items }
}

@Serializable
public data class ScaleSection(
    val id: String,
    val title: String,
    val items: List<ScaleItem>,
)

@Serializable
public data class ScaleItem(
    val id: String,
    val label: String,
    val options: List<ScaleOption>,
    val required: Boolean = true,
    val help: String = "",
    /** SSQ items may belong to several subscales. */
    val subscales: List<String> = emptyList(),
)

@Serializable
public data class ScaleOption(
    val label: String,
    val score: Double,
)

public data class ScaleResult(
    val code: String,
    val version: String,
    val itemScores: Map<String, Double>,
    val totalScore: Double,
    val subscores: Map<String, Double>,
    val complete: Boolean,
    val missingItems: List<String>,
)
