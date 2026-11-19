package dev.sluide.texttypes.rules

import dev.sluide.texttypes.TextTypeSource
import dev.sluide.texttypes.TextTypes
import dev.sluide.texttypes.TypeScore

/**
 * Python, JavaScript and TypeScript by keyword heuristics, each scored by the independent signs found. TypeScript is
 * JavaScript with types: a text with a sign only TypeScript has scores the JavaScript signs plus those as TypeScript,
 * and not as JavaScript.
 */
internal object ScriptRules : TextTypeSource {
    private val python =
        listOf(
            // `def f(…):` up to the line's last `:`, which a Python YAML-parsable snippet has as well (YAML gives 80).
            Signal("""^[ \t]*+(?:async[ \t]++)?def[ \t]++\w++[ \t]*+\([^\n]*:[ \t]*+$""", 60),
            // A class named in CapWords, as Python's are (prose "class schedule:").
            Signal("""^[ \t]*+class[ \t]++[A-Z_]\w*+[ \t]*+(?:\([^\n]*\))?[ \t]*+:[ \t]*+$""", 50),
            Signal("""^[ \t]*+from[ \t]++[\w.]++[ \t]++import[ \t]++\S""", 40),
            Signal("""^import[ \t]++\w[\w \t,]*+$""", 15),
            Signal("""^if[ \t]++__name__[ \t]*+==[ \t]*+['"]__main__['"][ \t]*+:""", 45),
            Signal("""\bself\.\w""", 20),
            // A line ending in `:` and a more indented line right after it.
            Signal("""^([ \t]*+)\S[^\n]*:[ \t]*+\r?\n\1[ \t]++\S""", 25),
            // Control lines of Python's shape: `with` a call right after it or an `as`, `for` up to three names `in`.
            Signal(
                """^[ \t]*+(?:elif[ \t]|except\b|try:|finally:""" +
                    """|with[ \t]++(?=[\w.]++\(|[^\n]*[ \t]as[ \t])[^\n]*:[ \t]*+$""" +
                    """|for[ \t]++\w++(?:[ \t]*+,[ \t]*+\w++){0,2}[ \t]++in[ \t][^\n]*:[ \t]*+$)""",
                25,
            ),
            Signal("""\b(?:None|True|False)\b""", 10),
            Signal("""^[ \t]*+print\(""", 10),
            Signal("""\bf"[^"\n{]*+\{""", 20),
        )

    private val javascript =
        listOf(
            // A declaration's shape: a name followed by `=`, `:`, `;` or `,`, or a destructuring `{` or `[`.
            Signal("""^[ \t]*+(?:export[ \t]++)?(?:const|let)[ \t]++(?:(?!val\b)[\w$]++[ \t]*+[=:;,]|[{\[])""", 20),
            Signal("""^[ \t]*+(?:export[ \t]++)?(?:default[ \t]++)?(?:async[ \t]++)?function\b""", 30),
            Signal("""=>""", 15),
            Signal("""^[ \t]*+export[ \t]++(?:default|function|const|let|class|async|\{)""", 20),
            Signal("""\brequire\([ \t]*+['"]""", 30),
            Signal("""\bmodule\.exports\b|^[ \t]*+exports\.\w++[ \t]*+=""", 30),
            Signal("""\bconsole\.(?:log|error|warn|info|debug)\(""", 25),
            Signal("""^[ \t]*+import[ \t]++(?:[\w*{][^\n]*?[ \t]from[ \t]++)?['"]""", 25),
            Signal("""===|!==""", 15),
            Signal("""\b(?:document|window)\.\w""", 15),
            Signal("""^[ \t]*+var[ \t]++\w++[ \t]*+=[^\n]*;[ \t]*+$""", 10),
            Signal("""\bprocess\.(?:argv|env|exit)\b""", 20),
        )

    private val typescriptOnly =
        listOf(
            Signal(
                """^[ \t]*+(?:export[ \t]++)?(?:declare[ \t]++)?interface[ \t]++\w++(?:<[^>\n]*+>)?[ \t]*+""" +
                    """(?:extends[ \t]++[^{\n]++)?\{""",
                35,
            ),
            Signal("""^[ \t]*+(?:export[ \t]++)?(?:declare[ \t]++)?type[ \t]++\w++(?:<[^>\n]*+>)?[ \t]*+=""", 35),
            // A type annotation of code shape: followed by punctuation or the line's end, not by prose words.
            Signal(
                """[\w)?][ \t]*+:[ \t]*+(?:string|number|boolean|void|any|unknown|never|bigint)(?:\[])?[ \t]*+""" +
                    """(?:[,;)=|{>]|$)""",
                30,
            ),
            Signal("""\bas[ \t]++const\b""", 25),
            Signal("""^[ \t]*+(?:export[ \t]++)?(?:declare[ \t]++)?(?:const[ \t]++)?enum[ \t]++\w++[ \t]*+\{""", 25),
            // A typed class member or constructor parameter: `private readonly http: HttpClient)`.
            Signal(
                """\b(?:private|public|protected|readonly)[ \t]++(?:readonly[ \t]++)?\w++[ \t]*+[?!]?:[ \t]*+""" +
                    """[\w.<>\[\]|]++[ \t]*+(?:[,;)=]|$)""",
                25,
            ),
            Signal("""\b(?:Promise|Record|Partial|Readonly|Omit|Pick)<""", 20),
            Signal("""^[ \t]*+(?:import|export)[ \t]++type[ \t]""", 25),
            Signal("""^[ \t]*+(?:readonly[ \t]++)?\w++\?:[ \t]*+\S""", 20),
            // A function with a typed parameter: up to the first `:` or `)`, so that the scan stays linear.
            Signal("""\bfunction[ \t]*+\*?[ \t]*+\w*+[ \t]*+(?:<[^>(\n]*+>)?[ \t]*+\([^):\n]*+:""", 30),
        )

    override fun scores(text: String): List<TypeScore> {
        val js = weigh(text, javascript)
        val ts = weigh(text, typescriptOnly)
        val script = if (ts > 0) heuristic(TextTypes.TYPESCRIPT, js + ts) else heuristic(TextTypes.JAVASCRIPT, js)
        return heuristic(TextTypes.PYTHON, weigh(text, python)) + script
    }
}
