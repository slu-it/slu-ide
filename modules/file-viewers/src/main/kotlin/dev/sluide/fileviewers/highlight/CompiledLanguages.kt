package dev.sluide.fileviewers.highlight

import org.fife.ui.rsyntaxtextarea.SyntaxConstants

/**
 * The [RSyntaxHighlighter]s of compiled languages: C, C++, C#, Go (and its `go.mod`), Rust, D, Scala, Dart, Visual
 * Basic, Pascal, Fortran, assembler and VHDL.
 */
internal object CompiledLanguages {
    /** The whitespace-separated [words] in lower case, upper case and capitalized, for case-insensitive languages. */
    private fun caseVariants(words: String): Set<String> =
        words.trim().split(Regex("""\s+""")).flatMapTo(HashSet()) {
            listOf(it.lowercase(), it.uppercase(), it.lowercase().replaceFirstChar(Char::uppercaseChar))
        }

    private fun words(words: String): Set<String> = words.trim().split(Regex("""\s+""")).toSet()

    /**
     * The `'` of a digit separator (`1'000'000`, C23 and C++14) that the lexer takes for a character literal, which
     * then eats the rest of the line. The quote is matched first and the lookbehind only reaches back over the
     * number's own characters from it, so the lookbehind runs at quotes only, not at every position.
     */
    private const val DIGIT_SEPARATOR = """'(?<=\b[0-9][0-9a-fA-FxXbB']{0,40}')(?=[0-9a-fA-F])"""

