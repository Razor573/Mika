package com.wangzan101.mika.core.uistate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** 给模型看的手机窗口层级;节点完整保留,只省去空字段和默认状态。 */
object UiTreeJson {
    private val LAYOUT_WRAPPERS = setOf("FrameLayout", "LinearLayout", "ViewGroup")
    private const val VIEWPORT_MIN = 0
    private const val VIEWPORT_MAX = 1000

    private val WINDOW_ORDER = compareBy<UiWindowTree>(
        { it.layer },
        { it.id },
        { it.type },
        { it.packageName.orEmpty() },
        { it.title.orEmpty() },
        { it.active },
        { it.focused },
    )

    private val NODE_ORDER = compareBy<UiTreeNode>(
        { it.bounds.getOrElse(1) { 0 } },
        { it.bounds.getOrElse(0) { 0 } },
        { it.bounds.getOrElse(3) { 0 } },
        { it.bounds.getOrElse(2) { 0 } },
        { it.role },
        { it.resourceId.orEmpty() },
        { it.text.orEmpty() },
        { it.description.orEmpty() },
        { it.hintText.orEmpty() },
        { it.ref.orEmpty() },
    )

    internal val ELEMENT_CANONICAL_ORDER: Comparator<UiElement> = compareBy<UiElement>(
        { it.bounds.getOrElse(1) { 0 } },
        { it.bounds.getOrElse(0) { 0 } },
        { it.bounds.getOrElse(3) { 0 } },
        { it.bounds.getOrElse(2) { 0 } },
        { it.bounds.size },
        { it.role },
        { it.name },
        { it.resourceId.orEmpty() },
        { it.centerX },
        { it.centerY },
        { it.clickable },
        { it.editable },
        { it.scrollable },
        { it.hint },
        { it.focused },
        { it.secret },
        { it.searchField },
        { it.placeholder.orEmpty() },
    ).thenBy { it.bounds.joinToString(",") }
        .thenBy { it.states.sorted().joinToString("\u0000") }
        .thenBy { it.keyShortcuts.sorted().joinToString("\u0000") }

    private val HASH_NODE_ORDER = compareBy<Pair<UiTreeNode, ByteArray>>(
        { it.first.bounds.getOrElse(1) { 0 } },
        { it.first.bounds.getOrElse(0) { 0 } },
        { it.first.bounds.getOrElse(3) { 0 } },
        { it.first.bounds.getOrElse(2) { 0 } },
        { it.first.bounds.size },
        { it.first.role },
        { it.first.resourceId.orEmpty() },
        { it.first.text.orEmpty() },
        { it.first.description.orEmpty() },
        { it.first.hintText.orEmpty() },
        { it.first.clickable },
        { it.first.editable },
        { it.first.scrollable },
        { it.first.checkable },
        { it.first.checked },
        { it.first.selected },
        { it.first.enabled },
        { it.first.focused },
        { it.first.secret },
        { it.first.wheel },
    ).thenBy { it.first.bounds.joinToString(",") }
        .thenComparator { a, b -> compareBytes(a.second, b.second) }

    data class PrunedResult(
        val windows: JsonArray,
        val meaningfulCount: Int,
    )

