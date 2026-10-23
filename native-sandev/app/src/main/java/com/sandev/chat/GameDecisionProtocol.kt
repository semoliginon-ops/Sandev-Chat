package com.sandev.chat

/**
 * Minimal text protocol for model-assisted decisions across different games.
 * The model may suggest only one of the supplied legal action IDs.
 */
internal object GameDecisionProtocol {
    data class ActionSpec(val id: String, val description: String = "")

    fun buildPrompt(
        gameType: String,
        state: Map<String, String>,
        legalActions: List<ActionSpec>
    ): String {
        val actionLines = legalActions
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .joinToString("\n") { action ->
                if (action.description.isBlank()) "- ${action.id}" else "- ${action.id}: ${action.description}"
            }
        val stateLines = state.entries.joinToString("\n") { (key, value) -> "$key: $value" }
        return buildString {
            appendLine("You are a decision assistant for the game type: ${gameType.ifBlank { "generic" }}.")
            appendLine("Use only the current state and choose one legal action.")
            appendLine("CURRENT STATE:")
            appendLine(stateLines.ifBlank { "(no state fields provided)" })
            appendLine("LEGAL ACTIONS:")
            appendLine(actionLines.ifBlank { "(no legal actions)" })
            appendLine("Reply with exactly one line and no markdown: ACTION: <exact action ID>")
        }
    }

    fun parseAction(output: String, legalActionIds: Collection<String>): String? {
        val allowed = legalActionIds.map { it.trim().uppercase() }.filter { it.isNotBlank() }.toSet()
        val line = Regex("(?im)^\\s*ACTION\\s*:\\s*([A-Z0-9_-]+)\\s*$")
            .find(output)?.groupValues?.getOrNull(1)?.uppercase()
        return line?.takeIf { it in allowed }
    }
}
