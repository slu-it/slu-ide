package dev.sluide.fileviewers.bicep

/**
 * The resources and modules of a Bicep file and how they depend on each other, read from [BicepTokens] (so nothing in
 * comments or strings counts, but interpolations do). Best effort: it never throws and stays linear in the text, also
 * for files that are no valid Bicep.
 *
 * - Nodes are top-level `resource <symbol> '<type>@<api>' [existing] = …` and `module <symbol> '<path>' = …`, and
 *   `resource` declarations nested in a resource's body (child resources). A body runs to the next top-level
 *   declaration (at bracket depth 0, or any declaration keyword at the start of a line, which also recovers from
 *   unbalanced brackets); a child's body from its `{` (or loop `[`, after any `if (…)`) to the end of its brackets.
 * - An edge goes from a resource or module to each resource or module whose symbol its body uses (`dependsOn`,
 *   `storage.id`, `parent::child`, `...spread`), also through `var`s, transitively; a short name in a body is first a
 *   child of the resources the body is nested in, then a top-level one. A child has a [Edge.parent] edge to the resource
 *   it is nested in, and so does a resource with `parent: <symbol>`. Params, vars and outputs are not nodes.
 * - Only the first [maxNodes] nodes and [maxEdges] edges are kept, so that Mermaid draws them ([total] counts all).
 */
