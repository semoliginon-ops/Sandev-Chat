package com.sandev.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameDecisionProtocolTest {
    @Test
    fun buildsPromptWithOnlySuppliedLegalActions() {
        val prompt = GameDecisionProtocol.buildPrompt(
            "strategy",
            mapOf("turn" to "4", "resources" to "2"),
            listOf(
                GameDecisionProtocol.ActionSpec("SCOUT", "reveal map"),
                GameDecisionProtocol.ActionSpec("ATTACK", "engage target")
            )
        )
        org.junit.Assert.assertTrue(prompt.contains("game type: strategy"))
        org.junit.Assert.assertTrue(prompt.contains("turn: 4"))
        org.junit.Assert.assertTrue(prompt.contains("- SCOUT: reveal map"))
        org.junit.Assert.assertTrue(prompt.contains("ACTION: <exact action ID>"))
    }

    @Test
    fun parsesOnlyAnExactActionLine() {
        assertEquals(
            "DEFEND",
            GameDecisionProtocol.parseAction("ACTION: DEFEND\nCOMMENT: attack later", listOf("ATTACK", "DEFEND"))
        )
        assertNull(
            GameDecisionProtocol.parseAction("I recommend ATTACK because it is useful.", listOf("ATTACK", "DEFEND"))
        )
    }

    @Test
    fun rejectsActionNotInLegalList() {
        assertNull(
            GameDecisionProtocol.parseAction("ACTION: HEAL", listOf("ATTACK", "DEFEND"))
        )
    }
}
