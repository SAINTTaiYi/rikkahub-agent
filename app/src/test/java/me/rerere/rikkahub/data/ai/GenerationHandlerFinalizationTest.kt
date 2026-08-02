package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationHandlerFinalizationTest {
    @Test
    fun `ultra loop guard permits more than seven trips`() {
        assertFalse(ultraLoopGuardNeedsFinalization(8))
        assertFalse(ultraLoopGuardNeedsFinalization(ULTRA_MAX_TOOL_LOOP_STEPS - 1))
        assertTrue(ultraLoopGuardNeedsFinalization(ULTRA_MAX_TOOL_LOOP_STEPS))
    }

    @Test
    fun `ultra budget keeps an eighth tool round available`() {
        val step = generationFinalizationStep(
            stepIndex = 8,
            maxSteps = ULTRA_MAX_TOOL_LOOP_STEPS,
            wallClockNeedsFinalization = false,
            loopGuardNeedsFinalization = false,
        )

        assertFalse(step.forceFinalization)
        assertFalse(step.skipResumableTools)
    }

    @Test
    fun `ultra budget reserves finalization only after 512 tool rounds`() {
        val lastToolRound = generationFinalizationStep(
            stepIndex = ULTRA_MAX_TOOL_LOOP_STEPS - 1,
            maxSteps = ULTRA_MAX_TOOL_LOOP_STEPS + 1,
            wallClockNeedsFinalization = false,
            loopGuardNeedsFinalization = false,
        )
        val finalAnswerRound = generationFinalizationStep(
            stepIndex = ULTRA_MAX_TOOL_LOOP_STEPS,
            maxSteps = ULTRA_MAX_TOOL_LOOP_STEPS + 1,
            wallClockNeedsFinalization = false,
            loopGuardNeedsFinalization = false,
        )

        assertFalse(lastToolRound.forceFinalization)
        assertTrue(finalAnswerRound.forceFinalization)
    }
}
