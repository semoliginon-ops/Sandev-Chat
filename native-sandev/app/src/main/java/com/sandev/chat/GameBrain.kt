package com.sandev.chat

/**
 * Small, deterministic decision layer shared by game adapters.
 *
 * Game-specific code describes legal actions and estimates their outcomes. This
 * class ranks those candidates consistently; it does not depend on an LLM or
 * Android APIs, so it can be unit-tested on the JVM.
 */
internal object GameBrain {
    data class Situation(
        val gameType: String,
        val healthRatio: Double = 1.0,
        val resourceRatio: Double = 1.0,
        val threat: Double = 0.0,
        val objectiveUrgency: Double = 0.5
    ) {
        fun normalized(): Situation = copy(
            gameType = gameType.trim().ifBlank { "generic" },
            healthRatio = healthRatio.unit(),
            resourceRatio = resourceRatio.unit(),
            threat = threat.unit(),
            objectiveUrgency = objectiveUrgency.unit()
        )
    }

    data class Candidate(
        val actionId: String,
        val legal: Boolean,
        val progress: Double = 0.0,
        val survival: Double = 0.0,
        val informationGain: Double = 0.0,
        val resourceCost: Double = 0.0,
        val risk: Double = 0.0
    )

    fun choose(situation: Situation, candidates: List<Candidate>): Candidate? {
        val state = situation.normalized()
        return candidates.asSequence()
            .filter { it.legal && it.actionId.isNotBlank() }
            .maxWithOrNull(
                compareBy<Candidate> { score(state, it) }
                    .thenByDescending { it.actionId }
            )
    }

    fun score(situation: Situation, candidate: Candidate): Double {
        if (!candidate.legal || candidate.actionId.isBlank()) return Double.NEGATIVE_INFINITY
        val state = situation.normalized()
        val progressWeight = 0.55 + state.objectiveUrgency * 0.45
        val survivalWeight = 0.20 + (1.0 - state.healthRatio) * 1.25 + state.threat * 0.45
        val resourceWeight = 0.12 + (1.0 - state.resourceRatio) * 0.90
        val riskWeight = 0.15 + state.threat * 0.55
        return candidate.progress.unit() * progressWeight +
            candidate.survival.unit() * survivalWeight +
            candidate.informationGain.unit() * 0.15 -
            candidate.resourceCost.unit() * resourceWeight -
            candidate.risk.unit() * riskWeight
    }

    private fun Double.unit(): Double = if (isNaN()) 0.0 else coerceIn(0.0, 1.0)
}
