package com.wangzan101.mika.shortcuts

import com.wangzan101.mika.settings.RecommendedTask
import com.wangzan101.mika.workflow.WorkflowStep
import com.wangzan101.mika.workflow.WorkflowTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedTaskShortcutsTest {
    @Test
    fun buildsSavedTaskAndWorkflowSpecsWithExpectedPrefixes() {
        val task = RecommendedTask(id = "task-42", title = "Morning Weather Briefing", prompt = "Check weather")
        val taskSpec = PinnedShortcutSpec.savedTask(task)
        assertEquals("saved_task_task-42", taskSpec.id)
        assertEquals("Morning Weather Briefing", taskSpec.shortLabel)
        assertEquals("Morning Weather Briefing", taskSpec.longLabel)
        assertEquals("task-42", taskSpec.targetId)
        assertEquals(PinnedShortcutSpec.Kind.SavedTask, taskSpec.kind)

        val workflow = WorkflowTemplate(
            id = "wf-99",
            revision = 1,
            title = "Daily Digest",
            steps = listOf(
                WorkflowStep(title = "Collect", instruction = "Read inbox"),
                WorkflowStep(title = "Summarize", instruction = "Write digest"),
            ),
        )
        val workflowSpec = PinnedShortcutSpec.workflow(workflow)
        assertEquals("workflow_wf-99", workflowSpec.id)
        assertEquals("Daily Digest", workflowSpec.shortLabel)
        assertEquals("Daily Digest", workflowSpec.longLabel)
        assertEquals("wf-99", workflowSpec.targetId)
        assertEquals(PinnedShortcutSpec.Kind.Workflow, workflowSpec.kind)
    }

    @Test
    fun truncatesShortAndLongLabelsWithinBoundsAndNormalizesWhitespace() {
        val longTitle = "  Summarize   all\nunread   group   messages   and   draft   replies   for   review   today  "
        val spec = PinnedShortcutSpec.savedTask("id-1", longTitle)

        assertTrue(spec.shortLabel.length <= PinnedShortcutSpec.MAX_SHORT_LABEL)
        assertTrue(spec.shortLabel.endsWith("…"))
        assertFalse(spec.shortLabel.contains("\n"))

        assertTrue(spec.longLabel.length <= PinnedShortcutSpec.MAX_LONG_LABEL)
        assertTrue(spec.longLabel.endsWith("…"))
        assertFalse(spec.longLabel.contains("  "))

        // Surrogate-pair emoji truncation never splits a UTF-16 pair.
        val emojiTitle = "😀".repeat(20)
        val truncatedEmoji = PinnedShortcutSpec.truncateLabel(emojiTitle, PinnedShortcutSpec.MAX_SHORT_LABEL)
        assertTrue(truncatedEmoji.length <= PinnedShortcutSpec.MAX_SHORT_LABEL)
        assertTrue(truncatedEmoji.endsWith("…"))
        val withoutEllipsis = truncatedEmoji.dropLast(1)
        assertEquals(0, withoutEllipsis.length % 2)

        // Blank title uses fallback.
        val blankSpec = PinnedShortcutSpec.savedTask("id-2", "   \n  ", fallbackTitle = "Saved task")
        assertEquals("Saved task", blankSpec.shortLabel)
        assertEquals("Saved task", blankSpec.longLabel)
    }

    @Test
    fun sanitizesIdsAndCapsLength() {
        assertEquals("abc_123-def.ghi", PinnedShortcutSpec.sanitizeId("  abc/123-def.ghi  "))
        assertEquals("item", PinnedShortcutSpec.sanitizeId("   ///   "))
        val oversized = "a".repeat(200)
        val sanitized = PinnedShortcutSpec.sanitizeId(oversized)
        assertEquals(PinnedShortcutSpec.MAX_ID_LENGTH, sanitized.length)
        assertEquals("workflow_$sanitized", PinnedShortcutSpec.workflow(oversized, "Title").id)
    }

    @Test
    fun verifiesValidTokenAndRejectsForgedOrMissingTokens() {
        val token = "6f1c8f92-2a4f-4d1c-8c1e-9a8d7e6b5c4a"
        assertTrue(PinnedShortcutSpec.verifyToken(token, token))

        assertFalse(PinnedShortcutSpec.verifyToken(token, null))
        assertFalse(PinnedShortcutSpec.verifyToken(token, ""))
        assertFalse(PinnedShortcutSpec.verifyToken(token, "   "))
        assertFalse(PinnedShortcutSpec.verifyToken(token, "6f1c8f92-2a4f-4d1c-8c1e-9a8d7e6b5c4b"))
        assertFalse(PinnedShortcutSpec.verifyToken(token, token.dropLast(1)))
        assertFalse(PinnedShortcutSpec.verifyToken(null, token))
        assertFalse(PinnedShortcutSpec.verifyToken("", ""))
    }
}
