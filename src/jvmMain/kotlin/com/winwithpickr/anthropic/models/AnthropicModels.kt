package com.winwithpickr.anthropic.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ── Request types ────────────────────────────────────────────────────────────

@Serializable
data class MessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int = 1024,
    val system: String? = null,
    val messages: List<Message>,
    val tools: List<Tool>? = null,
    @SerialName("tool_choice") val toolChoice: ToolChoice? = null,
)

@Serializable
data class Message(
    val role: String,
    val content: String,
)

@Serializable
data class Tool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonElement,
)

@Serializable
data class ToolChoice(
    val type: String,
    val name: String? = null,
)

// ── Response types ───────────────────────────────────────────────────────────

@Serializable
data class MessagesResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<ContentBlock>,
    @SerialName("stop_reason") val stopReason: String? = null,
)

@Serializable
data class ContentBlock(
    val type: String,
    val id: String? = null,
    val name: String? = null,
    val input: JsonElement? = null,
    val text: String? = null,
)

// ── Extracted command (tool input shape) ─────────────────────────────────────

@Serializable
data class ExtractedCommand(
    @SerialName("is_command") val isCommand: Boolean,
    val winners: Int = 1,
    @SerialName("trigger_mode") val triggerMode: String = "immediate",
    @SerialName("scheduled_delay_hours") val scheduledDelayHours: Int? = null,
    @SerialName("selection_mode") val selectionMode: String = "random",
    val reply: Boolean = true,
    val retweet: Boolean = false,
    val like: Boolean = false,
    @SerialName("quote_tweet") val quoteTweet: Boolean = false,
    @SerialName("follow_host") val followHost: Boolean = false,
    @SerialName("follow_accounts") val followAccounts: List<String> = emptyList(),
    @SerialName("min_account_age_days") val minAccountAgeDays: Int = 0,
    @SerialName("min_followers") val minFollowers: Int = 0,
    @SerialName("required_hashtag") val requiredHashtag: String? = null,
    @SerialName("required_quote_text") val requiredQuoteText: String? = null,
    @SerialName("min_tags") val minTags: Int = 0,
)

// ── Deadline extraction models ───────────────────────────────────────────────

@Serializable
data class ExtractedDeadline(
    val found: Boolean,
    @SerialName("deadline_iso") val deadlineIso: String? = null,
    val reasoning: String? = null,
    @SerialName("sport_context") val sportContext: SportContext? = null,
    /**
     * Semantic classification of the prediction question. Used to gate the
     * early-pick bonus (only temporal predictions — sports, crypto, market —
     * are eligible). Valid values: "sports", "crypto", "market", "trivia",
     * "other". Null if not classified (e.g. LLM extraction failed).
     */
    val category: String? = null,
)

@Serializable
data class SportContext(
    val sport: String? = null,
    val league: String? = null,
    val teams: List<String> = emptyList(),
)

// ── Answer extraction models ────────────────────────────────────────────────

@Serializable
data class ExtractedAnswer(
    @SerialName("is_answer") val isAnswer: Boolean,
    val answer: String? = null,
    val reasoning: String? = null,
)

// ── Prediction evaluation models ────────────────────────────────────────────

@Serializable
data class PredictionEntry(
    @SerialName("user_id") val userId: String,
    val username: String,
    @SerialName("reply_text") val replyText: String,
    /** ISO-8601 instant when the reply was submitted. Pass-through — evaluator ignores. */
    @SerialName("submitted_at") val submittedAt: String? = null,
)

@Serializable
data class ScoredPrediction(
    @SerialName("user_id") val userId: String,
    val username: String,
    @SerialName("extracted_prediction") val extractedPrediction: String,
    val score: Int,
    val reasoning: String,
    /** ISO-8601 instant when the reply was submitted. Pass-through — evaluator ignores. */
    @SerialName("submitted_at") val submittedAt: String? = null,
    /** Early-pick bonus applied on top of [score]. Null if no bonus. */
    @SerialName("bonus_points") val bonusPoints: Double? = null,
    /** [score] + [bonusPoints]. Null if no bonus applied. */
    @SerialName("adjusted_score") val adjustedScore: Double? = null,
)
