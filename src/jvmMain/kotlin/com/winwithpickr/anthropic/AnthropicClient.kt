package com.winwithpickr.anthropic

import com.winwithpickr.anthropic.models.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Thin Ktor wrapper around the Anthropic Messages API.
 */
class AnthropicClient(
    private val apiKey: String,
    private val model: String,
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 5_000 }
    }

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"

        val EXTRACT_COMMAND_TOOL = Tool(
            name = "extract_command",
            description = "Extract a structured giveaway command from the user's tweet text. " +
                "Set is_command=false if the text is not a giveaway command.",
            inputSchema = buildToolSchema(),
        )

        val SYSTEM_PROMPT = """
            You are a giveaway command parser for a Twitter bot called pickr.
            Users mention the bot to start or pick winners for giveaways.

            Extract the structured command from the tweet text. Key rules:
            - A command must mention starting a giveaway, picking winners, or drawing winners.
            - "start" or "watch" → trigger_mode "watch" (bot watches for host signal to end)
            - "pick" without "start" → trigger_mode "immediate" (pick winners now)
            - "in Xh" or "in Xd" → trigger_mode "scheduled" with scheduled_delay_hours
            - Entry sources: replies (default), retweets, likes, quote tweets
            - "from replies+retweets" or "who replied and retweeted" → reply=true, retweet=true
            - "must be following" or "followers only" → follow_host=true
            - "follow @handle" → follow_accounts=["handle"] (strip @)
            - Winner count defaults to 1 unless specified
            - If the text doesn't contain a giveaway command, set is_command=false
            - Ignore the bot's @handle in the text when parsing
        """.trimIndent()

        private fun buildToolSchema(): JsonElement = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("is_command") { put("type", "boolean"); put("description", "Whether this text contains a giveaway command") }
                putJsonObject("winners") { put("type", "integer"); put("description", "Number of winners to pick (default 1)") }
                putJsonObject("trigger_mode") { put("type", "string"); put("description", "immediate, watch, or scheduled"); putJsonArray("enum") { add(kotlinx.serialization.json.JsonPrimitive("immediate")); add(kotlinx.serialization.json.JsonPrimitive("watch")); add(kotlinx.serialization.json.JsonPrimitive("scheduled")) } }
                putJsonObject("scheduled_delay_hours") { put("type", "integer"); put("description", "Hours to delay before picking (only for scheduled mode)") }
                putJsonObject("reply") { put("type", "boolean"); put("description", "Include replies as entry source") }
                putJsonObject("retweet") { put("type", "boolean"); put("description", "Include retweets as entry source") }
                putJsonObject("like") { put("type", "boolean"); put("description", "Include likes as entry source") }
                putJsonObject("quote_tweet") { put("type", "boolean"); put("description", "Include quote tweets as entry source") }
                putJsonObject("follow_host") { put("type", "boolean"); put("description", "Require following the giveaway host") }
                putJsonObject("follow_accounts") { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "List of account handles (without @) that entrants must follow") }
                putJsonObject("min_account_age_days") { put("type", "integer"); put("description", "Minimum account age in days") }
                putJsonObject("min_followers") { put("type", "integer"); put("description", "Minimum follower count") }
                putJsonObject("required_hashtag") { put("type", "string"); put("description", "Required hashtag (without #)") }
                putJsonObject("required_quote_text") { put("type", "string"); put("description", "Required text in quote tweets") }
                putJsonObject("min_tags") { put("type", "integer"); put("description", "Minimum number of friends tagged") }
            }
            putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("is_command")) }
        }
    }

    /**
     * Send a tweet text to Claude and extract a structured command via tool use.
     * Returns the raw [ExtractedCommand] or null if the API call fails.
     */
    suspend fun extractCommand(text: String, botHandle: String): ExtractedCommand? {
        val cleanText = text.replace("@$botHandle", "", ignoreCase = true).trim()

        val request = MessagesRequest(
            model = model,
            maxTokens = 1024,
            system = SYSTEM_PROMPT,
            messages = listOf(Message(role = "user", content = "Parse this tweet: \"$cleanText\"")),
            tools = listOf(EXTRACT_COMMAND_TOOL),
            toolChoice = ToolChoice(type = "tool", name = "extract_command"),
        )

        val response = client.post(API_URL) {
            contentType(ContentType.Application.Json)
            header("x-api-key", apiKey)
            header("anthropic-version", API_VERSION)
            setBody(request)
        }

        val messagesResponse = response.body<MessagesResponse>()

        val toolBlock = messagesResponse.content.firstOrNull { it.type == "tool_use" && it.name == "extract_command" }
            ?: return null

        val inputJson = toolBlock.input ?: return null
        return json.decodeFromJsonElement(ExtractedCommand.serializer(), inputJson)
    }
}
