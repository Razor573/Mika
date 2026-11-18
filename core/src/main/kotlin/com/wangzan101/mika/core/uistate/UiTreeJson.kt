package com.wangzan101.mika.core.uistate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 给模型看的手机窗口层级;节点完整保留,只省去空字段和默认状态。 */
object UiTreeJson {
    private val LAYOUT_WRAPPERS = setOf("FrameLayout", "LinearLayout", "ViewGroup")
    private const val VIEWPORT_MIN = 0
    private const val VIEWPORT_MAX = 1000

    data class PrunedResult(
        val windows: JsonArray,
        val meaningfulCount: Int,
    )

    fun windows(s: ScreenSnapshot): JsonArray = buildJsonArray {
        for (w in s.windows) add(buildJsonObject {
            put("id", w.id)
            put("type", w.type)
            put("layer", w.layer)
            w.title?.let { put("title", it) }
            w.packageName?.let { put("packageName", it) }
            if (w.active) put("active", true)
            if (w.focused) put("focused", true)
            put("nodes", JsonArray(w.nodes.map(::node)))
        })
    }

    /**
     * 精简骨架树：
     * 1. 丢弃不可见 / 零面积 (`width <= 0` 或 `height <= 0`) / 视口外 (`0..999` 之外) 的节点。
     * 2. 折叠单子节点的非交互布局容器 (`FrameLayout`, `LinearLayout`, `ViewGroup`) 并上提子节点。
     * 3. 保留多子节点容器或有标签的父容器，确保价格/状态与所属条目不丢失。
     */
    fun prunedWindows(s: ScreenSnapshot): PrunedResult {
        var meaningful = 0
        val arr = buildJsonArray {
            for (w in s.windows) {
                val prunedNodes = w.nodes.flatMap { pruneNode(it) }
                meaningful += prunedNodes.sumOf { countMeaningful(it) }
                add(buildJsonObject {
                    put("id", w.id)
                    put("type", w.type)
                    put("layer", w.layer)
                    w.title?.let { put("title", it) }
                    w.packageName?.let { put("packageName", it) }
                    if (w.active) put("active", true)
                    if (w.focused) put("focused", true)
                    put("nodes", JsonArray(prunedNodes.map(::node)))
                })
            }
        }
        return PrunedResult(arr, meaningful)
    }

    /** 返回指定 [rootRef] 节点的完整未精简子树；找不到返回 null。 */
    fun subtree(s: ScreenSnapshot, rootRef: String): JsonObject? {
        fun find(nodes: List<UiTreeNode>): UiTreeNode? {
            for (n in nodes) {
                if (n.ref == rootRef) return n
                find(n.children)?.let { return it }
            }
            return null
        }
        for (w in s.windows) {
            find(w.nodes)?.let { return node(it) }
        }
        return null
    }

    private fun pruneNode(n: UiTreeNode): List<UiTreeNode> {
        if (isZeroOrOffscreen(n.bounds)) return emptyList()
        val prunedChildren = n.children.flatMap { pruneNode(it) }
        if (prunedChildren.size == 1 && isEmptyWrapper(n)) {
            return prunedChildren
        }
        return listOf(n.copy(children = prunedChildren))
    }

    private fun isZeroOrOffscreen(bounds: List<Int>): Boolean {
        if (bounds.size < 4) return true
        val l = bounds[0]
        val t = bounds[1]
        val r = bounds[2]
        val b = bounds[3]
        if (r - l <= 0 || b - t <= 0) return true
        if (r <= VIEWPORT_MIN || b <= VIEWPORT_MIN || l >= VIEWPORT_MAX || t >= VIEWPORT_MAX) return true
        return false
    }

    private fun isEmptyWrapper(n: UiTreeNode): Boolean {
        val simpleRole = n.role.substringAfterLast('.')
        if (simpleRole !in LAYOUT_WRAPPERS) return false
        return n.ref == null &&
            n.text.isNullOrBlank() &&
            n.description.isNullOrBlank() &&
            n.hintText.isNullOrBlank() &&
            n.resourceId.isNullOrBlank() &&
            !n.clickable && !n.editable && !n.scrollable &&
            !n.checkable && !n.selected && !n.focused
    }

    private fun isMeaningful(n: UiTreeNode): Boolean =
        n.ref != null ||
            !n.text.isNullOrBlank() ||
            !n.description.isNullOrBlank() ||
            !n.hintText.isNullOrBlank() ||
            n.clickable || n.editable || n.scrollable ||
            n.checkable || n.selected || n.focused

    private fun countMeaningful(n: UiTreeNode): Int =
        (if (isMeaningful(n)) 1 else 0) + n.children.sumOf { countMeaningful(it) }

    private fun node(n: UiTreeNode): JsonObject = JsonObject(shallow(n) +
        (if (n.children.isEmpty()) emptyMap() else mapOf("children" to JsonArray(n.children.map(::node)))))

    private fun shallow(n: UiTreeNode): JsonObject = buildJsonObject {
        put("role", n.role)
        n.ref?.let { put("ref", it) }
        n.text?.let { put("text", it) }
        n.description?.let { put("description", it) }
        n.hintText?.let { put("hintText", it) }
        n.resourceId?.let { put("resourceId", it) }
        put("bounds", JsonArray(n.bounds.map(::JsonPrimitive)))
        if (n.clickable) put("clickable", true)
        if (n.editable) put("editable", true)
        if (n.scrollable) put("scrollable", true)
        if (n.checkable) put("checked", n.checked)
        if (n.selected) put("selected", true)
        if (!n.enabled) put("enabled", false)
        if (n.focused) put("focused", true)
        if (n.secret) put("secret", true)
        if (n.wheel) put("wheel", true)
    }

    /** 路径按窗口和子节点序号定位;同一路径的属性变化只发该节点,新增节点发完整子树。 */
    fun changes(before: ScreenSnapshot, current: ScreenSnapshot): JsonObject {
        val old = index(before.windows)
        val now = index(current.windows)
        val updated = buildJsonArray {
            for ((path, n) in now) {
                val previous = old[path]
                if (previous == null && parent(path) in now && parent(path) !in old) continue // 新父节点已经带上完整子树
                if (previous == null || shallow(previous) != shallow(n)) add(buildJsonObject {
                    put("path", path)
                    put("node", if (previous == null) node(n) else shallow(n))
                })
            }
        }
        val gone = old.keys.filterTo(LinkedHashSet()) { it !in now }
        val removed = buildJsonArray { for (path in gone) if (parent(path) !in gone) add(JsonPrimitive(path)) }
        return buildJsonObject {
            put("updated", updated)
            put("removed", removed)
        }
    }

    fun sameWindows(a: ScreenSnapshot, b: ScreenSnapshot): Boolean =
        a.windows.map { listOf(it.id, it.type, it.layer, it.title, it.packageName, it.active, it.focused) } ==
            b.windows.map { listOf(it.id, it.type, it.layer, it.title, it.packageName, it.active, it.focused) }

    private fun index(windows: List<UiWindowTree>): LinkedHashMap<String, UiTreeNode> = LinkedHashMap<String, UiTreeNode>().also { out ->
        fun walk(prefix: String, nodes: List<UiTreeNode>) {
            nodes.forEachIndexed { i, node ->
                val path = "$prefix/$i"
                out[path] = node
                walk(path, node.children)
            }
        }
        windows.forEach { walk("w${it.id}", it.nodes) }
    }

    private fun parent(path: String): String = path.substringBeforeLast('/', "")
}
