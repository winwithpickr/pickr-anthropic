package com.winwithpickr.anthropic

import com.winwithpickr.anthropic.models.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * Evaluates prediction entries against a correct answer using Claude tool use.
 * Processes in batches of [BATCH_SIZE] to stay within token limits.
 */
class PredictionEvaluator(
    private val client: AnthropicClient,
) {
    private val log = LoggerFactory.getLogger(PredictionEvaluator::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        const val BATCH_SIZE = 25
        private const val MIN_REPLY_LENGTH = 3

        val SCORE_PREDICTIONS_TOOL = Tool(
            name = "score_predictions",
            description = "Score each prediction against the correct answer. " +
                "Extract the core prediction from each reply (ignore @mentions, emojis, filler text). " +
                "Score 0-100 where 100 = exact match. For numeric predictions, score based on closeness.",
            inputSchema = buildScoreToolSchema(),
        )

        val SYSTEM_PROMPT = """
            You are a prediction evaluator for a giveaway bot. Your job is to:
            1. Extract the core prediction from each reply text (ignore @mentions, emojis, hashtags, and filler)
            2. Compare it to the correct answer
            3. Score each prediction 0-100:
               - 100 = exact match or functionally equivalent
               - 80-99 = very close (e.g. off by 1 point in a score prediction)
               - 50-79 = partially correct (e.g. got one team's score right)
               - 1-49 = somewhat related but mostly wrong
               - 0 = completely wrong, unrelated, or no prediction found
            4. For score predictions (e.g. sports), compare each component
            5. Be generous in extraction — look for numbers, scores, names even in messy replies
            6. Provide brief reasoning for each score

            Always use the score_predictions tool to return your results.
        """.trimIndent()

        private fun buildScoreToolSchema(): JsonElement = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("scores") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("user_id") { put("type", "string") }
                            putJsonObject("username") { put("type", "string") }
                            putJsonObject("extracted_prediction") { put("type", "string"); put("description", "The core prediction extracted from the reply") }
                            putJsonObject("score") { put("type", "integer"); put("description", "0-100 score") }
                            putJsonObject("reasoning") { put("type", "string"); put("description", "Brief explanation of the score") }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("user_id"))
                            add(JsonPrimitive("username"))
                            add(JsonPrimitive("extracted_prediction"))
                            add(JsonPrimitive("score"))
                            add(JsonPrimitive("reasoning"))
                        }
                    }
                }
            }
            putJsonArray("required") { add(JsonPrimitive("scores")) }
        }
    }

    /**
     * Evaluate all predictions against the correct answer.
     * Pre-filters empty/too-short replies, then batches the rest through the LLM.
     */
    suspend fun evaluate(
        question: String,
        answer: String,
        predictions: List<PredictionEntry>,
    ): List<ScoredPrediction> {
        val filtered = predictions.filter { it.replyText.trim().length >= MIN_REPLY_LENGTH }
        if (filtered.isEmpty()) return emptyList()

        log.info("Evaluating {} predictions (filtered from {}) against answer: {}",
            filtered.size, predictions.size, answer.take(80))

        val results = mutableListOf<ScoredPrediction>()
        for (batch in filtered.chunked(BATCH_SIZE)) {
            try {
                val scored = evaluateBatch(question, answer, batch)
                results.addAll(scored)
            } catch (e: Exception) {
                log.error("Prediction batch evaluation failed ({} entries): {}", batch.size, e.message)
                // Score failed batch as 0 so they're not lost
                batch.forEach { entry ->
                    results.add(ScoredPrediction(
                        userId = entry.userId,
                        username = entry.username,
                        extractedPrediction = "",
                        score = 0,
                        reasoning = "Evaluation failed",
                    ))
                }
            }
        }
        return results
    }

    private suspend fun evaluateBatch(
        question: String,
        answer: String,
        batch: List<PredictionEntry>,
    ): List<ScoredPrediction> {
        val predictionsText = batch.mapIndexed { i, p ->
            "${i + 1}. @${p.username} (id: ${p.userId}): \"${p.replyText}\""
        }.joinToString("\n")

        val userMessage = buildString {
            appendLine("Question: $question")
            appendLine("Correct answer: $answer")
            appendLine()
            appendLine("Predictions to score:")
            append(predictionsText)
        }

        val response = client.callTool(
            systemPrompt = SYSTEM_PROMPT,
            userMessage = userMessage,
            tool = SCORE_PREDICTIONS_TOOL,
        ) ?: return batch.map { entry ->
            ScoredPrediction(entry.userId, entry.username, "", 0, "No response from evaluator")
        }

        val scoresArray = response.jsonObject["scores"]?.jsonArray ?: return batch.map { entry ->
            ScoredPrediction(entry.userId, entry.username, "", 0, "No scores in response")
        }

        // Index LLM results by userId, clamp scores to 0-100, ensure every input entry has a result
        val scored = scoresArray.map { element ->
            json.decodeFromJsonElement(ScoredPrediction.serializer(), element).let {
                it.copy(score = it.score.coerceIn(0, 100))
            }
        }.associateBy { it.userId }

        return batch.map { entry ->
            scored[entry.userId] ?: ScoredPrediction(
                userId = entry.userId,
                username = entry.username,
                extractedPrediction = "",
                score = 0,
                reasoning = "Not returned by evaluator",
            )
        }
    }
}
