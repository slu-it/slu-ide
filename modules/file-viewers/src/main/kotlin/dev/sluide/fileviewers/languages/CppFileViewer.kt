package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/**
 * Shows C++ sources and headers (`.cpp`, `.cc`, `.cxx`, `.c++`, `.h`, `.hpp`, `.hh`, `.hxx`, Arduino `.ino`) with
 * syntax highlighting; `.h` is here because its lexer reads C as well.
 */
@Component
class CppFileViewer :
    CodeFileViewer(
        setOf("cpp", "cc", "cxx", "c++", "h", "hpp", "hh", "hxx", "ino"),
        RSyntaxHighlighter.cpp,
        CommentSyntax.SLASHES,
    )
