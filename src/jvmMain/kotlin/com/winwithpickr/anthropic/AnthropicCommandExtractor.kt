package com.winwithpickr.anthropic

import com.winwithpickr.core.CommandExtractor
import com.winwithpickr.core.models.EntryConditions
import com.winwithpickr.core.models.ParsedCommand
import com.winwithpickr.core.models.TriggerMode

data class AnthropicConfig(
    val apiKey: String,
    val model: String = "claude-haiku-4-5-20251001",
)

/**
 * [CommandExtractor] implementation that uses the Anthropic Messages API
 * with tool use to extract structured giveaway commands from natural language.
 */
class AnthropicCommandExtractor(config: AnthropicConfig) : CommandExtractor {

    private val client = AnthropicClient(apiKey = config.apiKey, model = config.model)

    override suspend fun extract(text: String, botHandle: String): ParsedCommand? {
        val extracted = client.extractCommand(text, botHandle) ?: return null
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

        return ParsedCommand(
            winners = extracted.winners.coerceAtLeast(1),
            conditions = EntryConditions(
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
