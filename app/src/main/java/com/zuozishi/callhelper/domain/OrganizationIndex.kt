package com.zuozishi.callhelper.domain

/** Built once with the snapshot, outside UI composition. Keys encode full paths. */
class OrganizationIndex(people: List<Personnel>) {
    data class Node(val key: String, val name: String, val parent: String?, val children: List<String>,
                    val people: List<Personnel>, val total: Int)
    private class Builder(val key: String, val name: String, val parent: String?) {
        val children = linkedSetOf<String>()
        val people = mutableListOf<Personnel>()
        var total = 0
    }
    val nodes: Map<String, Node>
    val root: Node get() = nodes.getValue("")
    init {
        val builders = linkedMapOf("" to Builder("", "全部组织", null))
        people.forEach { person ->
            val top = person.topOrgName.trim().ifBlank { "未归属组织" }
            // Current exports use middle dots. Slashes remain accepted for sample/manual CSVs.
            val segments = person.fullOrgName.split('·', '/', '\\', '>').map { it.trim() }.filter { it.isNotEmpty() }
            // Keep every ancestor: browsing starts at the actual root, not TopOrgName.
            val path = segments.ifEmpty { listOf(top) }
            var parent = ""
            builders.getValue(parent).total++
            path.forEach { segment ->
                val key = parent + "${segment.length}:$segment"
                builders.getValue(parent).children.add(key)
                val node = builders.getOrPut(key) { Builder(key, segment, parent) }
                node.total++
                parent = key
            }
            builders.getValue(parent).people.add(person)
        }
        nodes = builders.mapValues { (_, node) -> Node(node.key, node.name, node.parent,
            node.children.sortedBy { builders.getValue(it).name }, node.people.sortedBy { it.name }, node.total) }
    }
    fun path(key: String): List<Node> {
        val result = mutableListOf<Node>()
        var current = nodes[key] ?: root
        while (true) {
            result.add(current)
            current = current.parent?.let { nodes[it] } ?: break
        }
        return result.asReversed()
    }
}
