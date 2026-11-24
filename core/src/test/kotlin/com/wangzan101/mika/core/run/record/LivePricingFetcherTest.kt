package com.wangzan101.mika.core.run.record

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

class LivePricingFetcherTest {
    private val sampleGeminiPage = """
        ## Gemini 4 Hydrogen {% id=&quot;gemini-4-hydrogen&quot; %}
        {% tabs %}
          {% tab title=&quot;Standard&quot; %}
          {% table %}
          * Feature
          * Free Tier
          * Paid Tier, per 1M tokens in USD

          ---

          * Input price
          * Free of charge
          * ${'$'}1.50

          ---

          * Output price (including thinking tokens)
          * Free of charge
          * ${'$'}7.50

          ---

          * Context caching price
          * Free of charge
          * ${'$'}0.075
          {% /table %}
          {% /tab %}
        {% /tabs %}

        ## Gemini 3.8 Flash {% id=&quot;gemini-3.8-flash&quot; %}
        {% tabs %}
          {% tab title=&quot;Standard&quot; %}
          {% table %}
          * Feature
          * Free Tier
          * Paid Tier, per 1M tokens in USD

          ---

          * Input price
          * Free of charge
          * ${'$'}0.75 through December 31, 2026.
          ${'$'}1.50 starting January 1, 2027.

          ---

          * Output price (including thinking tokens)
          * Free of charge
          * ${'$'}3.75 through December 31, 2026.
          ${'$'}7.50 starting January 1, 2027.

          ---

          * Context caching price
          * Free of charge
          * ${'$'}0.075 through December 31, 2026.
          ${'$'}0.15 starting January 1, 2027.
          ${'$'}0.50 / 1,000,000 tokens per hour (storage price) through December 31, 2026.
          {% /table %}
          {% /tab %}
        {% /tabs %}
    """.trimIndent()

    private val sampleVertexPage = """
        <button role="tab" id="tab-g0-t0" track-metadata-eventdetail="Standard">Standard</button>
        <button role="tab" id="tab-g0-t1" track-metadata-eventdetail="Priority">Priority</button>
        <button role="tab" id="tab-g0-t2" track-metadata-eventdetail="Flex/Batch">Flex/Batch</button>
        <div role="tabpanel" aria-labelledby="tab-g0-t0">
          <table>
            <tr><th>Model</th><th>Type</th><th>Region</th><th>Price (/1M tokens)</th><th>Price (/1M tokens) Cache read</th></tr>
            <tr><td>Gemini 4 Hydrogen</td><td>Input (text, image, video, audio)</td><td>Global</td><td>${'$'}1.50</td><td>${'$'}0.075</td></tr>
            <tr><td></td><td></td><td>Non-global</td><td>${'$'}1.65</td><td>${'$'}0.0825</td></tr>
            <tr><td></td><td>Text output (response and reasoning)</td><td>Global</td><td>${'$'}7.50</td><td>${'$'}7.50</td></tr>
            <tr><td>Gemini 3.8 Flash* through December 31, 2026</td><td>Input (text, image, video, audio)</td><td>Global</td><td>${'$'}0.75</td><td>${'$'}0.075</td></tr>
            <tr><td></td><td>Text output (response and reasoning)</td><td>Global</td><td>${'$'}3.75</td><td>N/A</td></tr>
            <tr><td>Gemini 3.8 Flash Starting January 1, 2027</td><td>Input (text, image, video, audio)</td><td>Global</td><td>${'$'}1.50</td><td>${'$'}0.15</td></tr>
            <tr><td></td><td>Text output (response and reasoning)</td><td>Global</td><td>${'$'}7.50</td><td>N/A</td></tr>
          </table>
        </div>
        <div role="tabpanel" aria-labelledby="tab-g0-t2">
          <table>
            <tr><th>Model</th><th>Type</th><th>Region</th><th>Price (/1M tokens)</th><th>Price (/1M tokens) Cache read</th></tr>
            <tr><td>Gemini 4 Hydrogen</td><td>Input (text, image, video, audio)</td><td>Global</td><td>${'$'}0.75</td><td>${'$'}0.0375</td></tr>
            <tr><td></td><td>Text output (response and reasoning)</td><td>Global</td><td>${'$'}3.75</td><td>N/A</td></tr>
          </table>
        </div>
    """.trimIndent()

    @After
    fun resetEstimator() {
        UsageCostEstimator.installCatalog(null)
    }

    @Test
    fun parsesOfficialPagesAndComputesExactVertexHydrogenAndEffectiveDatedFlashCost() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":403}}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody(sampleVertexPage))
            server.enqueue(MockResponse().setResponseCode(200).setBody(sampleGeminiPage))

            val snapshot = LivePricingFetcher.fetch(
                http = OkHttpClient(),
                billingBearerToken = "tok",
                nowIso = "2026-11-24T20:00:00Z",
                cloudBillingBaseUrl = server.url("/v1/services").toString(),
                vertexPricingUrl = server.url("/vertex/pricing").toString(),
                geminiPricingUrl = server.url("/gemini/pricing").toString(),
            )
            assertNotNull(snapshot)
            UsageCostEstimator.installCatalog(snapshot)

