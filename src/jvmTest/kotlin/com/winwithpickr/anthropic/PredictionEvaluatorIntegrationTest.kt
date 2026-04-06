package com.winwithpickr.anthropic

import com.winwithpickr.anthropic.models.PredictionEntry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests that hit the real Anthropic API for prediction evaluation.
 *
 * Skipped when ANTHROPIC_API_KEY is not set.
 * Run manually: ./gradlew jvmTest -Dinclude.tags=integration
 */
@Tag("integration")
class PredictionEvaluatorIntegrationTest {

    companion object {
        private lateinit var evaluator: PredictionEvaluator

        @JvmStatic
        @BeforeAll
        fun setup() {
            val apiKey = System.getenv("ANTHROPIC_API_KEY")
            assumeTrue(apiKey != null && apiKey.isNotBlank(), "ANTHROPIC_API_KEY not set — skipping integration tests")
            val client = AnthropicClient(apiKey = apiKey, model = "claude-haiku-4-5-20251001")
            evaluator = PredictionEvaluator(client)
        }
    }

    // ── Scoring accuracy ──────────────────────────────────────────────

    @Test
    fun `exact match scores near 100`() = runTest(timeout = 30.seconds) {
        val predictions = listOf(
            PredictionEntry("u1", "alice", "UConn 82 Michigan 75"),
        )
        val result = evaluator.evaluate("Predict the final score", "UConn 82 Michigan 75", predictions)
        assertEquals(1, result.size)
        assertTrue(result[0].score >= 90, "Exact match should score >= 90, got ${result[0].score}")
        assertEquals("u1", result[0].userId)
    }

    @Test
    fun `close prediction scores higher than wrong one`() = runTest(timeout = 30.seconds) {
        val predictions = listOf(
            PredictionEntry("u1", "alice", "UConn 80 Michigan 73"),
            PredictionEntry("u2", "bob", "Duke 90 Kentucky 85"),
        )
        val result = evaluator.evaluate("Predict the final score", "UConn 82 Michigan 75", predictions)
        assertEquals(2, result.size)

        val alice = result.first { it.userId == "u1" }
        val bob = result.first { it.userId == "u2" }
        assertTrue(alice.score > bob.score, "Close prediction (${alice.score}) should beat wrong teams (${bob.score})")
        assertTrue(alice.score >= 50, "Close prediction should score >= 50, got ${alice.score}")
        assertTrue(bob.score <= 30, "Wrong teams should score <= 30, got ${bob.score}")
    }

    @Test
    fun `completely wrong prediction scores low`() = runTest(timeout = 30.seconds) {
        val predictions = listOf(
            PredictionEntry("u1", "alice", "Lakers 110 Celtics 105"),
        )
        val result = evaluator.evaluate("Predict the UConn vs Michigan score", "UConn 82 Michigan 75", predictions)
        assertEquals(1, result.size)
        assertTrue(result[0].score <= 20, "Wrong teams should score <= 20, got ${result[0].score}")
    }

    // ── Extraction from messy text ────────────────────────────────────

    @Test
    fun `extracts prediction from emoji-heavy reply`() = runTest(timeout = 30.seconds) {
        val predictions = listOf(
            PredictionEntry("u1", "alice", "🔥🔥🔥 UConn 82 Michigan 75 lets goooo 🏀💰"),
        )
        val result = evaluator.evaluate("Predict the final score", "UConn 82 Michigan 75", predictions)
        assertEquals(1, result.size)
        assertTrue(result[0].score >= 85, "Should extract prediction from emoji text, got ${result[0].score}")
        assertTrue(result[0].extractedPrediction.isNotBlank(), "Should extract a prediction")
    }

    @Test
    fun `extracts prediction with at-mentions and filler`() = runTest(timeout = 30.seconds) {
        val predictions = listOf(
            PredictionEntry("u1", "alice", "@host @winwithpickr I think UConn wins 82-75 over Michigan!!"),
        )
        val result = evaluator.evaluate("Predict the final score", "UConn 82 Michigan 75", predictions)
        assertEquals(1, result.size)
        assertTrue(result[0].score >= 80, "Should handle @mentions and filler, got ${result[0].score}")
    }

    // ── Mixed quality batch ───────────────────────────────────────────

