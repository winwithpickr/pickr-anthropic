# pickr-anthropic

Anthropic Claude integration for [@winwithpickr](https://x.com/winwithpickr) — natural language command extraction using Claude's tool use API.

Private, MIT-licensed. JVM only (Kotlin Multiplatform, JVM target).

Part of the [winwithpickr](https://github.com/winwithpickr) ecosystem:
- [pickr-engine](https://github.com/winwithpickr/pickr-engine) — platform-agnostic verification core
- [pickr-twitter](https://github.com/winwithpickr/pickr-twitter) — X/Twitter SDK
- [pickr-telegram](https://github.com/winwithpickr/pickr-telegram) — Telegram SDK
- **pickr-anthropic** — this repo

## What this library does

pickr-anthropic implements the `CommandExtractor` interface from pickr-engine using the Anthropic Messages API with tool use. It lets hosts describe giveaways in plain English instead of memorizing structured commands.

- **Natural language extraction** — "pick 3 winners from people who replied and retweeted, must be following me" → structured `ParsedCommand`
- **Tool use** — Claude extracts command fields via a typed tool schema, not free-text parsing
- **Model-configurable** — defaults to `claude-haiku-4-5-20251001` for speed/cost; configurable via `AnthropicConfig`

## How it fits in

On the server, `SmartCommandParser` orchestrates the flow:

1. **Pre-filter** — skip LLM for mentions without giveaway signal words
2. **Redis cache** — avoid duplicate API calls for the same mention text
3. **LLM extraction** — call `AnthropicCommandExtractor.extract()`
4. **Regex fallback** — on any LLM failure, fall back to the regex `CommandParser` in pickr-twitter

The regex parser in pickr-twitter handles structured commands (`@winwithpickr pick 3 from replies+retweets`). This library handles everything else — freeform text that the regex can't parse.

## Usage

```kotlin
val config = AnthropicConfig(
    apiKey = System.getenv("ANTHROPIC_API_KEY"),
    model = "claude-haiku-4-5-20251001",  // default
)

val extractor = AnthropicCommandExtractor(config)

// Returns null if the text isn't a giveaway command
val command: ParsedCommand? = extractor.extract(
    text = "@winwithpickr start a giveaway, pick 3 winners from replies, followers only",
    botHandle = "winwithpickr",
)
```

## Modules

| File | Description |
|---|---|
| `AnthropicCommandExtractor` | `CommandExtractor` implementation — maps LLM output to `ParsedCommand` |
| `AnthropicClient` | Low-level Anthropic Messages API client with tool use |
| `AnthropicModels` | Request/response models for the Anthropic API |
| `AnthropicConfig` | API key + model configuration |

## Environment variables

| Variable | Description |
|---|---|
| `ANTHROPIC_API_KEY` | Anthropic API key (required) |
| `ANTHROPIC_MODEL` | Model ID (optional, defaults to `claude-haiku-4-5-20251001`) |

## Building

```bash
# Run unit tests
./gradlew jvmTest

# Run integration tests (requires ANTHROPIC_API_KEY)
ANTHROPIC_API_KEY=sk-... ./gradlew jvmTest -Dinclude.tags=integration

# Publish to Maven local
./gradlew publishToMavenLocal
```

## License

MIT — see [LICENSE](LICENSE)
