package com.lateropulsion.feature.protocol

import com.lateropulsion.core.common.LpError
import com.lateropulsion.core.common.Outcome
import com.lateropulsion.core.model.AppConfig
import com.lateropulsion.core.model.BodyPosition
import com.lateropulsion.core.model.ExerciseType
import com.lateropulsion.core.model.GainLimits
import com.lateropulsion.core.model.LpJson
import com.lateropulsion.core.model.MetricNames
import com.lateropulsion.core.model.ProtocolSpec
import com.lateropulsion.core.model.VisualMode
import kotlinx.serialization.SerializationException

public data class ValidationIssue(val path: String, val message: String, val blocking: Boolean = true) {
    override fun toString(): String = "${if (blocking) "ERROR" else "WARN"} $path: $message"
}

/** Semantic validation of a protocol beyond what the JSON schema can express (REQ-SES-002). */
public object ProtocolValidator {
    private val idPattern = Regex("^[a-z0-9][a-z0-9-]{2,63}$")

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    public fun validate(p: ProtocolSpec, config: AppConfig = AppConfig()): List<ValidationIssue> {
        val issues = ArrayList<ValidationIssue>()
        fun err(path: String, msg: String) { issues += ValidationIssue(path, msg) }
        fun warn(path: String, msg: String) { issues += ValidationIssue(path, msg, blocking = false) }

        if (!idPattern.matches(p.protocolId)) err("protocol_id", "must be lowercase kebab-case, 3..64 chars")
        if (p.version < 1) err("version", "must be >= 1")
        if (p.name.isBlank()) err("name", "required")
        if (p.blocks.isEmpty()) err("blocks", "at least one block")

        val isAssessment = "assessment" in p.tags
        val ids = HashSet<String>()
        val cap = config.session.maxDurationMin * 60
        p.blocks.forEachIndexed { i, b ->
            val path = "blocks[$i](${b.blockId})"
            if (!ids.add(b.blockId)) err(path, "duplicate block_id")
            if (b.durationS <= 0) err(path, "duration_s must be positive")
            if (b.durationS > config.session.mandatoryRestEveryS) err(path, "duration_s exceeds mandatory rest interval ${config.session.mandatoryRestEveryS} s")
            if (b.toleranceDeg <= 0.0) err(path, "tolerance_deg must be positive")
            if (b.restAfterS < 0) err(path, "rest_after_s cannot be negative")
            if (b.checkpointsS != b.checkpointsS.sorted() || b.checkpointsS.toSet().size != b.checkpointsS.size) err(path, "checkpoints_s must be strictly ascending")
            if (b.checkpointsS.any { it <= 0 || it > b.durationS }) err(path, "checkpoints_s must lie within (0, duration_s]")
            if (b.cues.toSet().size != b.cues.size) err(path, "duplicate cues")
            b.abortIf?.let { c ->
                if (c.comparatorCount != 1) err("$path.abort_if", "exactly one comparator")
                if (c.metric !in MetricNames.all) err("$path.abort_if", "unknown metric '${c.metric}'")
            }
            b.gain?.let { g -> if (g < GainLimits.MIN_ERROR_AUGMENTATION || g > GainLimits.MAX) err(path, "gain override out of range") }
            val pos = b.position ?: p.positionRequired
            if (pos.stage > p.positionRequired.stage) err(path, "block position $pos exceeds protocol position ${p.positionRequired}")
            if (b.exercise == ExerciseType.WALKING_TO_TARGET && pos != BodyPosition.WALKING) err(path, "walking exercise requires WALKING position")
            if (b.exercise == ExerciseType.STANDING_HOLD && pos.stage < BodyPosition.STANDING.stage) err(path, "standing hold requires STANDING or WALKING position")
            if (b.exercise == ExerciseType.SVV_TEST) err(path, "SVV is an assessment, not a protocol exercise")
            if (b.exercise == ExerciseType.BASELINE_CAPTURE && !isAssessment) err(path, "baseline capture only in protocols tagged 'assessment'")
            if (b.exercise == ExerciseType.WEIGHT_SHIFT && b.amplitudeDeg == null) warn(path, "weight shift without amplitude_deg uses the tolerance band as target")
        }

        if (p.positionRequired.stage >= BodyPosition.STANDING.stage && p.requiresGateFrom == null && !isAssessment) {
            err("requires_gate_from", "standing and walking protocols must be gated by a lower-stage protocol (REQ-SAF-003)")
        }
        if (p.requiresGateFrom == p.protocolId) err("requires_gate_from", "cannot depend on itself")
        if (p.totalPlannedS > cap) err("blocks", "planned duration ${p.totalPlannedS} s exceeds the session cap $cap s")
        p.maxSessionS?.let { if (it > cap) err("max_session_s", "exceeds the configured cap $cap s") }

        if (p.visualMode == VisualMode.COMPENSATED_VIEW) {
            if (!p.gainSchedule.isFading) err("gain_schedule", "Mode B protocols require a fading schedule (REQ-SES-010)")
            val g = p.gainInitial ?: config.visual.gainInitial
            if (g > GainLimits.MAX_FIRST_EXPOSURE) warn("gain_initial", "initial gain $g exceeds the first-exposure limit ${GainLimits.MAX_FIRST_EXPOSURE}; preconditions will block early sessions")
            if (p.positionRequired.stage > BodyPosition.SITTING_UNSUPPORTED.stage) err("position_required", "Mode B is seated only")
        }

        p.progressionGate?.let { g ->
            if (g.requires.isEmpty()) err("progression_gate.requires", "must not be empty")
            if (!idPattern.matches(g.unlocks)) err("progression_gate.unlocks", "invalid protocol id")
            g.requires.forEachIndexed { i, r ->
                if (r.metric !in MetricNames.all) err("progression_gate.requires[$i]", "unknown metric '${r.metric}'")
                if (listOfNotNull(r.gte, r.lte, r.eq).size != 1) err("progression_gate.requires[$i]", "exactly one comparator")
                if (r.consecutiveSessions < 1) err("progression_gate.requires[$i]", "consecutive_sessions must be >= 1")
            }
        }
        return issues
    }

