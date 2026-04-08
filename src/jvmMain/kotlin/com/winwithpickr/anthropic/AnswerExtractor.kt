package com.winwithpickr.anthropic

import com.winwithpickr.anthropic.models.ExtractedAnswer
import com.winwithpickr.anthropic.models.Tool
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * Extracts answers from host replies to prediction giveaways using Claude tool use.
 * Follows the same pattern as [DeadlineExtractor].
 */
class AnswerExtractor(
    private val client: AnthropicClient,
) {
    private val log = LoggerFactory.getLogger(AnswerExtractor::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        val EXTRACT_ANSWER_TOOL = Tool(
            name = "extract_answer",
            description = "Determine whether a host's reply reveals the answer to their prediction giveaway question.",
            inputSchema = buildAnswerToolSchema(),
        )

        val SYSTEM_PROMPT = """
            You determine whether a host's reply to their own prediction giveaway reveals the answer.

            Rules:
            - The host posted a prediction question and participants guessed. Now the host is replying.
            - If the reply reveals the outcome/result that participants were predicting, set is_answer=true.
            - Extract and normalize the answer for scoring (e.g. "Lakers 118, Celtics 112" or "${'$'}64,800").
            - Common answer patterns: final scores, prices, stats, outcomes, "it was X", "X won", etc.
            - Set is_answer=false for: hype replies ("great guesses!"), status updates ("game starts soon"),
              engagement replies ("112 people entered so far"), or anything that doesn't reveal the outcome.
            - When ambiguous, err on the side of is_answer=false — a missed answer just means the host
              replies again or uses "the answer is ..." explicitly.

            Use the extract_answer tool to return your result.
        """.trimIndent()

        private fun buildAnswerToolSchema(): JsonElement = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("is_answer") {
                    put("type", "boolean")
                    put("description", "Whether the reply reveals the answer to the prediction question")
                }
                putJsonObject("answer") {
                    put("type", "string")
                    put("description", "The extracted answer text, normalized for scoring")
                }
                putJsonObject("reasoning") {
                    put("type", "string")
                    put("description", "Brief explanation")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("is_answer")) }
        }
    }

    /**
     * Extract an answer from a host reply given the original prediction question.
     * Returns null on API failure (caller skips LLM path).
     */
    suspend fun extract(question: String, replyText: String): ExtractedAnswer? {
        val userMessage = "Question: \"$question\"\nHost reply: \"$replyText\""

        return try {
            val response = client.callTool(
                systemPrompt = SYSTEM_PROMPT,
                userMessage = userMessage,
                tool = EXTRACT_ANSWER_TOOL,
            ) ?: return null

            json.decodeFromJsonElement(ExtractedAnswer.serializer(), response)
        } catch (e: Exception) {
            log.warn("Answer extraction failed for reply '{}': {}", replyText.take(60), e.message)
            null
        }
    }
}
