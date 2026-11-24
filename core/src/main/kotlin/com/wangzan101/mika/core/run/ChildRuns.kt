package com.wangzan101.mika.core.run

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.usage.RunUsageTally
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.run.record.UsageLogFormatter
import com.wangzan101.mika.core.safety.guard.sha256Hex12
import java.util.Collections
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ChildRunSink(val sink: RunSink) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ChildRunSink>
}

private class ChildRunRecorder : RunSink {
    private val list = Collections.synchronizedList(mutableListOf<RunEvent>())
    override fun event(e: RunEvent) { list += e }
    fun snapshot(): List<RunEvent> = synchronized(list) { list.toList() }
}

@Serializable enum class ChildPhase { Running, Completed, Failed, Interrupted, Unknown }

@Serializable data class ChildRecord(val id: String, val parentId: String, val phase: ChildPhase,
    val result: String? = null, val atMs: Long,
    /** 派下去的问题;界面上一行字说清这个子任务在干什么。 */
    val goal: String = "")

interface ChildRunStore {
    fun save(record: ChildRecord)
    fun get(id: String): ChildRecord?
    fun all(): List<ChildRecord>
}

class MemoryChildRunStore : ChildRunStore {
    private val records = linkedMapOf<String, ChildRecord>()
    @Synchronized override fun save(record: ChildRecord) { records[record.id] = record }
    @Synchronized override fun get(id: String) = records[id]
    @Synchronized override fun all() = records.values.toList()
}

data class ChildResult(val id: RunId, val phase: ChildPhase, val text: String)

fun interface ChildRunHost { suspend fun spawn(spec: RunSpec, format: String?): ChildResult }

