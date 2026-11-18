package com.wangzan101.mika.shortcuts

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.widget.Toast
import com.wangzan101.mika.R
import com.wangzan101.mika.settings.RecommendedTask
import com.wangzan101.mika.ui.MainActivity
import com.wangzan101.mika.workflow.WorkflowTemplate
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Pure-Kotlin pinned shortcut metadata builder, label truncator, ID sanitizer, and token verifier.
 */
object PinnedShortcutSpec {
    const val MAX_SHORT_LABEL = 25
    const val MAX_LONG_LABEL = 60
    const val MAX_ID_LENGTH = 100
    const val PREFIX_SAVED_TASK = "saved_task_"
    const val PREFIX_WORKFLOW = "workflow_"

    enum class Kind { SavedTask, Workflow }

    data class Spec(
        val id: String,
        val shortLabel: String,
        val longLabel: String,
        val targetId: String,
        val kind: Kind,
    )

    fun savedTask(task: RecommendedTask, fallbackTitle: String = "Saved task"): Spec =
        savedTask(task.id, task.title, fallbackTitle)

    fun savedTask(rawId: String, title: String, fallbackTitle: String = "Saved task"): Spec = Spec(
        id = PREFIX_SAVED_TASK + sanitizeId(rawId),
        shortLabel = truncateLabel(title, MAX_SHORT_LABEL, fallbackTitle),
        longLabel = truncateLabel(title, MAX_LONG_LABEL, fallbackTitle),
        targetId = rawId,
        kind = Kind.SavedTask,
    )

    fun workflow(template: WorkflowTemplate, fallbackTitle: String = "Multi-step task"): Spec =
        workflow(template.id, template.title, fallbackTitle)

    fun workflow(rawId: String, title: String, fallbackTitle: String = "Multi-step task"): Spec = Spec(
        id = PREFIX_WORKFLOW + sanitizeId(rawId),
        shortLabel = truncateLabel(title, MAX_SHORT_LABEL, fallbackTitle),
        longLabel = truncateLabel(title, MAX_LONG_LABEL, fallbackTitle),
        targetId = rawId,
        kind = Kind.Workflow,
    )

    fun sanitizeId(raw: String): String {
        val cleaned = buildString(raw.length.coerceAtMost(MAX_ID_LENGTH)) {
            for (ch in raw.trim()) {
                if (length >= MAX_ID_LENGTH) break
                append(
                    if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '_' || ch == '-') {
                        ch
                    } else {
                        '_'
                    },
                )
            }
        }.trim('_')
        return cleaned.ifEmpty { "item" }
    }

    fun truncateLabel(raw: String, maxLength: Int, fallback: String = "Mika"): String {
        val normalized = raw.replace(Regex("\\s+"), " ").trim().ifEmpty { fallback }
        if (normalized.length <= maxLength) return normalized
        if (maxLength <= 1) return normalized.take(maxLength.coerceAtLeast(0))
        val limit = maxLength - 1
        var end = 0
        while (end < normalized.length) {
            val next = end + Character.charCount(normalized.codePointAt(end))
            if (next > limit) break
            end = next
        }
        return normalized.substring(0, end).trimEnd() + "…"
    }

    fun verifyToken(expected: String?, actual: String?): Boolean {
        if (expected.isNullOrBlank() || actual.isNullOrBlank()) return false
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            actual.toByteArray(StandardCharsets.UTF_8),
        )
    }
}

/**
 * Requests launcher home-screen shortcuts for saved tasks and multi-step workflows via
 * platform [ShortcutManager], authenticated with a per-install random UUID token.
 */
object PinnedTaskShortcuts {
    const val EXTRA_SHORTCUT_TASK_ID = "com.wangzan101.mika.extra.SHORTCUT_TASK_ID"
    const val EXTRA_SHORTCUT_WORKFLOW_ID = "com.wangzan101.mika.extra.SHORTCUT_WORKFLOW_ID"
    const val EXTRA_SHORTCUT_TOKEN = "com.wangzan101.mika.extra.SHORTCUT_TOKEN"

    private const val PREFS_NAME = "mika_pinned_shortcuts"
    private const val KEY_TOKEN = "shortcut_token"
    private val tokenLock = Any()

    sealed interface PinResult {
        data object Requested : PinResult
        data object Unsupported : PinResult
    }

    fun isValidToken(context: Context, candidate: String?): Boolean {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_TOKEN, null)
        return PinnedShortcutSpec.verifyToken(stored, candidate)
    }

    fun pinSavedTask(context: Context, task: RecommendedTask): PinResult {
        if (task.id.isBlank()) return unsupported(context)
        val spec = PinnedShortcutSpec.savedTask(task, context.getString(R.string.recommendations_title))
        val intent = shortcutIntent(context).putExtra(EXTRA_SHORTCUT_TASK_ID, task.id)
        return requestPin(context, spec, intent)
    }

    fun pinWorkflow(context: Context, template: WorkflowTemplate): PinResult {
        if (template.id.isBlank()) return unsupported(context)
        val spec = PinnedShortcutSpec.workflow(template, context.getString(R.string.workflows_title))
        val intent = shortcutIntent(context).putExtra(EXTRA_SHORTCUT_WORKFLOW_ID, template.id)
        return requestPin(context, spec, intent)
    }

    private fun shortcutIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_SHORTCUT_TOKEN, ensureToken(context))

    private fun ensureToken(context: Context): String = synchronized(tokenLock) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_TOKEN, generated).commit()
        generated
    }

    private fun requestPin(context: Context, spec: PinnedShortcutSpec.Spec, intent: Intent): PinResult {
        val manager = context.getSystemService(ShortcutManager::class.java)
        if (manager == null || !manager.isRequestPinShortcutSupported) {
            return unsupported(context)
        }
        val info = ShortcutInfo.Builder(context, spec.id)
            .setShortLabel(spec.shortLabel)
            .setLongLabel(spec.longLabel)
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
        return if (manager.requestPinShortcut(info, null)) {
            Toast.makeText(context, R.string.shortcut_pin_requested, Toast.LENGTH_SHORT).show()
            PinResult.Requested
        } else {
            unsupported(context)
        }
    }

    private fun unsupported(context: Context): PinResult {
        Toast.makeText(context, R.string.shortcut_pin_unsupported, Toast.LENGTH_SHORT).show()
        return PinResult.Unsupported
    }
}
