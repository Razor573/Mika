# Add opt-in Google Search grounding via Gemini

## Summary

Adds **Google Search grounding** (`WebSearchProvider.Google`) as an opt-in web search option in Settings -> Web search (`GoogleGroundingSearch.kt`, `WebSearchSettings.kt`, `ProviderStore.kt`).

- **OFF by default:** Web search stays disabled on fresh installs and never changes existing search behavior unless the user explicitly enables it and selects Google.
- **No extra search API key or SearXNG server required:** Uses the user's already-configured Gemini API key and Gemini model (`generateContent` with `tools: [{"googleSearch": {}}]`) via `ProviderStore.googleGroundingSearch()`.
- **Clear billing disclosure in UI:** Shows the pricing note in Web search settings (`5,000 free grounded prompts/month, then $14/1,000 queries`).

## Why upstream users need it

Upstream web search options require separate third-party search accounts/API keys (such as You, Jina, Exa, TinyFish) or self-hosting SearXNG. Users who already have a Gemini API key configured in Mika can opt into Google Search grounding right away without creating or paying for a separate search service.

## Key files

- `core/src/main/kotlin/com/wangzan101/mika/core/search/GoogleGroundingSearch.kt`
- `core/src/main/kotlin/com/wangzan101/mika/core/search/WebSearchProvider.kt`
- `app/src/main/java/com/wangzan101/mika/settings/ProviderStore.kt`
- `app/src/main/java/com/wangzan101/mika/settings/WebSearchSettings.kt`
- `app/src/main/java/com/wangzan101/mika/ui/WebSearchSettings.kt`
- `app/src/main/java/com/wangzan101/mika/ui/components/Controls.kt`
- `app/src/main/java/com/wangzan101/mika/ui/SettingsScreen.kt`
- `app/src/main/res/values/strings_web_search.xml`
- `core/src/test/kotlin/com/wangzan101/mika/core/search/GoogleGroundingSearchTest.kt`
- `core/src/test/kotlin/com/wangzan101/mika/core/search/ProviderSearchTest.kt`
- `app/src/test/java/com/wangzan101/mika/settings/ProviderStoreTest.kt`
- `app/src/test/java/com/wangzan101/mika/settings/WebSearchSettingsTest.kt`
- `app/src/test/java/com/wangzan101/mika/ui/WebSearchCopyTest.kt`

## How to test on a phone

1. Open Settings -> Web search on a fresh install and confirm Web search is **OFF** by default.
2. Without a Gemini provider configured, select **Google** and verify Mika reports that no supported Gemini provider is active yet.
3. Configure a Gemini provider and valid API key in Settings -> Model.
4. Return to Settings -> Web search, enable Web search, choose **Google**, confirm the `5,000 free grounded prompts/month, then $14/1,000 queries` note is visible, and run a chat query that searches the web to verify search hits and snippets return.

## What it does NOT do

- Does not turn on Web search automatically or send search queries to Google when Web search is disabled or another provider is selected.
- Does not route search queries through third-party proxies or bypass Gemini API billing/rate limits (`429` retry-after and `401`/`403`/`SAFETY` errors are mapped directly).
- Does not touch or alter existing TinyFish, SearXNG, You, Jina, or Exa providers.

## Deliberately left out

- Vertex AI and Antigravity transport branches from the fork (kept `GoogleGroundingSearch` cleanly isolated for Gemini on upstream without introducing fork-only providers or `GeminiCodec`).
