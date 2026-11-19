package dev.sluide.languageservers.files

/** What a glob means, as a tree: built by [Globs], compiled by [GlobMatcher]. */
internal sealed interface GlobNode {
    class Sequence(
        val parts: List<GlobNode>,
    ) : GlobNode

    class Alternation(
        val options: List<GlobNode>,
    ) : GlobNode

    class Optional(
        val inner: GlobNode,
    ) : GlobNode

    /** Zero or more of [atom]. */
    class Star(
        val atom: Atom,
    ) : GlobNode

    /** One code point that passes [test]. */
    class Atom(
        val test: (Int) -> Boolean,
    ) : GlobNode
}

/**
 * A glob that matches whole `/`-separated paths, built with [Globs.matcher]. The tree is compiled to a Thompson NFA
 * and run in lockstep over the path, each state at most once per position: a match takes at most path length times
 * program size steps, whatever the glob. The matcher is immutable; every call keeps its state to itself, so threads
 * may share one.
 */
internal class GlobMatcher(
    tree: GlobNode,
) {
    private sealed interface Instruction {
        class Test(
            val test: (Int) -> Boolean,
        ) : Instruction

        class Split(
            val first: Int,
            val second: Int,
        ) : Instruction

        class Jump(
            val target: Int,
        ) : Instruction

        data object Done : Instruction

        /** Holds the place of an instruction that needs what comes after it; always replaced. */
        data object Placeholder : Instruction
    }

    private val program: Array<Instruction>

    init {
        val code = mutableListOf<Instruction>()
        compile(tree, code)
        code += Instruction.Done
        program = code.toTypedArray()
    }

    /**
     * Appends the instructions of [tree] to [code]. The work is a stack of steps, not recursion: the nesting of braces
     * is the glob's to choose, and a deep one must not overflow the stack.
     */
    private fun compile(
        tree: GlobNode,
        code: MutableList<Instruction>,
    ) {
        val steps = ArrayDeque<() -> Unit>()

        fun visit(node: GlobNode) {
            when (node) {
                is GlobNode.Atom -> {
                    code += Instruction.Test(node.test)
                }

                is GlobNode.Sequence -> {
                    for (part in node.parts.asReversed()) steps.addLast { visit(part) }
                }

                is GlobNode.Optional -> {
                    val split = code.size
                    code += Instruction.Placeholder
                    steps.addLast { code[split] = Instruction.Split(split + 1, code.size) }
                    steps.addLast { visit(node.inner) }
                }

                is GlobNode.Star -> {
                    val split = code.size
                    code += Instruction.Placeholder
                    code += Instruction.Test(node.atom.test)
                    code += Instruction.Jump(split)
                    code[split] = Instruction.Split(split + 1, code.size)
                }

                is GlobNode.Alternation -> {
                    val jumps = mutableListOf<Int>()

                    fun option(index: Int) {
                        val last = index == node.options.lastIndex
                        val split = code.size
                        if (!last) code += Instruction.Placeholder
                        steps.addLast {
                            if (last) {
                                for (jump in jumps) code[jump] = Instruction.Jump(code.size)
                            } else {
                                jumps += code.size
                                code += Instruction.Placeholder
                                code[split] = Instruction.Split(split + 1, code.size)
                                option(index + 1)
                            }
                        }
                        steps.addLast { visit(node.options[index]) }
                    }
                    option(0)
                }
            }
        }

        visit(tree)
        while (steps.isNotEmpty()) steps.removeLast()()
    }

    /** Whether the whole [path] matches. */
    fun matches(path: CharSequence): Boolean {
        var current = StateSet(program.size)
        var next = StateSet(program.size)
        current.add(0)
        var index = 0
        while (index < path.length) {
            val codePoint = Character.codePointAt(path, index)
            index += Character.charCount(codePoint)
            next.clear()
            for (pc in current.states()) {
                val instruction = program[pc]
                if (instruction is Instruction.Test && instruction.test(codePoint)) next.add(pc + 1)
            }
            current = next.also { next = current }
        }
        return current.contains(program.lastIndex)
    }

    /** The states reached at one position, with the empty moves (split, jump) already followed. */
    private inner class StateSet(
        size: Int,
    ) {
        private val seen = BooleanArray(size)
        private val members = IntArray(size)
        private var count = 0
        private val pending = IntArray(size)

        fun states() = members.asSequence().take(count)

        fun contains(pc: Int) = seen[pc]

        fun clear() {
            seen.fill(false)
            count = 0
        }

        fun add(start: Int) {
            var top = 0

            fun push(pc: Int) {
                if (seen[pc]) return
                seen[pc] = true
                pending[top++] = pc
            }
            push(start)
            while (top > 0) {
                when (val instruction = program[pending[--top]]) {
                    is Instruction.Split -> {
                        push(instruction.second)
                        push(instruction.first)
                    }

                    is Instruction.Jump -> {
                        push(instruction.target)
                    }

                    else -> {
                        members[count++] = pending[top]
                    }
                }
            }
        }
    }
}
