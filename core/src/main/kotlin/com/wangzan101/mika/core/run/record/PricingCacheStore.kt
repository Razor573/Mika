package com.wangzan101.mika.core.run.record

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.time.Instant

/**
 * Local JSON cache for fetched Google pricing tables with weekly staleness check and offline safety.
 */
class PricingCacheStore(
    private val file: File,
    private val maxAgeMs: Long = WEEKLY_MS,
) {
    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var cached: PricingSnapshot? = readFromDisk()

    fun current(): PricingSnapshot? = cached

    fun loadIntoEstimator(): PricingSnapshot? {
        val snap = cached
        if (snap != null) UsageCostEstimator.installCatalog(snap)
        return snap
    }

    fun isStale(nowMs: Long = System.currentTimeMillis()): Boolean {
        val snap = cached ?: return true
        val fetchedMs = runCatching { Instant.parse(snap.fetchedAt).toEpochMilli() }.getOrNull() ?: return true
        return nowMs - fetchedMs >= maxAgeMs
    }

    fun save(snapshot: PricingSnapshot) {
        synchronized(lock) {
            cached = snapshot
            UsageCostEstimator.installCatalog(snapshot)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(PricingSnapshot.serializer(), snapshot))
            if (!tmp.renameTo(file)) {
                file.writeText(json.encodeToString(PricingSnapshot.serializer(), snapshot))
                tmp.delete()
            }
        }
    }

    fun refreshIfStale(
        http: OkHttpClient = OkHttpClient(),
        billingBearerToken: String? = null,
        billingApiKey: String? = null,
        billingProjectId: String? = null,
        nowMs: Long = System.currentTimeMillis(),
        force: Boolean = false,
    ): PricingSnapshot? {
        if (!force && !isStale(nowMs)) return loadIntoEstimator()
        val fetched = LivePricingFetcher.fetch(
            http = http,
            billingBearerToken = billingBearerToken,
            billingApiKey = billingApiKey,
            billingProjectId = billingProjectId,
            nowIso = Instant.ofEpochMilli(nowMs).toString(),
        )
        if (fetched != null) {
            save(fetched)
            return fetched
        }
        return loadIntoEstimator()
    }

    private fun readFromDisk(): PricingSnapshot? {
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(PricingSnapshot.serializer(), file.readText())
        }.getOrNull()
    }

    companion object {
        const val FILE = "pricing-cache-v1.json"
        const val WEEKLY_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
