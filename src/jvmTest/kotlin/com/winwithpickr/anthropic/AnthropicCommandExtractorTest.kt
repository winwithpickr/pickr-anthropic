package com.winwithpickr.anthropic

import com.winwithpickr.anthropic.models.ExtractedCommand
import com.winwithpickr.core.models.TriggerMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthropicCommandExtractorTest {

    @Test
    fun `extracted command maps to ParsedCommand correctly`() = runTest {
        val extracted = ExtractedCommand(
            isCommand = true,
            winners = 3,
            triggerMode = "watch",
            reply = true,
            retweet = true,
            like = false,
            quoteTweet = false,
            followHost = true,
            followAccounts = listOf("somebrand"),
            minAccountAgeDays = 7,
            minFollowers = 100,
            requiredHashtag = "giveaway",
            minTags = 2,
        )

        val result = mapExtractedCommand(extracted)
        assertNotNull(result)
        assertEquals(3, result.winners)
        assertEquals(TriggerMode.WATCH, result.triggerMode)
        assertTrue(result.conditions.reply)
        assertTrue(result.conditions.retweet)
        assertTrue(result.conditions.followHost)
        assertEquals(listOf("somebrand"), result.conditions.followAccounts)
        assertEquals(7, result.conditions.minAccountAgeDays)
        assertEquals(100, result.conditions.minFollowers)
        assertEquals("giveaway", result.conditions.requiredHashtag)
        assertEquals(2, result.conditions.minTags)
        assertNull(result.scheduledDelayMs)
    }

    @Test
    fun `is_command false returns null`() = runTest {
        val extracted = ExtractedCommand(isCommand = false)
        val result = mapExtractedCommand(extracted)
        assertNull(result)
    }

    @Test
    fun `scheduled mode maps delay correctly`() = runTest {
        val extracted = ExtractedCommand(
            isCommand = true,
            triggerMode = "scheduled",
            scheduledDelayHours = 2,
        )

        val result = mapExtractedCommand(extracted)
        assertNotNull(result)
        assertEquals(TriggerMode.SCHEDULED, result.triggerMode)
        assertEquals(2 * 3_600_000L, result.scheduledDelayMs)
    }

    @Test
    fun `scheduled delay clamped to 7 days`() = runTest {
        val extracted = ExtractedCommand(
            isCommand = true,
            triggerMode = "scheduled",
            scheduledDelayHours = 999,
        )

        val result = mapExtractedCommand(extracted)
        assertNotNull(result)
        assertEquals(7 * 86_400_000L, result.scheduledDelayMs)
    }

    @Test
    fun `winners clamped to at least 1`() = runTest {
        val extracted = ExtractedCommand(
            isCommand = true,
            winners = 0,
        )

        val result = mapExtractedCommand(extracted)
        assertNotNull(result)
        assertEquals(1, result.winners)
    }

    /**
     * Helper that applies the same mapping logic as [AnthropicCommandExtractor]
     * without needing a real API client.
     */
    private fun mapExtractedCommand(extracted: ExtractedCommand): com.winwithpickr.core.models.ParsedCommand? {
        if (!extracted.isCommand) return null

        val triggerMode = when (extracted.triggerMode) {
            "watch" -> TriggerMode.WATCH
            "scheduled" -> TriggerMode.SCHEDULED
            else -> TriggerMode.IMMEDIATE
        }

        val maxDelayMs = 7 * 86_400_000L
        val scheduledDelayMs = if (triggerMode == TriggerMode.SCHEDULED && extracted.scheduledDelayHours != null) {
            (extracted.scheduledDelayHours.toLong() * 3_600_000L).coerceAtMost(maxDelayMs)
        } else null

        return com.winwithpickr.core.models.ParsedCommand(
            winners = extracted.winners.coerceAtLeast(1),
            conditions = com.winwithpickr.core.models.EntryConditions(
                reply = extracted.reply,
                retweet = extracted.retweet,
                like = extracted.like,
                quoteTweet = extracted.quoteTweet,
                followHost = extracted.followHost,
                followAccounts = extracted.followAccounts,
                minAccountAgeDays = extracted.minAccountAgeDays,
                minFollowers = extracted.minFollowers,
                requiredHashtag = extracted.requiredHashtag,
                requiredQuoteText = extracted.requiredQuoteText,
                minTags = extracted.minTags,
            ),
            triggerMode = triggerMode,
            scheduledDelayMs = scheduledDelayMs,
        )
    }
}
