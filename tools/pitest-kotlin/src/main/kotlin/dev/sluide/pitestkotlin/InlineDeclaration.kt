package dev.sluide.pitestkotlin

import org.pitest.bytecode.analysis.MethodTree

/**
 * True when [method] is the declaration of an inline function, which Kotlin callers never run: they run its inlined
 * copy. The compiler marks the body of an inline function with a local `$i$f$<name>`, `<name>` being the method's JVM
 * name (also when mangled: `twice$module` for an internal one, `scaled-zWZb0L8` for one with a value-class parameter).
 * In the declaration that marker reaches the end of the method; in a caller of a same-named inline function the
 * caller's own return follows it. Without a local-variable table nothing marks it, and the method's mutants stay.
 */
internal fun isInlineDeclaration(method: MethodTree): Boolean {
    val marker =
        method
            .rawNode()
            .localVariables
            .orEmpty()
            .firstOrNull { it.name == "\$i\$f\$${method.rawNode().name}" }
    if (marker == null) return false
    val instructions = method.instructions()
    return instructions.drop(instructions.indexOf(marker.end)).none { it.opcode != -1 }
}