            // Verify known token count against Vertex gemini-4-hydrogen ($1.50 in / $7.50 out / $0.075 cached):
            // 1,000,000 input (400,000 cached + 600,000 uncached) + 100,000 output = 0.6*1.50 + 0.4*0.075 + 0.1*7.50 = $1.68
            val est = UsageCostEstimator.estimate(
                model = "gemini-4-hydrogen",
                role = "main",
                inputTokens = 1_000_000L,
                cachedTokens = 400_000L,
                outputTokens = 100_000L,
                provider = "vertex",
                asOf = LocalDate.of(2026, 11, 24),
            )
            assertEquals(1.68, est.costUsd, 0.000001)
            assertFalse(est.price.fallback)
            assertEquals(1.50, est.price.inputPerM, 0.0001)
            assertEquals(7.50, est.price.outputPerM, 0.0001)
            assertEquals(0.075, est.price.cachedInputPerM, 0.0001)
            assertEquals("2026-11-24T20:00:00Z", est.price.fetchedAt)
            assertEquals("vertex_pricing_page+gemini_pricing_page", est.price.source)

            // Flex/Batch tier on Vertex
            val batchPrice = UsageCostEstimator.resolvePrice(
                model = "gemini-4-hydrogen",
                provider = "vertex",
                tier = "batch",
                asOf = LocalDate.of(2026, 11, 24),
            )
            assertEquals(0.75, batchPrice.inputPerM, 0.0001)
            assertEquals(3.75, batchPrice.outputPerM, 0.0001)

            // Effective dates on gemini-3.8-flash (intro through 2026-12-31, standard starting 2027-01-01)
            val flashIntro = UsageCostEstimator.resolvePrice("gemini-3.8-flash", provider = "gemini", asOf = LocalDate.of(2026, 11, 24))
            assertEquals(0.75, flashIntro.inputPerM, 0.0001)
            assertEquals(3.75, flashIntro.outputPerM, 0.0001)
            assertEquals(0.075, flashIntro.cachedInputPerM, 0.0001)

            val flash2027 = UsageCostEstimator.resolvePrice("gemini-3.8-flash", provider = "gemini", asOf = LocalDate.of(2027, 1, 15))
            assertEquals(1.50, flash2027.inputPerM, 0.0001)
            assertEquals(7.50, flash2027.outputPerM, 0.0001)
            assertEquals(0.15, flash2027.cachedInputPerM, 0.0001)
        }
    }

    @Test
    fun flagsBundledFallbackWhenFetchFailsAndPreservesDiskCacheOffline() {
        val dir = Files.createTempDirectory("pricing-cache-test").toFile()
        try {
            UsageCostEstimator.installCatalog(null)
            // No live or cached price -> must fall back and flag fallback = true + bundled date
            val fallbackEst = UsageCostEstimator.estimate(
                model = "gemini-4-hydrogen",
                role = "main",
                inputTokens = 1_000_000L,
                cachedTokens = 400_000L,
                outputTokens = 100_000L,
                provider = "vertex",
                asOf = LocalDate.of(2026, 11, 24),
            )
            assertTrue(fallbackEst.price.fallback)
            assertEquals("bundled_fallback", fallbackEst.price.source)
            assertEquals(BundledPricingFallback.AS_OF_DATE, fallbackEst.price.fetchedAt)
            assertEquals(1.68, fallbackEst.costUsd, 0.000001)

            // Persist a live snapshot to disk, reload fresh store, verify staleness and offline retention
            val cacheFile = File(dir, PricingCacheStore.FILE)
            val store = PricingCacheStore(cacheFile)
            val saved = PricingSnapshot(
                fetchedAt = "2026-11-24T12:00:00Z",
                source = "cloud_billing_api",
                entries = listOf(ModelPriceEntry("vertex", "gemini-4-hydrogen", "standard", "global", 1.50, 7.50, 0.075)),
            )
            store.save(saved)

            val reloaded = PricingCacheStore(cacheFile)
            val loaded = reloaded.loadIntoEstimator()
            assertNotNull(loaded)
            val nowSameDay = java.time.Instant.parse("2026-11-25T12:00:00Z").toEpochMilli()
            assertFalse("Within 7 days cache must not be stale", reloaded.isStale(nowSameDay))
            val afterWeek = java.time.Instant.parse("2026-12-03T12:00:00Z").toEpochMilli()
            assertTrue("After 7 days cache must be stale", reloaded.isStale(afterWeek))

            val liveFromDisk = UsageCostEstimator.resolvePrice("gemini-4-hydrogen", provider = "vertex")
            assertFalse(liveFromDisk.fallback)
            assertEquals("cloud_billing_api", liveFromDisk.source)
        } finally {
            dir.deleteRecursively()
        }
    }
}