/** 子 Run 先保存结论预览再交给父 Run；父 Run 消失时保留 Unknown 记录，不自动重跑。 */
class ChildAgentRunner(
    private val create: (RunSpec, ResourceScope) -> AgentLoop,
    private val store: ChildRunStore,
    private val ownerAlive: (RunId) -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
    private val parentSink: RunSink? = null,
    private val writeChildLog: (parentRunId: String, childId: String, content: String) -> Unit = { _, _, _ -> },
    private val versionCode: Int? = null,
    private val versionName: String? = null,
) : ChildRunHost {
    constructor(
        create: (RunSpec) -> AgentLoop,
        store: ChildRunStore,
        ownerAlive: (RunId) -> Boolean = { true },
        clock: () -> Long = System::currentTimeMillis,
        parentSink: RunSink? = null,
        writeChildLog: (parentRunId: String, childId: String, content: String) -> Unit = { _, _, _ -> },
        versionCode: Int? = null,
        versionName: String? = null,
    ) : this({ spec, _ -> create(spec) }, store, ownerAlive, clock, parentSink, writeChildLog, versionCode, versionName)

    private val active = HashMap<RunId, MutableSet<AgentLoop>>()

    override suspend fun spawn(spec: RunSpec, format: String?): ChildResult {
        val resources = ResourceScope()
        try {
            return runChild(spec, format, resources)
        } finally {
            resources.close()
        }
    }

    private suspend fun runChild(spec: RunSpec, format: String?, resources: ResourceScope): ChildResult {
        require(spec.parent != null && spec.provenance.origin == com.wangzan101.mika.core.run.RunOrigin.Child)
        val parent = spec.parent
        val parentTally = currentCoroutineContext()[RunUsageTally]
        val childTally = RunUsageTally(
            tokenBudget = spec.tokenBudget ?: parentTally?.tokenBudget,
            parentBudget = parentTally,
        )
        val recorder = ChildRunRecorder()
        val goal = spec.goal.take(200)
        val startedAt = clock()
        return withContext(childTally + ChildRunSink(recorder)) {
            val agent = create(spec, resources)
            synchronized(active) { active.getOrPut(parent) { mutableSetOf() } += agent }
            try {
                store.save(ChildRecord(spec.id.value, parent.value, ChildPhase.Running, atMs = clock(), goal = goal))
                val end = agent.run(spec, RunInbox())
                val raw = when (end) {
                    is TaskState.Succeeded -> end.answer
                    is TaskState.Failed -> end.reason
                    TaskState.Cancelled -> INTERRUPTED
                    else -> "no result"
                }
                // 失败不能记成完成:spawn_agent 的 ok 就是按 Completed 判的,以前 Failed 被写成 Completed,
                // 父模型会把失败的子任务当成成功结果汇总。
                val phase = when (end) {
                    is TaskState.Failed -> ChildPhase.Failed
                    TaskState.Cancelled -> ChildPhase.Interrupted
                    else -> ChildPhase.Completed
                }
                val checked = if (format == "json" && runCatching { Json.parseToJsonElement(raw) }.isFailure)
                    "$raw\n(the child run's JSON did not pass validation; the original text is kept)" else raw
                // 只截短落盘预览;交回父 Run 的原文由 spawn_agent 按本次请求的额度截断并报告原长。
                val preview = checked.take(MAX_RESULT_CHARS)
                store.save(ChildRecord(spec.id.value, parent.value, phase, preview, clock(), goal = goal))
                val outcome = when (end) {
                    is TaskState.Succeeded -> if (end.doubtful) "unverified" else "succeeded"
                    is TaskState.Failed -> if (end.kind == FailKind.GaveUp) "gaveup" else "failed"
                    TaskState.Cancelled -> "cancelled"
                    else -> "error"
                }
                recordChildUsage(spec, parent, startedAt, outcome, recorder, childTally)
                if (!ownerAlive(parent)) {
                    store.save(ChildRecord(spec.id.value, parent.value, ChildPhase.Unknown, preview, clock(), goal = goal))
                    return@withContext ChildResult(spec.id, ChildPhase.Unknown, checked)
                }
                ChildResult(spec.id, phase, checked)
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    store.save(ChildRecord(spec.id.value, parent.value, ChildPhase.Interrupted, INTERRUPTED, clock(), goal = goal))
                    recordChildUsage(spec, parent, startedAt, "cancelled", recorder, childTally)
                }
                throw e
            } finally {
                synchronized(active) { active[parent]?.remove(agent); if (active[parent]?.isEmpty() == true) active.remove(parent) }
            }
        }
    }

    private fun recordChildUsage(
        spec: RunSpec,
        parent: RunId,
        startedAt: Long,
        outcome: String,
        recorder: ChildRunRecorder,
        childTally: RunUsageTally,
    ) {
        runCatching {
            val endedAt = clock()
            recorder.event(childTally.event(endedAt))
            val childEvents = recorder.snapshot()
            val childLog = UsageLogFormatter.formatEvents(childEvents, spec.id.value, versionCode, versionName)
            runCatching { writeChildLog(parent.value, spec.id.value, childLog) }
            val summary = UsageLogFormatter.buildSummary(childEvents, spec.id.value, versionCode, versionName)
            val mainTotals = summary.totalsByRole["main"]
            val lightTotals = summary.totalsByRole["light"]
            val summaryEvent = RunEvent.SubAgentSummary(
                atMs = endedAt,
                childId = spec.id.value,
                parentStep = spec.parentStep ?: 0,
                goal = Redactor.text(spec.goal).take(120),
                goalHash = sha256Hex12(spec.goal),
                steps = summary.steps,
                calls = summary.totalCalls,
                inputTokens = summary.totalInputTokens,
                outputTokens = summary.totalOutputTokens,
                cachedTokens = summary.totalCachedTokens,
                costUsd = summary.totalCostUsd,
                mainCalls = mainTotals?.calls ?: 0,
                lightCalls = lightTotals?.calls ?: 0,
                lightAcceptedCalls = summary.lightAcceptedCalls,
                lightRejectedCalls = summary.lightRejectedCalls,
                mainInputTokens = mainTotals?.inputTokens ?: 0L,
                mainOutputTokens = mainTotals?.outputTokens ?: 0L,
                mainCachedTokens = mainTotals?.cachedTokens ?: 0L,
                mainCostUsd = mainTotals?.costUsd ?: 0.0,
                lightInputTokens = lightTotals?.inputTokens ?: 0L,
                lightOutputTokens = lightTotals?.outputTokens ?: 0L,
                lightCachedTokens = lightTotals?.cachedTokens ?: 0L,
                lightCostUsd = lightTotals?.costUsd ?: 0.0,
                durationMs = (endedAt - startedAt).coerceAtLeast(0L),
                outcome = outcome,
                logFile = "mika-usage-child-${spec.id.value}.jsonl",
                unreportedCalls = summary.unreportedCalls,
                unreportedReasons = summary.unreportedReasons,
            )
            parentSink?.event(summaryEvent)
        }
    }

    fun cancelAll(parent: RunId) { synchronized(active) { active[parent]?.toList() }.orEmpty().forEach(AgentLoop::cancel) }

    companion object {
        /** 子任务记录的落盘预览上限,不是交回父任务的结果上限。 */
        const val MAX_RESULT_CHARS = 4000
        private const val INTERRUPTED = "interrupted"
    }
}