    @Test
    fun `mixed batch ranks correctly`() = runTest(timeout = 30.seconds) {
        val predictions = listOf(
            PredictionEntry("u1", "alice", "UConn 82 Michigan 75"),
            PredictionEntry("u2", "bob", "UConn 80 Michigan 70"),
            PredictionEntry("u3", "carol", "Duke 90 Kentucky 85"),
            PredictionEntry("u4", "dave", "lol idk UConn maybe"),
            PredictionEntry("u5", "eve", "UConn 82 Michigan 74"),
        )
        val result = evaluator.evaluate("Predict the final score", "UConn 82 Michigan 75", predictions)
        assertEquals(5, result.size)

        val scores = result.associate { it.userId to it.score }
        // Exact match should be highest
        assertTrue(scores["u1"]!! >= scores["u2"]!!, "Exact match should score >= close guess")
        assertTrue(scores["u1"]!! >= scores["u5"]!!, "Exact match should score >= off-by-one")
        // Wrong teams should be lowest
        assertTrue(scores["u3"]!! < scores["u2"]!!, "Wrong teams should score less than close guess")
    }

    // ── Edge cases ────────────────────────────────────────────────────

    @Test
    fun `short replies are filtered out before LLM call`() = runTest(timeout = 15.seconds) {
        val predictions = listOf(
            PredictionEntry("u1", "alice", "hi"),
            PredictionEntry("u2", "bob", ""),
            PredictionEntry("u3", "carol", "ok"),
        )
        val result = evaluator.evaluate("Predict the score", "UConn 82 Michigan 75", predictions)
        assertEquals(0, result.size)
    }

    @Test
    fun `non-sports prediction works too`() = runTest(timeout = 30.seconds) {
        val predictions = listOf(
            PredictionEntry("u1", "alice", "Bitcoin will hit $100,000"),
            PredictionEntry("u2", "bob", "Bitcoin to $95,000"),
            PredictionEntry("u3", "carol", "Ethereum $5,000"),
        )
        val result = evaluator.evaluate(
            "What will Bitcoin's price be at end of day?",
            "Bitcoin closed at $99,500",
            predictions,
        )
        assertEquals(3, result.size)

        val scores = result.associate { it.userId to it.score }
        // alice ($100K) and bob ($95K) should both be closer than carol (wrong coin)
        assertTrue(scores["u1"]!! > scores["u3"]!!, "Close BTC guess should beat ETH guess")
        assertTrue(scores["u2"]!! > scores["u3"]!!, "Close BTC guess should beat ETH guess")
    }

    // ── Large pool stress tests ───────────────────────────────────────

    /**
     * Simulates a medium giveaway (~300 entries).
     * Generates realistic messy replies across a score distribution:
     *   - ~5% exact/near-exact matches
     *   - ~20% close guesses (right teams, off by a few points)
     *   - ~40% partial (right teams, wrong ballpark)
     *   - ~25% wrong teams entirely
     *   - ~10% junk (emoji-only, off-topic, short filler that passes the 3-char filter)
     */
    @Test
    fun `300 entry giveaway scores and ranks correctly`() = runTest(timeout = 3.minutes) {
        val entries = generateSportsPool(size = 300, seed = 42)
        val result = evaluator.evaluate(
            "Predict the final score of UConn vs Michigan",
            "UConn 82 Michigan 75",
            entries,
        )

        // All non-junk entries should get a score back
        assertEquals(300, result.size, "Every entry should be scored")

        // Exact matches that the LLM actually scored should all be 90+
        val exactIds = entries.filter { it.replyText.contains("UConn 82") && it.replyText.contains("Michigan 75") }
            .map { it.userId }.toSet()
        val exactResults = result.filter { it.userId in exactIds && it.extractedPrediction.isNotBlank() }
        assertTrue(exactResults.isNotEmpty(), "LLM should score at least some exact matches")
        for (r in exactResults) {
            assertTrue(
                r.score >= 90,
                "Exact match '${r.extractedPrediction}' should score >= 90, got ${r.score} (user=${r.userId})",
            )
        }
        // At most 10% of exact matches should be dropped by the LLM
        val dropRate = 1.0 - (exactResults.size.toDouble() / exactIds.size)
        assertTrue(dropRate <= 0.10, "LLM dropped ${"%.0f".format(dropRate * 100)}% of exact matches (${exactIds.size - exactResults.size}/${exactIds.size})")

        // Wrong-team entries should average much lower than right-team entries
        val wrongTeamIds = entries.filter { it.replyText.contains("Duke") || it.replyText.contains("Kentucky") }
            .map { it.userId }.toSet()
        val rightTeamIds = entries.filter {
            it.replyText.contains("UConn") && it.replyText.contains("Michigan") &&
                it.userId !in exactIds
        }.map { it.userId }.toSet()

        val avgWrong = result.filter { it.userId in wrongTeamIds }.map { it.score }.average()
        val avgRight = result.filter { it.userId in rightTeamIds }.map { it.score }.average()
        assertTrue(
            avgRight > avgWrong,
            "Right-team avg (${"%.1f".format(avgRight)}) should beat wrong-team avg (${"%.1f".format(avgWrong)})",
        )

        // Sanity: scores are in 0-100 range
        assertTrue(result.all { it.score in 0..100 }, "All scores should be 0-100")
    }

