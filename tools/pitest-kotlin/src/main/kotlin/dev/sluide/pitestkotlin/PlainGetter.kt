package dev.sluide.pitestkotlin

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.pitest.bytecode.analysis.MethodTree

/**
 * True when [method]'s code only returns a field, by its instructions (labels and line numbers aside): `this.field`
 * (`ALOAD 0, GETFIELD`), a static field (`GETSTATIC`, an `object`'s property), or a companion's property through the
 * accessor the compiler adds to the outer class (`INVOKESTATIC Outer.access$get<Name>$cp`), then the return.
 */
internal fun isPlainGetter(method: MethodTree): Boolean {
    val code = method.instructions().filter { it.opcode != -1 }
    if (code.lastOrNull()?.isValueReturn() != true) return false
    val load = code.dropLast(1)
    return when (load.size) {
        1 -> load[0].opcode == Opcodes.GETSTATIC || load[0].isCompanionFieldAccessor()
        2 -> load[0].isLoadOfThis() && load[1].opcode == Opcodes.GETFIELD
        else -> false
    }
}

private fun AbstractInsnNode.isValueReturn() = opcode in Opcodes.IRETURN..Opcodes.ARETURN

/** Local 0, `this`: the first of two instructions that leave a field on the empty stack can only be an `ALOAD`. */
private fun AbstractInsnNode.isLoadOfThis() = this is VarInsnNode && `var` == 0

/**
 * The static `access$get<Name>$cp` the compiler adds to the outer class for a companion's property: `$cp` marks the
 * accessors of a companion's fields, and of those only the getter returns a value.
 */
private fun AbstractInsnNode.isCompanionFieldAccessor() = this is MethodInsnNode && name.endsWith("\$cp")
