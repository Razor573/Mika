# Add native Google Gemini Decision preset (`gemini-decision-1`)

## Summary

Adds native support for Google's **Gemini Decision 1** (`gemini-decision-1`) API on the Decision model screen (`GoogleDecisionModel.kt`, `DecisionConfig.kt`, `DecisionSettings.kt`, `JevSettingsScreen.kt`).

- Speaks Google's native Decisions API request/response format (`POST https://generativelanguage.googleapis.com/v1beta/decisions`) instead of OpenRouter / SystemOne.
- Lets users reuse their **existing Gemini API key** stored under Settings -> Model (with an optional override in Decision settings), so no Jev, TypeSafe, or OpenRouter account is required.
- Rejects `aiplatform.googleapis.com` custom endpoints cleanly because the Decisions API is only supported via Google's Gemini endpoint (`generativelanguage.googleapis.com`).
- Preserves existing SystemOne / Jev defaults for users who do not switch presets.

## Why upstream users need it

Upstream's Decision model screen currently only speaks the SystemOne API shape (TypeSafe Jev, OpenRouter, or compatible custom endpoints). Upstream users who already use a Gemini Developer API key cannot configure `gemini-decision-1` natively without going through a third-party router or failing schema validation.

## Key files

- `core/src/main/kotlin/com/wangzan101/mika/core/decision/GoogleDecisionModel.kt`
- `core/src/main/kotlin/com/wangzan101/mika/core/decision/DecisionConfig.kt`
- `core/src/test/kotlin/com/wangzan101/mika/core/decision/GoogleDecisionModelTest.kt`
- `core/src/test/kotlin/com/wangzan101/mika/core/decision/DecisionConfigTest.kt`
- `app/src/main/java/com/wangzan101/mika/settings/DecisionSettings.kt`
- `app/src/main/java/com/wangzan101/mika/settings/ProviderStore.kt`
- `app/src/main/java/com/wangzan101/mika/ui/JevSettingsScreen.kt`
- `app/src/main/res/values/strings_jev.xml`
- `app/src/test/java/com/wangzan101/mika/settings/DecisionSettingsTest.kt`
- `app/src/test/java/com/wangzan101/mika/settings/ProviderStoreTest.kt`
- `app/src/test/java/com/wangzan101/mika/ui/JevSettingsTest.kt`
- `app/src/androidTest/java/com/wangzan101/mika/ui/JevSettingsScreenTest.kt`

## How to test on a phone

1. Configure a standard **Gemini** provider and API key in Settings -> Model.
2. Open the **Decision model** settings screen and select the **Google** preset (`gemini-decision-1`).
3. Verify the API key auto-fills/uses the saved Gemini API key (or enter a key directly) and tap Test / Save.
4. Confirm Mika sends a native `decisions` request to `generativelanguage.googleapis.com` and runs task gating/decision routing normally.
5. Verify entering an `aiplatform.googleapis.com` custom URL is cleanly rejected with an explicit validation message.

## What it does NOT do

- Does not force existing users off SystemOne / Jev or change default Decision model settings when disabled.
- Does not route through OpenRouter or call Vertex AI (`aiplatform.googleapis.com`).

## Deliberately left out

- Fork-only Vertex AI, Antigravity, Banking mode, and fork branding (`ProviderStore.kt` exposes only `Kind.Gemini` keys to `DecisionSettings`).