    /**
     * Simulates a large viral giveaway (~500 entries, 10 batches).
     * Primarily tests that batching works end-to-end without data loss or corruption,
     * and that relative ranking holds across batch boundaries.
     */
    @Test
    fun `500 entry viral giveaway batches correctly`() = runTest(timeout = 5.minutes) {
        val entries = generateSportsPool(size = 500, seed = 99)
        val result = evaluator.evaluate(
            "Predict the final score of UConn vs Michigan",
            "UConn 82 Michigan 75",
            entries,
        )

        assertEquals(500, result.size, "Every entry should be scored (no data loss across batches)")

        // Verify all user IDs are present (no duplicates or drops across batches)
        val returnedIds = result.map { it.userId }.toSet()
        val inputIds = entries.map { it.userId }.toSet()
        assertEquals(inputIds, returnedIds, "Returned IDs should match input IDs exactly")

        // Score distribution sanity: not all the same score
        val distinctScores = result.map { it.score }.distinct()
        assertTrue(distinctScores.size >= 5, "Should have score variety, got ${distinctScores.size} distinct scores")

        // Top scorer should be an exact or near-exact match
        val topScore = result.maxByOrNull { it.score }!!
        assertTrue(topScore.score >= 80, "Top scorer should be >= 80, got ${topScore.score}")
    }

    // ── Pool generators ───────────────────────────────────────────────

    private fun generateSportsPool(size: Int, seed: Int): List<PredictionEntry> {
        val rng = Random(seed)
        val teams = listOf("UConn", "Michigan")
        val wrongTeams = listOf("Duke" to "Kentucky", "Alabama" to "Houston", "Purdue" to "NC State")
        val filler = listOf(
            "🔥🔥🔥 let's go!", "this is it!", "easy money 💰",
            "I'm calling it now", "no doubt about it", "trust me on this one",
            "@host @winwithpickr", "LFG!!", "final answer:",
        )
        val junk = listOf(
            "lmao who knows", "following for updates", "good luck everyone 🍀",
            "retweet done ✅", "nice giveaway! hope I win", "🏀🏀🏀🏀🏀",
            "tag @friend1 @friend2 @friend3", "bump", "pleeease pick me",
        )

        return (1..size).map { i ->
            val userId = "u$i"
            val username = "user$i"
            val roll = rng.nextDouble()
            val text = when {
                // ~5% exact match
                roll < 0.05 -> {
                    val prefix = if (rng.nextBoolean()) filler.random(rng) + " " else ""
                    "${prefix}UConn 82 Michigan 75"
                }
                // ~20% close (right teams, off by 1-5 points)
                roll < 0.25 -> {
                    val s1 = 82 + rng.nextInt(-5, 6)
                    val s2 = 75 + rng.nextInt(-5, 6)
                    val prefix = if (rng.nextBoolean()) filler.random(rng) + " " else ""
                    "${prefix}UConn $s1 Michigan $s2"
                }
                // ~20% partial (right teams, wrong ballpark)
                roll < 0.45 -> {
                    val s1 = rng.nextInt(60, 110)
                    val s2 = rng.nextInt(55, 100)
                    "UConn $s1 Michigan $s2"
                }
                // ~20% one team right
                roll < 0.65 -> {
                    if (rng.nextBoolean()) {
                        "UConn ${rng.nextInt(70, 95)} Duke ${rng.nextInt(60, 90)}"
                    } else {
                        "Alabama ${rng.nextInt(70, 95)} Michigan ${rng.nextInt(60, 90)}"
                    }
                }
                // ~25% wrong teams
                roll < 0.90 -> {
                    val (t1, t2) = wrongTeams.random(rng)
                    "$t1 ${rng.nextInt(65, 105)} $t2 ${rng.nextInt(60, 100)}"
                }
                // ~10% junk/off-topic
                else -> junk.random(rng)
            }
            PredictionEntry(userId, username, text)
        }
    }
}
