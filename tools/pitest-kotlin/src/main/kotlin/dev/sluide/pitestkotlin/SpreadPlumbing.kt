package dev.sluide.pitestkotlin

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.VarInsnNode

/**
 * True when [call] is plumbing of a spread operator: the copy `*array` makes (`ALOAD n, ALOAD n, ARRAYLENGTH,
 * Arrays.copyOf` without a null check after it), or the size and array of the `SpreadBuilder` that `*array` among
 * other arguments fills.
 */
internal fun isSpreadPlumbing(call: MethodInsnNode): Boolean =
    when (call.owner) {
        "kotlin/jvm/internal/SpreadBuilder" -> call.name == "size" || call.name == "toArray"
        "java/util/Arrays" -> call.name == "copyOf" && copiesWholeLocal(call) && !isCheckedForNull(call)
        else -> false
    }

/**
 * `ALOAD n, ALOAD n, ARRAYLENGTH` before [call]: the array in local n copied at its own length. Their opcodes need no
 * check: the one before `ARRAYLENGTH` must push the array, so it is an `ALOAD`, and the first, an `ALOAD` or after a
 * `DUP` an `ASTORE`, gives the copy the array of local n either way.
 */
private fun copiesWholeLocal(call: MethodInsnNode): Boolean {
    val length = call.previousReal() ?: return false
    val array = length.previousReal() as? VarInsnNode ?: return false
    val sameArray = array.previousReal() as? VarInsnNode ?: return false
    return length.opcode == Opcodes.ARRAYLENGTH && array.`var` == sameArray.`var`
}

/**
 * `DUP, LDC, Intrinsics.checkNotNullExpressionValue` after [call]: Kotlin checks a Java result it uses as non-null, as
 * for a copy we make (`array.copyOf()`, `Arrays.copyOf(array, array.size)`); a spread's copy goes to its call unchecked.
 */
private fun isCheckedForNull(call: MethodInsnNode): Boolean {
    val check = call.nextReal()?.nextReal()?.nextReal() as? MethodInsnNode ?: return false
    return "${check.owner}.${check.name}" == "$INTRINSICS.checkNotNullExpressionValue"
}

/** The instruction before this one, labels, line numbers and frames aside. */
private fun AbstractInsnNode.previousReal(): AbstractInsnNode? =
    generateSequence(previous) {
        it.previous
    }.firstOrNull(::isReal)

/** The instruction after this one, labels, line numbers and frames aside. */
private fun AbstractInsnNode.nextReal(): AbstractInsnNode? = generateSequence(next) { it.next }.firstOrNull(::isReal)

private fun isReal(instruction: AbstractInsnNode) = instruction.opcode != -1