internal class BicepGraph private constructor(
    val nodes: List<Node>,
    val edges: List<Edge>,
    /** All resources and modules in the file, also those over [maxNodes]. */
    val total: Int,
    /** Whether edges were left out over [maxEdges]. */
    val edgesCut: Boolean,
) {
    enum class Kind { RESOURCE, MODULE }

    data class Node(
        val symbol: String,
        val kind: Kind,
        /** The resource type (`Microsoft.Storage/storageAccounts@2023-05-01`) or the module's path. */
        val target: String,
        val existing: Boolean = false,
        val loop: Boolean = false,
    ) {
        /** The last segment of a resource type without API version (`storageAccounts`), else the module path. */
        val shortTarget: String
            get() =
                if (kind == Kind.MODULE) {
                    target
                } else {
                    target
                        .substringBefore('@')
                        .trimEnd('/')
                        .substringAfterLast('/')
                        .ifEmpty { target }
                }
    }

    /** [from] depends on [to]; [parent] if [to] is its parent resource. */
    data class Edge(
        val from: Int,
        val to: Int,
        val parent: Boolean = false,
    )

    /** The graph as a Mermaid `flowchart LR`, with ids `r_<index>` and every label escaped ([mermaidLabel]). */
    fun toMermaid(): String =
        buildString {
            append("flowchart LR\n")
            for ((index, node) in nodes.withIndex()) {
                val symbol = if (node.loop) "${node.symbol} (loop)" else node.symbol
                val label = mermaidLabel(symbol) + "<br/>" + mermaidLabel(node.shortTarget, keepEnd = true)
                append("    r_").append(index)
                if (node.kind == Kind.MODULE) append("[[\"").append(label).append("\"]]\n")
                if (node.kind == Kind.RESOURCE) append("[\"").append(label).append("\"]\n")
            }
            for (edge in edges) {
                append("    r_").append(edge.from).append(if (edge.parent) " -->|parent| r_" else " --> r_")
                append(edge.to).append('\n')
            }
            val existing = nodes.indices.filter { nodes[it].existing }
            if (existing.isNotEmpty()) {
                append("    classDef existing stroke-dasharray: 5 5\n")
                append(existing.joinToString(",", prefix = "    class ", postfix = " existing\n") { "r_$it" })
            }
        }

    /** A line above the diagram when it is not complete, else `null`. */
    val note: String?
        get() =
            when {
                total > nodes.size -> {
                    "Showing the first ${nodes.size} of $total resources and modules."
                }

                edgesCut -> {
                    "Showing the first ${edges.size} dependencies."
                }

                else -> {
                    null
                }
            }

    companion object {
        /** Mermaid draws at most 500 edges and 50,000 characters; the node limit keeps the text below that. */
        const val MAX_NODES = 200
        const val MAX_EDGES = 400

        /**
         * The longest label part, in code points and in escaped characters; longer ones are cut with `…`. With them,
         * [MAX_NODES] and [MAX_EDGES], the diagram stays below Mermaid's 50,000 characters.
         */
        private const val MAX_LABEL = 48
        private const val MAX_LABEL_CHARS = 96

        private val TOP_LEVEL_WORDS =
            setOf(
                "resource",
                "module",
                "var",
                "param",
                "output",
                "type",
                "func",
                "metadata",
                "targetScope",
                "import",
                "extension",
                "using",
                "extends",
                "assert",
            )

        fun parse(
            text: String,
            maxNodes: Int = MAX_NODES,
            maxEdges: Int = MAX_EDGES,
        ): BicepGraph = Parser(BicepTokens.lex(text), maxNodes, maxEdges).run()

        /**
         * [text] for a quoted Mermaid label (`"…"`): letters, digits, blanks and `_-./:@(),` as they are, every other
         * character as an entity code (`#35;`), so quotes, `#`, `;`, `<`, backticks (Markdown strings) and the like
         * are text; control characters become blanks. Over [MAX_LABEL] code points or [MAX_LABEL_CHARS] characters,
         * the end (or, if [keepEnd], the start) is left out.
         */
        fun mermaidLabel(
            text: String,
            keepEnd: Boolean = false,
        ): String {
            val pieces = text.codePoints().toArray().map(::escape)
            if (pieces.size <= MAX_LABEL &&
                pieces.sumOf { it.length } <= MAX_LABEL_CHARS
            ) {
                return pieces.joinToString("")
            }
            val ellipsis = escape(ELLIPSIS)
            val kept = ArrayList<String>()
            var length = ellipsis.length
            for (piece in if (keepEnd) pieces.asReversed() else pieces) {
                if (kept.size == MAX_LABEL - 1 || length + piece.length > MAX_LABEL_CHARS) break
                kept += piece
                length += piece.length
            }
            return if (keepEnd) ellipsis + kept.asReversed().joinToString("") else kept.joinToString("") + ellipsis
        }

        private fun escape(point: Int): String =
            when {
                point < 0x20 || point == 0x7f -> " "
                isPlain(point) -> point.toChar().toString()
                else -> "#$point;"
            }

        private fun isPlain(point: Int) = point < 0x80 && (Character.isLetterOrDigit(point) || point.toChar() in SAFE)

        private const val ELLIPSIS = 0x2026

        /** How many enclosing resources a short name is looked up in; Bicep files nest a few levels at most. */
        private const val MAX_ANCESTORS = 32
        private const val SAFE = " _-./:@(),"
    }

    /**
     * A `resource`, `module`, `var` or `param` declaration; [node] is its index in the nodes, or -1 (also for
     * resources and modules over the limit, which are [isNode] all the same).
     */
    private class Declaration(
        val symbol: String,
        val node: Int,
        val isVar: Boolean,
        val isNode: Boolean,
        /** The declaration it is nested in (child resources), or -1. */
        val parent: Int,
    ) {
        /** The reference after `parent:` in its body, a token index, or -1. */
        var parentReference = -1
    }

    private class Parser(
        private val tokens: BicepTokens,
        private val maxNodes: Int,
        private val maxEdges: Int,
    ) {
        private val roles = tokens.roles()
        private val nodes = ArrayList<Node>()
        private var total = 0
        private val declarations = ArrayList<Declaration>()

        /** The references found: the declaration whose body has it, and the token. */
        private var referenceOwners = IntArray(64)
        private var referenceTokens = IntArray(64)
        private var references = 0

        fun run(): BicepGraph {
            walk()
            return BicepGraph(nodes, edges(), total, edgesCut)
        }

        /**
         * Child resources whose bodies are open: their declaration, the depth of their keyword, and whether their
         * body (its `{`, or the `[` of a loop) was entered; a condition's `(…)` before it is no body.
         */
        private val children = ArrayList<IntArray>()

        /** The name token of the last declaration, which is no reference. */
        private var declaredName = -1

        private fun walk() {
            var depth = 0
            var owner = -1
            var i = -1
            while (true) {
                i = tokens.nextSignificant(i)
                if (i < 0) break
                when (tokens.kind(i)) {
                    BicepTokens.PUNCTUATION -> {
                        if (tokens.isChar(i, '{') || tokens.isChar(i, '[') || tokens.isChar(i, '(')) depth++
                        if (tokens.isChar(i, '}') || tokens.isChar(i, ']') || tokens.isChar(i, ')')) {
                            depth = maxOf(0, depth - 1)
                        }
                    }

                    BicepTokens.INTERPOLATION_START -> {
                        depth++
                    }

                    BicepTokens.INTERPOLATION_END -> {
                        depth = maxOf(0, depth - 1)
                    }
                }
                closeChildren(depth, opensBody = tokens.isChar(i, '{') || tokens.isChar(i, '['))
                if (i == declaredName) continue
                val kind = tokens.kind(i)
                val role = roles[i]
                if (kind == BicepTokens.DECORATOR && depth == 0) {
                    owner = -1
                } else if (kind == BicepTokens.IDENTIFIER && role == BicepTokens.ROLE_KEYWORD) {
                    val word = tokens.text(i)
                    if (word in TOP_LEVEL_WORDS && (depth == 0 || startsAtColumnZero(i))) {
                        depth = 0
                        children.clear()
                        owner = declare(i, word, parent = -1)
                    } else if (word == "resource" && depth > 0) {
                        val enclosing = currentOwner(owner)
                        if (enclosing >= 0 && declarations[enclosing].isNode) {
                            val child = declare(i, word, parent = enclosing)
                            if (child >= 0) children += intArrayOf(child, depth, 0)
                        }
                    }
                } else if (kind == BicepTokens.IDENTIFIER && role == BicepTokens.ROLE_NONE) {
                    val current = currentOwner(owner)
                    if (current >= 0) addReference(current, i)
                } else if (kind == BicepTokens.IDENTIFIER && role == BicepTokens.ROLE_PROPERTY &&
                    tokens.isWord(i, "parent")
                ) {
                    val current = currentOwner(owner)
                    val colon = tokens.nextSignificant(i)
                    val target = if (colon >= 0) tokens.nextSignificant(colon) else -1
                    if (current >= 0 && target >= 0 && tokens.kind(target) == BicepTokens.IDENTIFIER &&
                        roles[target] == BicepTokens.ROLE_NONE
                    ) {
                        declarations[current].parentReference = target
                    }
                }
            }
        }

        private fun startsAtColumnZero(i: Int): Boolean {
            val start = tokens.start(i)
            return start == 0 || tokens.text[start - 1] == '\n'
        }

        /** Ends the child resources whose bodies [depth] has left; a token that [opensBody] may enter one. */
        private fun closeChildren(
            depth: Int,
            opensBody: Boolean,
        ) {
            while (children.isNotEmpty()) {
                val child = children.last()
                if (depth > child[1]) {
                    if (depth == child[1] + 1 && opensBody) child[2] = 1
                    return
                }
                if (child[2] == 0 && depth == child[1]) return
                children.removeAt(children.size - 1)
            }
        }

        private fun currentOwner(owner: Int): Int = if (children.isEmpty()) owner else children.last()[0]

        /**
         * Reads the declaration started by keyword token [keyword] ([word]) and returns its index, or -1 for one
         * whose body is not followed (outputs, types, functions, imports, or a declaration without a name).
         */
        private fun declare(
            keyword: Int,
            word: String,
            parent: Int,
        ): Int {
            if (word != "resource" && word != "module" && word != "var" && word != "param") return -1
            val name = tokens.nextSignificant(keyword)
            if (name < 0 || tokens.kind(name) != BicepTokens.IDENTIFIER) return -1
            val symbol = tokens.text(name)
            declaredName = name
            if (word == "var" || word == "param") {
                declarations += Declaration(symbol, node = -1, isVar = word == "var", isNode = false, parent = parent)
                return declarations.size - 1
            }
            var next = tokens.nextSignificant(name)
            var target = ""
            if (next >= 0 && tokens.kind(next) == BicepTokens.STRING) {
                target = unquote(tokens.text(next))
                next = tokens.nextSignificant(next)
            }
            var existing = false
            if (next >= 0 && tokens.kind(next) == BicepTokens.IDENTIFIER && tokens.isWord(next, "existing")) {
                existing = true
                next = tokens.nextSignificant(next)
            }
            var loop = false
            if (next >= 0 && tokens.isChar(next, '=')) {
                val bracket = tokens.nextSignificant(next)
                val keywordFor = if (bracket >= 0) tokens.nextSignificant(bracket) else -1
                loop =
                    bracket >= 0 && tokens.isChar(bracket, '[') && keywordFor >= 0 && tokens.isWord(keywordFor, "for")
            }
            total++
            val node =
                if (nodes.size < maxNodes) {
                    val kind = if (word == "module") Kind.MODULE else Kind.RESOURCE
                    nodes += Node(symbol, kind, target, existing, loop)
                    nodes.size - 1
                } else {
                    -1
                }
            declarations += Declaration(symbol, node, isVar = false, isNode = true, parent = parent)
            return declarations.size - 1
        }

        private fun unquote(string: String): String = string.removePrefix("'").removeSuffix("'")

        private fun addReference(
            owner: Int,
            token: Int,
        ) {
            if (references == referenceOwners.size) {
                referenceOwners = referenceOwners.copyOf(references * 2)
                referenceTokens = referenceTokens.copyOf(references * 2)
            }
            referenceOwners[references] = owner
            referenceTokens[references] = token
            references++
        }

        private var edgesCut = false

        private fun edges(): List<Edge> {
            // Top-level symbols by name; children by their parent and name (see resolve).
            val bySymbol = HashMap<String, Int>()
            val childBySymbol = HashMap<Pair<Int, String>, Int>()
            for ((index, declaration) in declarations.withIndex()) {
                if (declaration.parent < 0) {
                    bySymbol.putIfAbsent(declaration.symbol, index)
                } else {
                    childBySymbol.putIfAbsent(declaration.parent to declaration.symbol, index)
                }
            }
            // The declarations each body references, as compressed rows.
            val rowStarts = IntArray(declarations.size + 1)
            val targets = IntArray(references)
            for (r in 0 until references) rowStarts[referenceOwners[r] + 1]++
            for (d in 0 until declarations.size) rowStarts[d + 1] += rowStarts[d]
            val fill = rowStarts.copyOf()
            for (r in 0 until references) {
                val owner = referenceOwners[r]
                targets[fill[owner]++] = resolve(referenceTokens[r], owner, bySymbol, childBySymbol)
            }
            val words = (nodes.size + 63) / 64
            val vars = VarDependencies(declarations, rowStarts, targets, words)
            val edges = ArrayList<Edge>()
            val bits = LongArray(words)
            for ((index, declaration) in declarations.withIndex()) {
                val from = declaration.node
                if (from < 0) continue
                bits.fill(0)
                for (r in rowStarts[index] until rowStarts[index + 1]) {
                    val target = targets[r]
                    if (target < 0) continue
                    val node = declarations[target].node
                    if (node >= 0) {
                        bits.set(node)
                    } else if (declarations[target].isVar) {
                        vars.addTo(target, bits)
                    }
                }
                val parent =
                    if (declaration.parent >= 0) {
                        declarations[declaration.parent].node
                    } else if (declaration.parentReference >= 0) {
                        resolve(declaration.parentReference, index, bySymbol, childBySymbol).let {
                            if (it >= 0) declarations[it].node else -1
                        }
                    } else {
                        -1
                    }
                bits.clear(from)
                if (parent >= 0 && parent != from) {
                    bits.clear(parent)
                    if (!add(edges, Edge(from, parent, parent = true))) return edges
                }
                for (word in 0 until words) {
                    var remaining = bits[word]
                    while (remaining != 0L) {
                        val bit = java.lang.Long.numberOfTrailingZeros(remaining)
                        remaining = remaining and (remaining - 1)
                        if (!add(edges, Edge(from, word * 64 + bit))) return edges
                    }
                }
            }
            return edges
        }

        private fun add(
            edges: MutableList<Edge>,
            edge: Edge,
        ): Boolean {
            if (edges.size >= maxEdges) {
                edgesCut = true
                return false
            }
            edges += edge
            return true
        }

        /**
         * The declaration the reference at [token] in the body of [owner] names (`a`, or the child `b` for `a::b`), or
         * -1. A short name is first a child of [owner] or of the resources it is nested in (up to [MAX_ANCESTORS] of
         * them), the innermost first, then a top-level declaration.
         */
        private fun resolve(
            token: Int,
            owner: Int,
            bySymbol: Map<String, Int>,
            childBySymbol: Map<Pair<Int, String>, Int>,
        ): Int {
            val name = tokens.text(token)
            var declaration = -1
            var ancestor = owner
            var steps = 0
            while (ancestor >= 0 && steps++ < MAX_ANCESTORS && declaration < 0) {
                declaration = childBySymbol[ancestor to name] ?: -1
                ancestor = declarations[ancestor].parent
            }
            if (declaration < 0) declaration = bySymbol[name] ?: return -1
            var i = token
            while (true) {
                val separator = tokens.nextSignificant(i)
                if (separator < 0 || !tokens.isWord(separator, "::")) return declaration
                val child = tokens.nextSignificant(separator)
                if (child < 0 || tokens.kind(child) != BicepTokens.IDENTIFIER) return declaration
                declaration = childBySymbol[declaration to tokens.text(child)] ?: return declaration
                i = child
            }
        }
    }

    /**
     * The nodes each `var` depends on, through other vars too, as bit sets of [words] longs; computed once per var,
     * iteratively (a chain of vars may be long). Cycles, which valid Bicep has none of, are cut where they close.
     */
    private class VarDependencies(
        private val declarations: List<Declaration>,
        private val rowStarts: IntArray,
        private val targets: IntArray,
        private val words: Int,
    ) {
        private val state = ByteArray(declarations.size)
        private var sets: LongArray? = null

        fun addTo(
            variable: Int,
            bits: LongArray,
        ) {
            if (words == 0) return
            resolve(variable)
            val sets = sets ?: return
            for (word in 0 until words) bits[word] = bits[word] or sets[variable * words + word]
        }

        private fun resolve(start: Int) {
            if (state[start] == DONE) return
            val sets = sets ?: LongArray(declarations.size * words).also { sets = it }
            // Pairs of a var and the next of its references to look at.
            val stack = ArrayList<IntArray>()
            state[start] = OPEN
            stack += intArrayOf(start, rowStarts[start])
            while (stack.isNotEmpty()) {
                val top = stack.last()
                val variable = top[0]
                if (top[1] == rowStarts[variable + 1]) {
                    state[variable] = DONE
                    stack.removeAt(stack.size - 1)
                    if (stack.isNotEmpty()) {
                        val user = stack.last()[0]
                        for (word in 0 until words) {
                            sets[user * words + word] = sets[user * words + word] or sets[variable * words + word]
                        }
                    }
                    continue
                }
                val target = targets[top[1]++]
                if (target < 0) continue
                val declaration = declarations[target]
                if (declaration.node >= 0) {
                    sets[variable * words + declaration.node / 64] =
                        sets[variable * words + declaration.node / 64] or (1L shl (declaration.node % 64))
                } else if (declaration.isVar) {
                    when (state[target]) {
                        DONE -> {
                            for (word in 0 until words) {
                                sets[variable * words + word] =
                                    sets[variable * words + word] or sets[target * words + word]
                            }
                        }

                        UNSEEN -> {
                            state[target] = OPEN
                            stack += intArrayOf(target, rowStarts[target])
                        }
                    }
                }
            }
        }

        companion object {
            private const val UNSEEN: Byte = 0
            private const val OPEN: Byte = 1
            private const val DONE: Byte = 2
        }
    }
}

private fun LongArray.set(bit: Int) {
    this[bit / 64] = this[bit / 64] or (1L shl (bit % 64))
}

private fun LongArray.clear(bit: Int) {
    this[bit / 64] = this[bit / 64] and (1L shl (bit % 64)).inv()
}
