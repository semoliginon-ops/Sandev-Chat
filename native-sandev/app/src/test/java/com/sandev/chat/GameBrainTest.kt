package com.sandev.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameBrainTest {
    @Test
    fun ignoresIllegalActions() {
        val chosen = GameBrain.choose(
            GameBrain.Situation(gameType = "rpg"),
            listOf(
                GameBrain.Candidate("SKILL", legal = false, progress = 1.0),
                GameBrain.Candidate("ATTACK", legal = true, progress = 0.5)
            )
        )
        assertEquals("ATTACK", chosen?.actionId)
    }

    @Test
    fun returnsNullWhenNoLegalActionExists() {
        assertNull(
            GameBrain.choose(
                GameBrain.Situation(gameType = "strategy"),
                listOf(GameBrain.Candidate("MOVE", legal = false))
            )
        )
    }

    @Test
    fun criticalHealthPrioritizesSurvivalOverProgress() {
        val chosen = GameBrain.choose(
            GameBrain.Situation(
                gameType = "rpg",
                healthRatio = 0.08,
                resourceRatio = 0.4,
                threat = 0.9,
                objectiveUrgency = 0.4
            ),
            listOf(
                GameBrain.Candidate("ATTACK", legal = true, progress = 1.0, risk = 0.8),
                GameBrain.Candidate("HEAL", legal = true, progress = 0.1, survival = 1.0)
            )
        )
        assertEquals("HEAL", chosen?.actionId)
    }

    @Test
    fun highResourceCostIsPenalizedWhenResourcesAreScarce() {
        val state = GameBrain.Situation(
            gameType = "tactics",
            healthRatio = 1.0,
            resourceRatio = 0.05,
            threat = 0.1,
            objectiveUrgency = 0.5
        )
        val cheap = GameBrain.Candidate("BASIC", legal = true, progress = 0.65, resourceCost = 0.0)
        val expensive = GameBrain.Candidate("SPECIAL", legal = true, progress = 0.8, resourceCost = 1.0)
        assertEquals("BASIC", GameBrain.choose(state, listOf(expensive, cheap))?.actionId)
    }

    @Test
    fun normalizesOutOfRangeInputs() {
        val state = GameBrain.Situation(
            gameType = " ",
            healthRatio = -5.0,
            resourceRatio = 9.0,
            threat = Double.NaN,
            objectiveUrgency = 2.0
        ).normalized()
        assertEquals("generic", state.gameType)
        assertEquals(0.0, state.healthRatio, 0.0)
        assertEquals(1.0, state.resourceRatio, 0.0)
        assertEquals(0.0, state.threat, 0.0)
        assertEquals(1.0, state.objectiveUrgency, 0.0)
    }
}
