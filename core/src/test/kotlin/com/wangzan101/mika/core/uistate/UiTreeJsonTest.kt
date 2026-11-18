package com.wangzan101.mika.core.uistate

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTreeJsonTest {
    private fun leaf(ref: String, text: String) = UiTreeNode(
        role = "Button", bounds = listOf(0, 0, 500, 100), ref = ref, text = text,
        clickable = true, checkable = true, checked = false,
    )

    private fun screen(second: String = "查看", extra: Boolean = false): ScreenSnapshot {
        val children = listOf(leaf("@e1", "午餐"), leaf("@e2", second)) +
            if (extra) listOf(leaf("@e3", "新增")) else emptyList()
        val root = UiTreeNode("RecyclerView", listOf(0, 0, 999, 999), scrollable = true, children = children)
        val window = UiWindowTree(7, "application", 2, "订单", "com.example", true, true, listOf(root))
        val elements = children.mapIndexed { i, n ->
            UiElement(n.ref!!, "Button", n.text!!, 100, 100 + i * 100, n.bounds, null, true, false, false)
        }
        return ScreenSnapshot("port", "com.example", elements, 1080, 2400, windows = listOf(window))
    }

    @Test fun completeHierarchyAndStatesAreVisible() {
        val out = UiTreeJson.windows(screen()).single().jsonObject
        assertEquals("订单", out["title"]!!.jsonPrimitive.content)
        val root = out["nodes"]!!.jsonArray.single().jsonObject
        assertEquals("true", root["scrollable"]!!.jsonPrimitive.content)
        val children = root["children"]!!.jsonArray
        assertEquals(2, children.size)
        assertEquals("@e2", children[1].jsonObject["ref"]!!.jsonPrimitive.content)
        assertEquals("false", children[1].jsonObject["checked"]!!.jsonPrimitive.content)
    }

    @Test fun changedChildDoesNotRepeatUnchangedSibling() {
        val before = screen()
        val after = screen(second = "已查看", extra = true)
        val change = UiTreeJson.changes(before, after)
        val updated = change["updated"]!!.jsonArray
        assertEquals(listOf("w7/0/1", "w7/0/2"), updated.map { it.jsonObject["path"]!!.jsonPrimitive.content })
        assertEquals("已查看", updated[0].jsonObject["node"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("新增", updated[1].jsonObject["node"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(change["removed"]!!.jsonArray.isEmpty())
    }

    @Test fun actionResultCarriesTreeChangeWithStablePath() {
        val stabilizer = RefStabilizer("p")
        val before = stabilizer.next(screen())
        val next = screen()
        val changedWindows = next.windows.map { window ->
            window.copy(nodes = window.nodes.map { root ->
                root.copy(children = root.children.mapIndexed { i, child ->
                    if (i == 1) child.copy(checked = true) else child
                })
            })
        }
        val after = stabilizer.next(next.copy(windows = changedWindows))
        val result = InterfaceUpdate.between(before, after, baseVisible = true, maxElements = Int.MAX_VALUE)
        assertEquals("false", result["full"]!!.jsonPrimitive.content)
        val updated = result["treeChanges"]!!.jsonObject["updated"]!!.jsonArray.single().jsonObject
        assertEquals("w7/0/1", updated["path"]!!.jsonPrimitive.content)
        assertEquals("true", updated["node"]!!.jsonObject["checked"]!!.jsonPrimitive.content)
    }

    @Test fun stableRefsAlsoMapIntoTree() {
        val port = screen().copy(elements = screen().elements.mapIndexed { i, e -> e.copy(ref = "@e${i + 8}") },
            windows = screen().windows.map { w -> w.copy(nodes = w.nodes.map { root ->
                root.copy(children = root.children.mapIndexed { i, n -> n.copy(ref = "@e${i + 8}") })
            }) })
        val model = RefStabilizer("p").next(port)
        val output = UiTreeJson.windows(model.screen).single().jsonObject["nodes"]!!.jsonArray.single().jsonObject["children"]!!.jsonArray
        assertEquals(model.elements.map { it.ref }, output.map { it.jsonObject["ref"]!!.jsonPrimitive.content })
        assertFalse(output[0].jsonObject.containsKey("children"))
    }

    @Test fun prunedWindowsCollapsesWrappersDropsOffscreenAndShrinksDenseTree() {
        val items = (1..12).map { i ->
            val ref = "@e$i"
            // Deeply nested single-child FrameLayout -> LinearLayout -> ViewGroup wrappers
            val title = UiTreeNode("TextView", listOf(40, i * 70, 600, i * 70 + 30), text = "Item $i")
            val price = UiTreeNode("TextView", listOf(620, i * 70, 900, i * 70 + 30), text = "$$i.99")
            val row = UiTreeNode("LinearLayout", listOf(20, i * 70, 960, i * 70 + 60), ref = ref, clickable = true, children = listOf(title, price))
            UiTreeNode("FrameLayout", listOf(0, i * 70, 1000, i * 70 + 60), children = listOf(
                UiTreeNode("LinearLayout", listOf(0, i * 70, 1000, i * 70 + 60), children = listOf(
                    UiTreeNode("ViewGroup", listOf(0, i * 70, 1000, i * 70 + 60), children = listOf(row))
                ))
            ))
        }
        val offscreen = UiTreeNode("TextView", listOf(1050, 100, 1200, 150), text = "Offscreen")
        val zeroArea = UiTreeNode("View", listOf(10, 10, 10, 50), text = "ZeroWidth")
        val root = UiTreeNode("RecyclerView", listOf(0, 0, 999, 999), scrollable = true, children = items + offscreen + zeroArea)
        val snap = screen().copy(windows = listOf(UiWindowTree(7, "application", 0, "Shop", "com.shop", active = true, focused = true, nodes = listOf(root))))

        val rawJson = UiTreeJson.windows(snap).toString()
        val pruned = UiTreeJson.prunedWindows(snap)
        val prunedJson = pruned.windows.toString()

        assertTrue("Expected >=40% reduction: raw=${rawJson.length}, pruned=${prunedJson.length}", prunedJson.length <= rawJson.length * 0.6)
        assertFalse(prunedJson.contains("Offscreen"))
        assertFalse(prunedJson.contains("ZeroWidth"))
        for (i in 1..12) {
            assertTrue("Missing ref @e$i", prunedJson.contains("\"@e$i\""))
            assertTrue("Missing price for item $i", prunedJson.contains("$$i.99"))
        }
        assertTrue(pruned.meaningfulCount >= 12)
    }

    @Test fun subtreeReturnsFullUnprunedDescendantsForRootRef() {
        val inner = UiTreeNode("TextView", listOf(10, 10, 100, 40), text = "Detail")
        val wrapper = UiTreeNode("FrameLayout", listOf(10, 10, 100, 40), children = listOf(inner))
        val container = UiTreeNode("LinearLayout", listOf(10, 10, 200, 80), ref = "@e12", clickable = true, children = listOf(wrapper))
        val snap = screen().copy(windows = listOf(UiWindowTree(7, "application", 0, "Shop", "com.shop", active = true, focused = true, nodes = listOf(container))))

        val subtree = UiTreeJson.subtree(snap, "@e12")!!
        assertEquals("@e12", subtree["ref"]!!.jsonPrimitive.content)
        val child = subtree["children"]!!.jsonArray.single().jsonObject
        assertEquals("FrameLayout", child["role"]!!.jsonPrimitive.content)
        assertNull(UiTreeJson.subtree(snap, "@e999"))
    }

    @Test fun prunedWindowsReportsFewerThanThreeMeaningfulNodes() {
        val single = UiTreeNode("TextView", listOf(10, 10, 100, 40), text = "Only one")
        val snap = screen().copy(windows = listOf(UiWindowTree(7, "application", 0, "App", "com.app", active = true, focused = true, nodes = listOf(single))))
        val pruned = UiTreeJson.prunedWindows(snap)
        assertTrue(pruned.meaningfulCount < 3)
    }
}