    /** Cross-protocol checks over the whole library. */
    public fun validateLibrary(protocols: List<ProtocolSpec>, config: AppConfig = AppConfig()): List<ValidationIssue> {
        val issues = ArrayList<ValidationIssue>()
        val byId = protocols.associateBy { it.protocolId }
        if (byId.size != protocols.size) issues += ValidationIssue("library", "duplicate protocol ids")
        for (p in protocols) {
            issues += validate(p, config).map { it.copy(path = "${p.protocolId}.${it.path}") }
            p.requiresGateFrom?.let { from ->
                val src = byId[from]
                if (src == null) issues += ValidationIssue("${p.protocolId}.requires_gate_from", "unknown protocol '$from'")
                else if (src.progressionGate == null) issues += ValidationIssue("${p.protocolId}.requires_gate_from", "'$from' has no progression gate")
                else if (src.positionRequired.stage >= p.positionRequired.stage) issues += ValidationIssue("${p.protocolId}.requires_gate_from", "gate source must be a lower-stage protocol")
            }
            p.progressionGate?.let { g -> if (g.unlocks !in byId) issues += ValidationIssue("${p.protocolId}.progression_gate.unlocks", "unknown protocol '${g.unlocks}'", blocking = false) }
        }
        return issues
    }
}

public object ProtocolLoader {
    public fun parse(json: String, config: AppConfig = AppConfig()): Outcome<ProtocolSpec> {
        val spec = try {
            LpJson.strict.decodeFromString(ProtocolSpec.serializer(), json)
        } catch (e: SerializationException) {
            return Outcome.failure(LpError.Validation("protocol", "JSON does not match the protocol schema: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            return Outcome.failure(LpError.Validation("protocol", "invalid value: ${e.message}"))
        }
        val blocking = ProtocolValidator.validate(spec, config).filter { it.blocking }
        return if (blocking.isEmpty()) Outcome.success(spec) else Outcome.failure(LpError.Validation("protocol", blocking.joinToString("; ")))
    }
}
