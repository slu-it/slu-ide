package dev.sluide.converting.data

import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.resolver.Resolver
import java.util.regex.Pattern

/** The plain scalars of the YAML 1.2 core schema (ADR 0115). */
private object CoreSchema {
    val bool: Pattern = Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$")
    val nullValue: Pattern = Pattern.compile("^(?:~|null|Null|NULL|)$")

    /**
     * Unlike the core schema, a leading zero means octal (`0644`), as in YAML 1.1, go-yaml and Spring Boot; with digits
     * 8 or 9 (`089`) it is no number.
     */
    val int: Pattern = Pattern.compile("^(?:[-+]?(?:0|[1-9][0-9]*)|[-+]?0[0-7]+|0o[0-7]+|0x[0-9a-fA-F]+)$")

    /** Only with a point or an exponent: integers are [int]s, and `089` stays a string. */
    val float: Pattern =
        Pattern.compile(
            "^(?:(?=[^.eE]*[.eE])[-+]?(?:\\.[0-9]+|[0-9]+(?:\\.[0-9]*)?)(?:[eE][-+]?[0-9]+)?" +
                "|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN))$",
        )
    val merge: Pattern = Pattern.compile("^<<$")

    /** Strings a YAML 1.1 reader takes for booleans that SnakeYAML's own YAML 1.1 resolver leaves out. */
    val shortBool: Pattern = Pattern.compile("^(?:y|Y|n|N)$")
}

/**
 * Resolves plain scalars by the YAML 1.2 core schema: only `true` and `false` are booleans (so `on:` stays a key and
 * `NO` a string), no timestamps, no sexagesimal numbers; `<<` stays a merge key. Leading zeros mean octal ([CoreSchema.int]).
 */
internal class CoreSchemaResolver : Resolver() {
    override fun addImplicitResolvers() {
        addImplicitResolver(Tag.BOOL, CoreSchema.bool, "tTfF")
        addImplicitResolver(Tag.INT, CoreSchema.int, "-+0123456789")
        addImplicitResolver(Tag.FLOAT, CoreSchema.float, "-+.0123456789")
        addImplicitResolver(Tag.MERGE, CoreSchema.merge, "<")
        addImplicitResolver(Tag.NULL, CoreSchema.nullValue, "~nN\u0000")
    }
}

/**
 * Resolves a plain scalar as anything that a YAML 1.1 reader (SnakeYAML's own resolver plus `y`/`n`) or a YAML 1.2
 * core reader would not read as a string. Writing with it makes the emitter quote every string any of them would
 * misread (`yes`, `off`, `~`, `0123`, `1e3`, `12:30`, `2024-01-01`).
 */
internal class QuotingResolver : Resolver() {
    override fun addImplicitResolvers() {
        super.addImplicitResolvers()
        addImplicitResolver(Tag.BOOL, CoreSchema.shortBool, "yYnN")
        addImplicitResolver(Tag.INT, CoreSchema.int, "-+0123456789")
        addImplicitResolver(Tag.FLOAT, CoreSchema.float, "-+.0123456789")
    }
}
