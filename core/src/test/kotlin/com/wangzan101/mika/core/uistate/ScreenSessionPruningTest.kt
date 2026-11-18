package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSessionPruningTest {
    private class StubScreen(
        private var snap: ScreenSnapshot,
        private val pngBytes: ByteArray? = byteArrayOf(1, 2, 3),
    ) : ScreenPort {
        override suspend fun snapshot() = snap
        override suspend fun peek() = snap
        override suspend fun screenshotPng() = pngBytes
        override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Ok()
        override suspend fun tapXY(x: Int, y: Int) = ActionResult.Ok()
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = ActionResult.Ok()
        override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Ok()
        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Ok()
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Ok()
        override suspend fun back() = ActionResult.Ok()
        override suspend fun pressKey(keyCode: Int) = ActionResult.Ok()
        override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Ok()
        override suspend fun waitStable(timeoutMs: Long) = true
        override suspend fun listApps(): List<Pair<String, String>> = emptyList()
    }

    private fun denseCatalogSnap(): ScreenSnapshot {
        val rows = (1..270).map { i ->
            val top = ((i - 1) * 3) % 900
            val bounds = listOf(20, top, 960, top + 60)
            val price = UiTreeNode("TextView", listOf(40, top + 5, 300, top + 30), text = "$$i.99")
            val addBtn = UiTreeNode("Button", listOf(500, top + 5, 920, top + 50), ref = "@e$i", text = "Add to Cart $i", clickable = true)
            val row = UiTreeNode("LinearLayout", bounds, children = listOf(price, addBtn))
            UiTreeNode("FrameLayout", bounds, children = listOf(
                UiTreeNode("LinearLayout", bounds, children = listOf(
                    UiTreeNode("ViewGroup", bounds, children = listOf(
                        UiTreeNode("FrameLayout", bounds, children = listOf(
                            UiTreeNode("LinearLayout", bounds, children = listOf(
                                UiTreeNode("ViewGroup", bounds, children = listOf(row))
                            ))
                        ))
                    ))
                ))
            ))
        }
        val offscreen = UiTreeNode("TextView", listOf(1050, 100, 1200, 160), text = "OffscreenJunk")
        val zeroArea = UiTreeNode("View", listOf(10, 10, 10, 50), text = "ZeroAreaJunk")
        val root = UiTreeNode("RecyclerView", listOf(0, 0, 999, 999), scrollable = true, children = rows + offscreen + zeroArea)
        val window = UiWindowTree(1, "application", 0, "Amazon", "com.amazon.mShop", active = true, focused = true, nodes = listOf(root))
        val elements = (1..270).map { i ->
            val top = ((i - 1) * 3) % 900
            UiElement("@e$i", "Button", "Add to Cart $i", 710, top + 28, listOf(500, top + 5, 920, top + 50), null, clickable = true, editable = false, scrollable = false)
        }
        return ScreenSnapshot("snap-dense", "com.amazon.mShop", elements, 1080, 2400, windows = listOf(window))
    }

    @Test fun compactModeShrinksDenseCatalogAndCapsPhoneElementsAt250() = runTest {
        val snap = denseCatalogSnap()
        val compact = toolDispatcher(StubScreen(snap), compactUiTree = true)
            .executeRead(Part.ToolCall("c1", "phone_observe", "{}")).content
        val raw = toolDispatcher(StubScreen(snap), compactUiTree = false)
            .executeRead(Part.ToolCall("c2", "phone_observe", "{}")).content

        assertTrue("Expected >=40% reduction: compact=${compact.toString().length}, raw=${raw.toString().length}",
            compact.toString().length <= raw.toString().length * 0.6)
        assertEquals(250, compact["elements"]!!.jsonArray.size)
        assertTrue(compact["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(20, compact["omitted"]!!.jsonPrimitive.int)
        assertFalse(compact.containsKey("screenMap"))
        assertFalse(compact.containsKey("visibleText"))
        val treeText = compact["tree"].toString()
        for (i in listOf(1, 135, 270)) {
            assertTrue(treeText.contains("\"@e$i\""))
            assertTrue(treeText.contains("$$i.99"))
            assertTrue(treeText.contains("Add to Cart $i"))
        }
    }

    @Test fun fullRawTreeDumpOverridesCompactAnd250Cap() = runTest {
        val content = toolDispatcher(StubScreen(denseCatalogSnap()), compactUiTree = true, fullRawTreeDump = true)
            .executeRead(Part.ToolCall("c3", "phone_observe", "{}")).content

        assertEquals(270, content["elements"]!!.jsonArray.size)
        assertFalse(content["truncated"]!!.jsonPrimitive.boolean)
        assertFalse(content.containsKey("omitted"))
        assertTrue(content["tree"].toString().contains("FrameLayout"))
    }

    @Test fun sparsePrunedTreeFallsBackToRawWindowsAndTriggersAdaptiveVision() = runTest {
        val leaf = UiTreeNode("TextView", listOf(40, 100, 500, 160), ref = "@e1", text = "Continue", clickable = true)
        val wrapped = UiTreeNode("FrameLayout", listOf(0, 0, 999, 999), children = listOf(
            UiTreeNode("FrameLayout", listOf(0, 0, 999, 999), children = listOf(
                UiTreeNode("FrameLayout", listOf(0, 0, 999, 999), children = listOf(leaf))
            ))
        ))
        val window = UiWindowTree(1, "application", 0, "Sparse", "com.example.sparse", active = true, focused = true, nodes = listOf(wrapped))
        val elements = listOf(UiElement("@e1", "TextView", "Continue", 270, 130, listOf(40, 100, 500, 160), null, clickable = true, editable = false, scrollable = false))
        val snap = ScreenSnapshot("snap-sparse", "com.example.sparse", elements, 1080, 2400, windows = listOf(window))

        val result = toolDispatcher(StubScreen(snap), visionEnabled = true, adaptiveVision = true, compactUiTree = true)
            .executeRead(Part.ToolCall("c4", "phone_observe", "{}"))

        assertTrue(result.content["tree"].toString().contains("FrameLayout"))
        assertNotNull(result.content["imageReason"])
        assertNotNull(result.image)
    }

    @Test fun phoneQueryRootRefExpandsUnprunedSubtree() = runTest {
        val msgBody = UiTreeNode("TextView", listOf(40, 300, 800, 380), text = "Message body")
        val collapsedWrapper = UiTreeNode("FrameLayout", listOf(40, 300, 800, 380), children = listOf(msgBody))
        val container = UiTreeNode("LinearLayout", listOf(20, 280, 900, 420), ref = "@e12", text = "Alice", clickable = true, children = listOf(collapsedWrapper))
        val header = UiTreeNode("TextView", listOf(20, 20, 500, 80), ref = "@e1", text = "WhatsApp")
        val status = UiTreeNode("TextView", listOf(20, 120, 500, 180), ref = "@e2", text = "Online")
        val root = UiTreeNode("LinearLayout", listOf(0, 0, 999, 999), children = listOf(header, status, container))
        val window = UiWindowTree(1, "application", 0, "WhatsApp", "com.whatsapp", active = true, focused = true, nodes = listOf(root))
        val elements = listOf(
            UiElement("@e1", "TextView", "WhatsApp", 260, 50, listOf(20, 20, 500, 80), null, clickable = false, editable = false, scrollable = false),
            UiElement("@e2", "TextView", "Online", 260, 150, listOf(20, 120, 500, 180), null, clickable = false, editable = false, scrollable = false),
            UiElement("@e12", "LinearLayout", "Alice", 460, 350, listOf(20, 280, 900, 420), null, clickable = true, editable = false, scrollable = false),
        )
        val snap = ScreenSnapshot("snap-wa", "com.whatsapp", elements, 1080, 2400, windows = listOf(window))

        val dispatcher = toolDispatcher(StubScreen(snap), compactUiTree = true)
        val observed = dispatcher.executeRead(Part.ToolCall("c5", "phone_observe", "{}"))
        assertFalse(observed.content["tree"].toString().contains("FrameLayout"))

        val queried = dispatcher.executeRead(Part.ToolCall("c6", "phone_query", """{"rootRef":"@e12","full":true}"""))
        val subtreeJson = queried.content["tree"].toString()
        assertTrue(subtreeJson.contains("FrameLayout"))
        assertTrue(subtreeJson.contains("Message body"))
    }
}