    /**
     * Canonical SHA-256 hex digest over normalized screen content, excluding transient
     * `snapshotId`, `UiElement.ref`, `UiTreeNode.ref`, `changedDuringRead`, and timestamps.
     */
    fun canonicalHash(s: ScreenSnapshot): String {
        val out = ByteArrayOutputStream()
        out.writeStr(s.packageName?.trim().orEmpty())
        out.writeInt(s.width)
        out.writeInt(s.height)
        out.writeBool(s.protected)
        out.writeBool(s.hasWindow)
        out.writeOptStr(s.unreadableApp?.trim()?.ifEmpty { null })
        out.writeBool(s.web != null)
        out.writeStr(s.web?.url.orEmpty())
        out.writeStr(s.web?.title.orEmpty())

        val sortedElements = s.elements.sortedWith(ELEMENT_CANONICAL_ORDER)
        out.writeInt(sortedElements.size)
        for (e in sortedElements) {
            out.writeStr(e.role)
            out.writeStr(e.name)
            out.writeInt(e.centerX)
            out.writeInt(e.centerY)
            out.writeIntList(e.bounds)
            out.writeOptStr(e.resourceId)
            out.writeBool(e.clickable)
            out.writeBool(e.editable)
            out.writeBool(e.scrollable)
            out.writeBool(e.hint)
            out.writeBool(e.focused)
            out.writeStrList(e.keyShortcuts.sorted())
            out.writeBool(e.secret)
            out.writeOptStr(e.placeholder)
            out.writeBool(e.searchField)
            out.writeStrList(e.states.sorted())
        }

        val sortedWindows = s.windows.sortedWith(WINDOW_ORDER)
        out.writeInt(sortedWindows.size)
        for (w in sortedWindows) {
            out.writeInt(w.id)
            out.writeStr(w.type)
            out.writeInt(w.layer)
            out.writeOptStr(w.title)
            out.writeOptStr(w.packageName)
            out.writeBool(w.active)
            out.writeBool(w.focused)
            val sortedNodes = w.nodes.map { it to canonicalNodeDigest(it) }.sortedWith(HASH_NODE_ORDER)
            out.writeInt(sortedNodes.size)
            for ((_, digest) in sortedNodes) {
                out.writeInt(digest.size)
                out.write(digest)
            }
        }
        return sha256(out.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun windows(s: ScreenSnapshot): JsonArray = buildJsonArray {
        for (w in s.windows.sortedWith(WINDOW_ORDER)) add(buildJsonObject {
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
            for (w in s.windows.sortedWith(WINDOW_ORDER)) {
                val prunedNodes = w.nodes.flatMap { pruneNode(it) }.sortedWith(NODE_ORDER)
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
        val prunedChildren = n.children.flatMap { pruneNode(it) }.sortedWith(NODE_ORDER)
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
        a.windows.sortedWith(WINDOW_ORDER).map { listOf(it.id, it.type, it.layer, it.title, it.packageName, it.active, it.focused) } ==
            b.windows.sortedWith(WINDOW_ORDER).map { listOf(it.id, it.type, it.layer, it.title, it.packageName, it.active, it.focused) }

    private fun index(windows: List<UiWindowTree>): LinkedHashMap<String, UiTreeNode> = LinkedHashMap<String, UiTreeNode>().also { out ->
        fun walk(prefix: String, nodes: List<UiTreeNode>) {
            nodes.forEachIndexed { i, node ->
                val path = "$prefix/$i"
                out[path] = node
                walk(path, node.children)
            }
        }
        windows.sortedWith(WINDOW_ORDER).forEach { walk("w${it.id}", it.nodes) }
    }

    private fun parent(path: String): String = path.substringBeforeLast('/', "")

    private fun canonicalNodeDigest(n: UiTreeNode): ByteArray {
        val childEntries = n.children.map { it to canonicalNodeDigest(it) }.sortedWith(HASH_NODE_ORDER)
        val out = ByteArrayOutputStream()
        out.writeStr(n.role)
        out.writeIntList(n.bounds)
        out.writeOptStr(n.text)
        out.writeOptStr(n.description)
        out.writeOptStr(n.hintText)
        out.writeOptStr(n.resourceId)
        out.writeBool(n.clickable)
        out.writeBool(n.editable)
        out.writeBool(n.scrollable)
        out.writeBool(n.checkable)
        out.writeBool(n.checked)
        out.writeBool(n.selected)
        out.writeBool(n.enabled)
        out.writeBool(n.focused)
        out.writeBool(n.secret)
        out.writeBool(n.wheel)
        out.writeInt(childEntries.size)
        for ((_, digest) in childEntries) {
            out.writeInt(digest.size)
            out.write(digest)
        }
        return sha256(out.toByteArray())
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            val cmp = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (cmp != 0) return cmp
        }
        return a.size - b.size
    }

    private fun ByteArrayOutputStream.writeInt(v: Int) {
        write((v ushr 24) and 0xFF)
        write((v ushr 16) and 0xFF)
        write((v ushr 8) and 0xFF)
        write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.writeBool(v: Boolean) {
        write(if (v) 1 else 0)
    }

    private fun ByteArrayOutputStream.writeStr(v: String) {
        val bytes = v.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeOptStr(v: String?) {
        if (v == null) {
            write(0)
        } else {
            write(1)
            writeStr(v)
        }
    }

    private fun ByteArrayOutputStream.writeIntList(values: List<Int>) {
        writeInt(values.size)
        for (v in values) writeInt(v)
    }

    private fun ByteArrayOutputStream.writeStrList(values: List<String>) {
        writeInt(values.size)
        for (v in values) writeStr(v)
    }
}
