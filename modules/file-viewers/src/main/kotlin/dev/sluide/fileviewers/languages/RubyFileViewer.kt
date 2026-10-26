package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Ruby sources (`.rb`, `.rake`, `.gemspec`, `.ru`, `Gemfile`, `Rakefile` and the like, scripts with a `ruby` shebang) with syntax highlighting. */
@Component
class RubyFileViewer :
    CodeFileViewer(
        setOf("rb", "rake", "gemspec", "ru"),
        RSyntaxHighlighter.ruby,
        CommentSyntax.HASH,
        names = setOf("gemfile", "rakefile", "vagrantfile", "podfile", "brewfile", "fastfile", "guardfile"),
        interpreters = setOf("ruby"),
    )