    /**
     * The C lexer misses the keywords and types of C11 and C23 (`bool`, `nullptr`, `constexpr`, `_Generic`). `.h`
     * headers open as C++, whose lexer reads C as well but also knows `class`, `namespace` and `template`.
     */
    val c =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_C,
            keywords =
                words(
                    """
                    true false nullptr NULL constexpr static_assert alignas alignof typeof typeof_unqual _Generic
                    _Atomic _Noreturn _Static_assert _Thread_local thread_local inline restrict _Alignas _Alignof
                    """,
                ),
            types =
                words(
                    """
                    bool _Bool _Complex FILE uint8_t uint16_t uint32_t uint64_t int8_t int16_t int32_t int64_t
                    intptr_t uintptr_t ptrdiff_t
                    """,
                ),
            lexerMasks = listOf(Regex(DIGIT_SEPARATOR)),
        )

    /**
     * The C++ lexer misses the keywords since C++11/20 (`constexpr`, `concept`, `requires`, `co_await`, `override`,
     * `noexcept`), `std::` types are made types, digit separators no longer start character literals. Known gap:
     * raw strings (`R"x(…)x"`) are lexed as ordinary strings.
     */
    val cpp =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS,
            keywords =
                words(
                    """
                    constexpr consteval constinit nullptr concept requires co_await co_yield co_return override final
                    noexcept decltype static_assert thread_local NULL
                    """,
                ),
            types =
                words(
                    """
                    bool char8_t char16_t char32_t nullptr_t uint8_t uint16_t uint32_t uint64_t int8_t int16_t int32_t
                    int64_t intptr_t uintptr_t ptrdiff_t
                    """,
                ),
            lineRules =
                listOf(
                    Regex(
                        """(?<![\w:])std::(?<type>(?:string|string_view|wstring|vector|array|map|unordered_map|set|""" +
                            """unordered_set|list|deque|pair|tuple|optional|variant|any|span|function|""" +
                            """unique_ptr|shared_ptr|weak_ptr|ostream|istream|thread|mutex|size_t|ptrdiff_t)\b)""",
                    ),
                ),
            lexerMasks = listOf(Regex(DIGIT_SEPARATOR)),
        )

    /**
     * The C# lexer misses the contextual keywords (`required`, `var`, `async`, `await`, `nameof`, `when`, `with`,
     * `global`, `scoped`) and raw strings (`"""…"""`). `file` and `record` only count before a type declaration, and
     * `get`, `set` and `init` only when `;`, `{` or `=>` follows. The `$`/`@` of interpolated strings is part of the
     * string, attributes on their own line are annotations.
     */
    val csharp =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_CSHARP,
            keywords =
                words(
                    """
                    required var async await nameof when with global scoped yield partial not and or
                    """,
                ),
            types = words("dynamic"),
            rawStringDelimiter = "\"\"\"",
            lineRules =
                listOf(
                    Regex("""(?<![\w"])(?<string>\$@?|@\$)(?=")"""),
                    Regex(
                        """(?<![\w.])(?<keyword>file)(?=\s++(?:class|struct|interface|record|enum|static|sealed|""" +
                            """abstract|readonly|partial)\b)""",
                    ),
                    Regex(
                        """(?<![\w.])(?<keyword>record)(?=\s++(?:(?:class|struct)\s++)?[A-Z]\w*+\s*+(?:[({<:;]|$))""",
                    ),
                    Regex(
                        """(?<![\w.])(?=get|set|init)(?<![=,(][ \t]{0,20})(?<keyword>get|set|init)(?=\s*+(?:[;{]|=>))""",
                    ),
                    Regex("""^[ \t]*+(?<annotation>\[[A-Za-z_][\w.:, \t]*+\])"""),
                    Regex("""^[ \t]*+(?<annotation>\[[A-Za-z_][\w.:]*+)(?=\()"""),
                    Regex("""^[ \t]*+\[[A-Za-z_][^\n]*(?<=\))(?<annotation>\])[ \t]*+$"""),
                ),
        )

    /**
     * The Go lexer misses `any`, `comparable`, `error` and `iota`, and reports the backtick of a raw string as an
     * error token, which [RSyntaxHighlighter.rawStringDelimiter] takes as the start of a string reaching to the
     * closing backtick over several lines.
     */
    val go =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_GO,
            keywords = words("iota"),
            types = words("any comparable error"),
            rawStringDelimiter = "`",
        )

    /**
     * `go.mod` and `go.work` have no lexer: line rules style the directives, versions (`v1.2.3`, `1.22`, `go1.22.1`),
     * `=>` and `//` comments. Directives only count at the start of a line, so `golang.org/x/text` is no `go`.
     */
    val goMod =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_NONE,
            lineRules =
                listOf(
                    Regex("""(?<![:\w])(?<comment>//[^\n]*+)"""),
                    Regex(
                        """^[ \t]*+(?<keyword>module|go|require|replace|exclude|retract|toolchain|use|godebug|""" +
                            """ignore|tool)(?=[ \t(]|$)""",
                    ),
                    Regex("""(?<![\w.\-/])(?<number>v\d[\w.+\-]*+)"""),
                    Regex("""(?<![\w.\-/])(?<number>(?:go)?\d++(?:\.\d++)*+(?:rc\d++|beta\d++)?)(?![\w.\-/])"""),
                    Regex("""(?<operator>=>)"""),
                ),
        )

    /**
     * The Rust lexer takes the `'a` of a lifetime for a character literal that eats the rest of the line, so
     * lifetimes are hidden from it and styled as types. It misses `async`, `await` and `macro_rules!`; attributes
     * (`#[derive(Debug)]`, `#![allow(…)]`) are annotations, macro calls (`println!`) builtins. Known gap: raw strings
     * (`r#"…"#`) over several lines; `r#"…"#` on one line is fine.
     */
    val rust =
        RSyntaxHighlighter(
            "text/rust",
            keywords = words("async await try Some None Ok Err"),
            types = words("Self String Vec Option Result Box"),
            stringPrefixes = words("b br"),
            lineRules =
                listOf(
                    Regex("""(?<keyword>\bmacro_rules!)"""),
                    Regex("""(?<annotation>#!?\[[^\]\n"#\[]*+\])"""),
                    Regex("""(?<annotation>#!?\[[A-Za-z_][\w:]*+)"""),
                    Regex("""^[ \t]*+#!?\[[^\n]*(?<annotation>\])[ \t]*+$"""),
                    Regex("""(?<type>'[A-Za-z_]\w*+)(?!')"""),
                    Regex("""(?<builtin>\b[A-Za-z_]\w*+!)(?=[(\[{])"""),
                ),
            lexerMasks = listOf(Regex("""'(?=[A-Za-z_]\w*+(?!'))""")),
        )

    /** D: the lexer is fine as it is. */
    val d = RSyntaxHighlighter(SyntaxConstants.SYNTAX_STYLE_D)

    /**
     * Scala 3 keywords (`given`, `using`, `enum`, `extension`, `then`, `export`, `opaque`, `derives`, `transparent`,
     * `inline`) and the common types are added; the `s`, `f` and `raw` of interpolated strings belong to the string,
     * and `"""` strings may span several lines.
     */
    val scala =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_SCALA,
            keywords = words("given using enum extension then export opaque derives transparent inline"),
            types =
                words(
                    """
                    Int Long Double Float Char Byte Short Boolean Unit Any AnyRef AnyVal Nothing String Option Seq
                    List Map Set Vector Either Try Future
                    """,
                ),
            stringPrefixes = words("s f raw"),
            rawStringDelimiter = "\"\"\"",
        )

    /**
     * Dart 3: `sealed`, `mixin`, `late`, `required`, `when`, `await`, `async`, `enum`, `extension`, `as`; `base` only
     * before `class` or `mixin`.
     */
    val dart =
        RSyntaxHighlighter(
            "text/dart",
            keywords =
                words(
                    """
                    late required sealed mixin when await async yield enum extension as with covariant external
                    rethrow
                    """,
                ),
            types = words("dynamic Future Stream Record Never"),
            stringPrefixes = words("r"),
            lineRules = listOf(Regex("""(?<![\w.])(?<keyword>base)(?=\s++(?:class|mixin)\b)""")),
        )

    /**
     * Visual Basic. Keywords match in any case; `Async`, `Await`, `Iterator` and `Yield` and `REM` comments are added.
     * The lexer would take a `'` or `"` in a `REM` comment for a comment or string start, so the comment is hidden
     * from it, with the same anchors as the line rule that styles it (the start of a statement).
     */
    val visualBasic =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_VISUAL_BASIC,
            keywords = caseVariants("async await iterator yield"),
            lineRules = listOf(Regex("""(?i)(?:^[ \t]*+|:[ \t]*+)(?<comment>rem(?![\w])[^\n]*+)""")),
            lexerMasks = listOf(Regex("""(?i)(?:^|:)[ \t]{0,100}+rem(?![\w])[^\n]*+""", RegexOption.MULTILINE)),
        )

    /**
     * The Delphi lexer only knows lower case keywords, but Pascal is case-insensitive, so every keyword and type is
     * given in lower case, upper case and capitalized. Hexadecimal numbers (`$1F`) are numbers, but not the `$A` of a
     * directive like `{$A+}`.
     */
    val pascal =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_DELPHI,
            keywords =
                caseVariants(
                    """
                    program unit uses interface implementation type var const begin end if then else for to downto do
                    while repeat until case of record class object procedure function constructor destructor
                    inherited try except finally raise nil true false and or not xor div mod shl shr in is as with
                    private public protected published property override virtual overload abstract forward array set
                    packed label goto inline out initialization finalization external stdcall cdecl
                    threadvar resourcestring reintroduce
                    """,
                ),
            types =
                words("AnsiString WideString UnicodeString TObject SmallInt ShortInt LongInt LongWord UInt64") +
                    caseVariants(
                        """
                    integer boolean char byte word cardinal int64 uint64 smallint shortint longint longword real
                    double single extended currency pointer string
                    """,
                    ),
            lineRules = listOf(Regex("""(?<![\w\x24{])(?<number>\$[0-9A-Fa-f]++)(?![\w])""")),
        )

    /**
     * Fortran. Keywords are given in all cases for the ones the lexer misses (`intent`, `pointer`, `type`, …); `in`,
     * `out`, `block` and `target` only in `intent(in)`, as a `block` statement and as `, target ::`. Numbers (`1`,
     * `1.5d0`, `10_dp`) and the dotted operators (`.and.`, `.not.`) get line rules. Known gaps: fixed-form comments
     * with a `C` or `*` in the first column; `target` in `f(a, target, b)` is styled.
     */
    val fortran =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_FORTRAN,
            keywords =
                caseVariants(
                    """
                    pointer intent interface type public private only submodule associate concurrent
                    """,
                ),
            lineRules =
                listOf(
                    Regex("""(?i)(?<operator>\.(?:not|and|or|eqv|neqv|eq|ne|lt|le|gt|ge)\.)"""),
                    Regex("""(?i)\bintent\s*+\(\s*+(?<keyword>in\s++out|inout|in|out)\s*+\)"""),
                    Regex("""(?i)^[ \t]*+(?:\w++[ \t]*+:[ \t]*+)?(?<keyword>block)(?=[ \t]*+(?:!|$)|[ \t]++data\b)"""),
                    Regex("""(?i)^[ \t]*+end[ \t]++(?<keyword>block)\b"""),
                    Regex("""(?i),[ \t]*+(?<keyword>target)(?=[ \t]*+(?:::|,))"""),
                    Regex(
                        """(?<![\w.])(?<number>\d++(?:\.\d*+)?(?:[eEdD][+-]?\d++)?(?:_[A-Za-z0-9_]++)?)(?!\w)""",
                    ),
                ),
        )

    /**
     * x86 assembler (NASM, MASM, GAS in Intel syntax). The lexer styles the label before its `:` like a keyword, does
     * not know registers and does not read hexadecimal numbers (`0x1F`, `1Fh`, `$1F`), so line rules do. Known gap: the
     * `#` comments of AT&T syntax.
     */
    val assembler =
        RSyntaxHighlighter(
            SyntaxConstants.SYNTAX_STYLE_ASSEMBLER_X86,
            keywords = caseVariants("syscall"),
            lineRules =
                listOf(
                    Regex("""^[ \t]*+(?<type>[A-Za-z_.$?@][\w.$?@]*+)(?=:)"""),
                    Regex(
                        """(?i)(?<![\w.$@])(?<builtin>[re]?(?:[abcd]x|[sd]i|[sb]p|ip)|[abcd][lh]|[sd]il|[sb]pl|""" +
                            """r(?:[89]|1[0-5])[dwb]?|[xyz]mm(?:\d|[12]\d|3[01])|[cdefgs]s)(?!\w)""",
                    ),
                    Regex(
                        """(?i)(?<![\w.$])(?<number>0x[0-9a-f]++|\$[0-9a-f]++|[0-9][0-9a-f]*+h|0b[01]++|\d++)""" +
                            """(?!\w)""",
                    ),
                ),
        )

    /** VHDL (case-insensitive); the lexer misses `unsigned`, `signed`, `string`, `time` and `falling_edge`. */
    val vhdl =
        RSyntaxHighlighter(
            "text/vhdl",
            keywords = caseVariants("falling_edge"),
            types = caseVariants("unsigned signed string time"),
        )
}
