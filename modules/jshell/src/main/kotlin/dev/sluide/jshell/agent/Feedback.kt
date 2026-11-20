package dev.sluide.jshell.agent

import jdk.jshell.DeclarationSnippet
import jdk.jshell.EvalException
import jdk.jshell.ExpressionSnippet
import jdk.jshell.JShell
import jdk.jshell.MethodSnippet
import jdk.jshell.Snippet
import jdk.jshell.SnippetEvent
import jdk.jshell.TypeDeclSnippet
import jdk.jshell.UnresolvedReferenceException
import jdk.jshell.VarSnippet

/** JShell's normal feedback, shortened ("x ==> 5", "created method f(int)"). Null when the jshell tool shows none. */
internal fun feedback(
    shell: JShell,
    event: SnippetEvent,
): String? {
    val snippet = event.snippet()
    val status = event.status()
    if (status == Snippet.Status.REJECTED) return null
    // a snippet that replaces a rejected one starts out NONEXISTENT again
    val action =
        if (event.previousStatus() == Snippet.Status.NONEXISTENT) {
            "created"
        } else if (event.isSignatureChange) {
            "replaced"
        } else {
            "modified"
        }
    val unresolved =
        (snippet as? DeclarationSnippet)
            ?.let {
                shell
                    .unresolvedDependencies(it)
                    .iterator()
                    .asSequence()
                    .toList()
            }.orEmpty()
    val however =
        if (unresolved.isEmpty()) {
            ""
        } else {
            val what = if (status == Snippet.Status.RECOVERABLE_DEFINED) "cannot be invoked" else "cannot be referenced"
            ", however, it $what until ${unresolved.joinToString(", ")} is declared"
        }
    return when (snippet) {
        is VarSnippet -> {
            // null for a variable that cannot be referenced yet
            val value = event.value()
            if (value == null) null else "${snippet.name()} ==> $value"
        }

        is ExpressionSnippet -> {
            "${snippet.name()} ==> ${event.value()}"
        }

        is MethodSnippet -> {
            "$action method ${snippet.name()}(${snippet.parameterTypes()})$however"
        }

        is TypeDeclSnippet -> {
            "$action ${kindName(snippet)} ${snippet.name()}$however"
        }

        else -> {
            null
        }
    }
}

private fun kindName(snippet: TypeDeclSnippet): String {
    val kind = snippet.subKind()
    return if (kind == Snippet.SubKind.INTERFACE_SUBKIND) {
        "interface"
    } else if (kind == Snippet.SubKind.ENUM_SUBKIND) {
        "enum"
    } else if (kind == Snippet.SubKind.ANNOTATION_TYPE_SUBKIND) {
        "annotation interface"
    } else if (kind.name == "RECORD_SUBKIND") {
        // the constant exists from JDK 16 on; the agent compiles against JDK 9
        "record"
    } else {
        "class"
    }
}

/**
 * The exception as the user reads it: its class and message, then its frames, a frame of a session snippet as its
 * document line ("line 6"), every other one with its class and file; then each cause the same way.
 */
internal fun describe(
    exception: Throwable,
    snippetLines: Map<String, Int>,
): String {
    val out = StringBuilder()
    var current: Throwable? = exception
    while (current != null) {
        if (current !== exception) out.append("Caused by: ")
        out.append(headline(current)).append('\n')
        for (frame in current.stackTrace) {
            val line = frameLine(frame.fileName, frame.lineNumber, snippetLines)
            out.append("\tat ")
            if (line != null) {
                if (frame.methodName.isNotEmpty()) out.append(frame.methodName).append(' ')
                out.append("(line ").append(line + 1).append(")\n")
            } else {
                out
                    .append(frame.className)
                    .append('.')
                    .append(frame.methodName)
                    .append(" (")
                    .append(frame.fileName)
                    .append(':')
                    .append(frame.lineNumber)
                    .append(")\n")
            }
        }
        current = current.cause
    }
    return out.toString().trimEnd()
}

private fun headline(exception: Throwable): String =
    when (exception) {
        is UnresolvedReferenceException -> {
            "Attempted to call ${exception.snippet.name()} which cannot be invoked until its references are declared"
        }

        // the snippet's exception, whose class exists only in the execution JVM
        is EvalException -> {
            val message = exception.message
            exception.exceptionClassName + if (message == null) "" else ": $message"
        }

        else -> {
            exception.toString()
        }
    }

/** The document line (0-based) of the innermost frame in a snippet of this session; null if there is none. */
internal fun throwingLine(
    exception: Throwable,
    snippetLines: Map<String, Int>,
): Int? =
    exception.stackTrace
        .asSequence()
        .mapNotNull { frameLine(it.fileName, it.lineNumber, snippetLines) }
        .firstOrNull()
