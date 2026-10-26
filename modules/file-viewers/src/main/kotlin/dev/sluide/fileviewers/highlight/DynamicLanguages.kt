package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.TokenTypes

/**
 * The [RSyntaxHighlighter]s of dynamic languages and scripts: Ruby, PHP, Perl, Lua, Clojure, Lisp and Scheme, Tcl
 * and Windows batch files. The lexers are old; each is corrected for the current language where that is cheap, the
 * rest is listed as known gaps in `docs/features/file-viewers.md`.
 *
 * Line rules stay linear: possessive quantifiers wherever blanks or words repeat, no quantifier over alternatives
 * that can match the same text.
 */
internal object DynamicLanguages {
    private fun words(list: String): Set<String> = list.trim().split(Regex("""\s+""")).toSet()

    /**
     * Ruby. Symbols (`:name`, lexed as preprocessor) and variables (`@name`, `@@name`, `$name`) are annotations;
     * hash keys (`name:`, also keyword arguments) are properties; `%i[…]` is a string like the `%w[…]` the lexer knows. Known gaps:
     * heredocs are lexed as code (so `#{…}` in one looks like a comment), `/regex/` literals are operators, and a
     * `#{…}` in a string is not set apart from it.
     */
    val ruby =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_RUBY,
            keywords = words("__FILE__ __method__ __dir__"),
            builtins =
                words(
                    "attr_reader attr_accessor attr_writer require_relative include extend prepend private_constant",
                ),
            tokenStyles =
                mapOf(TokenTypes.PREPROCESSOR to TokenStyle.ANNOTATION, TokenTypes.VARIABLE to TokenStyle.ANNOTATION),
            lineRules =
                listOf(
                    Regex("""(?<![\w:@$.])(?<property>[A-Za-z_]\w*+[?!]?)(?=:(?!:)(?:[ \t]|$))"""),
                    Regex("""(?<![\w)\]}])(?<string>%i[\[({<][^\])}>\n]*+(?:[\])}>]|$))"""),
                ),
        )

    private const val PHP_ATTRIBUTE = """#\[(?:[^\[\]\n]++|\[[^\[\]\n]*+])*+]"""

    /**
     * PHP, HTML around `<?php … ?>` included. Variables (`$name`) are annotations, and so are attributes
     * (`#[Route('/')]`), which the lexer takes for `#` comments: a mask hides the attribute from it (in PHP 8 `#[`
     * always starts one), so code after it on the same line is lexed as code. The lexer misses PHP 8's `enum`,
     * `readonly`, `match`, `fn` and the type names; the name after `->`,
     * `?->` and `::` (`$table->string()`, `$r->match()`) is a property, not a keyword or type. Known gaps:
     * attributes over several lines are shown as comments, the member rule also styles `p::before` in embedded CSS
     * and `a->b` in HTML text, and heredocs are lexed as code.
     */
    val php =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_PHP,
            keywords = words("enum readonly match fn trait finally insteadof yield"),
            types = words("string int float bool iterable callable void never mixed object"),
            tokenStyles = mapOf(TokenTypes.VARIABLE to TokenStyle.ANNOTATION),
            lexerMasks = listOf(Regex(PHP_ATTRIBUTE)),
            lineRules =
                listOf(
                    Regex("""(?<annotation>$PHP_ATTRIBUTE)"""),
                    Regex("""(?<=->|::)(?!class\b)(?<property>[A-Za-z_]\w*+)"""),
                ),
        )

    /**
     * Perl. Variables of all three sigils (`$x`, `@list`, `%hash`) are annotations, the shebang a comment, `my`,
     * `use` and the like keywords instead of the lexer's functions, and `q(…)`, `qq{…}`, `qw[…]`, `qr<…>`, `qx(…)` strings;
     * format specifiers in strings (`%d`, `%-5s`, `%.2f`) are strings, not hashes. Known gaps: text after `__END__`
     * is lexed as code, `\@list` references and `s///` are not styled, an `@` in a string counts as a variable, and
     * a hash named like a specifier (`%s`, `%d`) is a string.
     */
    val perl =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_PERL,
            keywords = words("my our local use no package require return state BEGIN END __PACKAGE__"),
            tokenStyles =
                mapOf(TokenTypes.VARIABLE to TokenStyle.ANNOTATION, TokenTypes.PREPROCESSOR to TokenStyle.COMMENT),
            lineRules =
                listOf(
                    Regex(
                        """(?<![\w$@%&>-])(?<string>q[qwrx]?+[ \t]*+""" +
                            """(?:\([^()\n]*+\)|\[[^\[\]\n]*+]|\{[^{}\n]*+}|<[^<>\n]*+>))""",
                    ),
                    // The lexer takes the `%d` of `printf("%d")` for a hash.
                    Regex("""(?<string>%[-+ 0#]*+\d*+(?:\.\d++)?+[sdfeEgGxXobcu])(?!\w)"""),
                ),
        )

    /**
     * Lua 5.4. The attributes `<const>` and `<close>` are annotations, labels (`::continue::`) types, and the
     * shebang of a script a comment. Long strings and comments over several lines work.
     */
    val lua =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_LUA,
            lineRules =
                listOf(
                    Regex("""^(?<comment>#!.*)"""),
                    Regex("""(?<=\w|\w )(?<annotation><(?:const|close)>)"""),
                    Regex("""(?<type>::[A-Za-z_]\w*+::)"""),
                ),
        )

    /** Clojure. Keywords (`:kw`, lexed as preprocessor, and `::kw`) are annotations, `nil` a keyword. */
    val clojure =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_CLOJURE,
            keywords =
                words(
                    """
                    nil ns defn- defprotocol defrecord deftype defonce try catch finally declare letfn extend-type
                    extend-protocol reify proxy defstruct
                    """,
                ),
            builtins =
                words(
                    """
                    println print prn pr swap! reset! atom future doall dorun ->> cond-> cond->> as-> some-> some->>
                    require use
                    """,
                ),
            tokenStyles =
                mapOf(TokenTypes.PREPROCESSOR to TokenStyle.ANNOTATION, TokenTypes.DATA_TYPE to TokenStyle.KEYWORD),
            lineRules = listOf(Regex("""(?<![\w:])(?<annotation>::[^\s()\[\]{}",;]++)""")),
        )

    /**
     * Common Lisp, Emacs Lisp, Scheme and Racket on the one Lisp lexer. The lexer lacks `define`, `setq` and most
     * special forms; keywords (`:key`) are annotations, and `#t`, `#f`, `#lang` keywords. The keyword and builtin
     * lists are the union of the dialects: a word only one has (`list` as builtin in Scheme, `match` in Common
     * Lisp) is styled in all. Known gap: the lexer splits `set!` and `let*` at the `!` and `*`.
     */
    val lisp =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_LISP,
            keywords =
                words(
                    """
                    define setq setf begin quote letrec else nil struct module match for and or not
                    """,
                ),
            builtins =
                words(
                    """
                    car cdr cons list format print display newline apply map mapcar funcall values eval load null
                    eq equal
                    """,
                ),
            lineRules =
                listOf(
                    Regex("""(?<![\w#])(?<keyword>#(?:t|f|true|false|lang|reader))(?!\w)"""),
                    Regex("""(?<![\w:])(?<annotation>:[A-Za-z][\w-]*+)"""),
                ),
        )

    /**
     * Tcl. Variables (`$name`, `${name}`, `$arr(key)`, `$::ns::name`) are annotations, also in double-quoted
     * strings, where Tcl substitutes them.
     */
    val tcl =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_TCL,
            keywords = words("variable namespace try finally throw yield eq ne in ni package require provide"),
            builtins = words("dict coroutine vwait clock binary"),
            lineRules =
                listOf(
                    Regex(
                        """(?<![\\$\w])(?<annotation>[$](?:\{[^{}\n]*+}|(?:::)?+\w++(?:::\w++)*+(?:\([^()\n]*+\))?+))""",
                    ),
                ),
            stringStyles = setOf(TokenStyle.ANNOTATION),
        )

    private const val BAT_WORD_START = """(?<![\w%!:./\\-])"""
    private const val BAT_COMMAND_START = """(?i)(?:^|[(&|])[ \t]*+@?"""

    /**
     * Windows batch files. The lexer takes `SetLocal` and `else` for plain words and knows few commands, so rules
     * make these keywords and commands (`title`, `findstr`, `robocopy`) builtins in any case. Variables (`%NAME%`,
     * `%~dp0`, `%1`, `%%i`, `%%~nxf`, `!NAME!`) are annotations, also in quotes, where nothing else is styled; labels (`:main` and the target of `goto`
     * and `call`) types; `::` and `REM` in any case comments. Known gap: the arguments of `echo` are styled like
     * code (`echo not found`).
     */
    val batch =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_WINDOWS_BATCH,
            tokenStyles =
                mapOf(
                    TokenTypes.VARIABLE to TokenStyle.ANNOTATION,
                    TokenTypes.PREPROCESSOR to TokenStyle.TYPE,
                ),
            lineRules =
                listOf(
                    Regex("""^[ \t]*+(?<type>:(?!:)[^\s:&|<>()"]++)"""),
                    Regex("""(?i)(?<![\w%!])(?:goto|call)[ \t]++(?<type>:[^\s&|<>()"]++)"""),
                    Regex("""(?i)(?<![\w%!])goto[ \t]++(?<type>[A-Za-z_][^\s&|<>()"]*+)"""),
                    Regex(
                        """(?<annotation>%(?:[A-Za-z_][^%\s]*+%|~?[A-Za-z${'$'}:]*+[\d*]|[\d*]|%(?:~[A-Za-z${'$'}:]*)?[A-Za-z]))""",
                    ),
                    Regex("""(?<annotation>![A-Za-z_][^!\s]*+!)"""),
                    Regex(
                        """(?i)$BAT_WORD_START(?<keyword>else|defined|errorlevel|setlocal|endlocal|""" +
                            """(?:enable|disable)(?:delayedexpansion|extensions))(?![\w%!])""",
                    ),
                    Regex(
                        """$BAT_COMMAND_START(?<builtin>title|findstr|timeout|robocopy|taskkill|tasklist|where|""" +
                            """choice|attrib|tree|mklink|setx|clip|more)(?![\w.-])""",
                    ),
                ),
            stringStyles = setOf(TokenStyle.ANNOTATION),
        )
}
