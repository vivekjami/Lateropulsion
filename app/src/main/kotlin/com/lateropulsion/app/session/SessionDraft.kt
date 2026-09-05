package com.lateropulsion.app.session

import com.lateropulsion.core.model.AssistanceLevel
import com.lateropulsion.core.model.Baseline
import com.lateropulsion.core.model.Patient
import com.lateropulsion.core.model.PreSessionChecklist
import com.lateropulsion.core.model.SessionSpec
import com.lateropulsion.core.model.SsqScore
import com.lateropulsion.feature.assessment.Contraindication
import com.lateropulsion.feature.protocol.SessionHistoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What the setup → pre-check → live screens hand to each other. Cleared when a session is saved or abandoned. */
@Singleton
class SessionDraft @Inject constructor() {
    val spec = MutableStateFlow<SessionSpec?>(null)
    var patient: Patient? = null
    var patientName: String = ""
    var baseline: Baseline? = null
    var history: List<SessionHistoryEntry> = emptyList()
    var checklist: PreSessionChecklist = PreSessionChecklist()
    var contraindications: Map<Contraindication, Boolean> = emptyMap()
    var ssqPre: SsqScore? = null
    var assistanceBefore: AssistanceLevel? = null
    var simulated: Boolean = false

    fun clear() {
        spec.value = null; patient = null; patientName = ""; baseline = null; history = emptyList()
        checklist = PreSessionChecklist(); contraindications = emptyMap(); ssqPre = null; assistanceBefore = null; simulated = false
    }
}
