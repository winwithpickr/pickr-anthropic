package com.winwithpickr.anthropic

import com.winwithpickr.anthropic.models.ExtractedDeadline
import com.winwithpickr.anthropic.models.Tool
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Extracts a submission deadline from prediction giveaway questions using Claude tool use.
 * Follows the same pattern as [PredictionEvaluator].
 */
class DeadlineExtractor(
    private val client: AnthropicClient,
) {
    private val log = LoggerFactory.getLogger(DeadlineExtractor::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        val EXTRACT_DEADLINE_TOOL = Tool(
            name = "extract_deadline",
            description = "Extract a submission deadline from a prediction giveaway question. " +
                "The deadline should be BEFORE the event outcome becomes knowable.",
            inputSchema = buildDeadlineToolSchema(),
        )

        val SYSTEM_PROMPT = """
            You extract submission deadlines from prediction giveaway questions.
            The goal is to close submissions BEFORE the event outcome becomes knowable.

            Rules:
            - Sports games: deadline = scheduled game start time (outcome unknown until game begins)
            - Market closes: deadline = market close time (NYSE 4pm ET, crypto "Friday close" = Fri 11:59pm UTC)
            - Explicit time references: "by Friday", "before 3pm", "tomorrow" — interpret literally
            - "tonight" = today 11:59pm in the inferred timezone
            - "this weekend" = Saturday 12:00am in the inferred timezone
            - Default timezone: US Eastern (America/New_York) when ambiguous
            - If no time reference can be inferred, set found=false
            - Always return the deadline as ISO-8601 with timezone offset (e.g. 2026-04-11T16:00:00-04:00)
            - The deadline must be in the future relative to the provided current time

            Use the extract_deadline tool to return your result.
        """.trimIndent()

        private fun buildDeadlineToolSchema(): JsonElement = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("found") {
                    put("type", "boolean")
                    put("description", "Whether a submission deadline could be inferred")
                }
                putJsonObject("deadline_iso") {
                    put("type", "string")
                    put("description", "ISO-8601 datetime with offset for when submissions should close. Should be BEFORE the outcome becomes knowable.")
                }
                putJsonObject("reasoning") {
                    put("type", "string")
                    put("description", "Brief explanation of how the deadline was determined")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("found")) }
        }
    }

    /**
     * Extract a submission deadline from the question text.
     * Returns null on API failure (caller falls back to 7-day default).
     */
    suspend fun extract(question: String, now: Instant): ExtractedDeadline? {
        val currentTimeIso = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .withZone(ZoneId.of("America/New_York"))
            .format(now)

        val userMessage = "Question: \"$question\"\nCurrent time: $currentTimeIso"

        return try {
            val response = client.callTool(
                systemPrompt = SYSTEM_PROMPT,
                userMessage = userMessage,
                tool = EXTRACT_DEADLINE_TOOL,
            ) ?: return null

            json.decodeFromJsonElement(ExtractedDeadline.serializer(), response)
        } catch (e: Exception) {
            log.warn("Deadline extraction failed for question '{}': {}", question.take(60), e.message)
            null
        }
    }
}
