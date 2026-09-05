package com.lateropulsion.feature.assessment

import com.lateropulsion.core.common.LpError
import com.lateropulsion.core.common.Outcome
import com.lateropulsion.core.model.LpJson
import com.lateropulsion.core.model.ScaleDefinition
import com.lateropulsion.core.model.ScaleResult
import com.lateropulsion.core.model.ScoringRule
import com.lateropulsion.core.model.SsqScore
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Scores any versioned scale definition (REQ-PAT-010). Answers are option indices per item id. */
public object ScaleScorer {
    public fun score(def: ScaleDefinition, answers: Map<String, Int>): ScaleResult {
        val itemScores = LinkedHashMap<String, Double>()
        val missing = ArrayList<String>()
        for (item in def.items) {
            val idx = answers[item.id]
            if (idx == null) { if (item.required) missing += item.id; continue }
            require(idx in item.options.indices) { "option index $idx out of range for item ${item.id}" }
            itemScores[item.id] = item.options[idx].score
        }
        val subscores = LinkedHashMap<String, Double>()
        val total = when (def.scoring) {
            ScoringRule.SUM -> itemScores.values.sum()
            ScoringRule.MEAN -> if (itemScores.isEmpty()) 0.0 else itemScores.values.average()
            ScoringRule.SSQ_WEIGHTED -> {
                var rawAll = 0.0
                for ((sub, weight) in def.subscaleWeights) {
                    val raw = def.items.filter { sub in it.subscales }.sumOf { itemScores[it.id] ?: 0.0 }
                    subscores[sub] = raw * weight
                    rawAll += raw
                }
                rawAll * (def.totalWeight ?: 1.0)
            }
        }
        return ScaleResult(def.code, def.version, itemScores, total, subscores, complete = missing.isEmpty(), missingItems = missing)
    }

    /** Structural checks run over every shipped scale in tests and at load time. */
    public fun validate(def: ScaleDefinition): List<String> = buildList {
        if (def.code.isBlank()) add("code required")
        if (def.version.isBlank()) add("version required")
        if (def.items.isEmpty()) add("no items")
        val ids = def.items.map { it.id }
        if (ids.toSet().size != ids.size) add("duplicate item ids")
        def.items.forEach { item -> if (item.options.size < 2) add("item ${item.id} needs at least two options") }
        if (def.scoring == ScoringRule.SUM) {
            val max = def.items.sumOf { it.options.maxOf { o -> o.score } }
            val min = def.items.sumOf { it.options.minOf { o -> o.score } }
            if (kotlin.math.abs(max - def.rangeMax) > 1e-9) add("range_max ${def.rangeMax} != sum of max option scores $max")
            if (kotlin.math.abs(min - def.rangeMin) > 1e-9) add("range_min ${def.rangeMin} != sum of min option scores $min")
        }
        if (def.scoring == ScoringRule.SSQ_WEIGHTED) {
            if (def.subscaleWeights.isEmpty() || def.totalWeight == null) add("SSQ scoring needs subscale_weights and total_weight")
            def.items.forEach { if (it.subscales.isEmpty()) add("item ${it.id} belongs to no subscale") }
        }
        if (def.licenseNote.isBlank()) add("license_note required (Phase 0 scale licensing)")
    }

    public fun itemsToJson(result: ScaleResult): String = Json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject { result.itemScores.forEach { (k, v) -> put(k, JsonPrimitive(v)) } },
    )
}

public object ScaleLoader {
    public fun parse(json: String): Outcome<ScaleDefinition> {
        val def = try {
            LpJson.strict.decodeFromString(ScaleDefinition.serializer(), json)
        } catch (e: SerializationException) {
            return Outcome.failure(LpError.Validation("scale", "JSON does not match the scale schema: ${e.message}"))
        }
        val problems = ScaleScorer.validate(def)
        return if (problems.isEmpty()) Outcome.success(def) else Outcome.failure(LpError.Validation("scale", problems.joinToString("; ")))
    }
}

/** Kennedy SSQ. The flag threshold is a site setting (config.session.ssq_flag_threshold_total). */
public object SsqScoring {
    public const val CODE: String = "SSQ"

    public fun score(def: ScaleDefinition, answers: Map<String, Int>, flagThresholdTotal: Double): SsqScore {
        require(def.code == CODE && def.scoring == ScoringRule.SSQ_WEIGHTED) { "not an SSQ definition" }
        val r = ScaleScorer.score(def, answers)
        return SsqScore(
            nausea = r.subscores["N"] ?: 0.0,
            oculomotor = r.subscores["O"] ?: 0.0,
            disorientation = r.subscores["D"] ?: 0.0,
            total = r.totalScore,
            flagged = r.totalScore >= flagThresholdTotal,
            itemsJson = ScaleScorer.itemsToJson(r),
        )
    }
}
