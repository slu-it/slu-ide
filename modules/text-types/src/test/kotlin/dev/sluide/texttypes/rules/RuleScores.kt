package dev.sluide.texttypes.rules

import dev.sluide.texttypes.TextTypeSource
import org.junit.jupiter.params.provider.Arguments

/** The scores a rule gives [text] as `"<extension> <score>"`, comma-separated; `""` for none. */
internal fun TextTypeSource.scored(text: String): String =
    scores(text).joinToString { "${it.type.extension} ${it.score}" }

/** Cases for a `@MethodSource`: text to the expected [scored] result. */
internal fun cases(vararg cases: Pair<String, String>): List<Arguments> =
    cases.map { (text, expected) -> Arguments.of(text, expected) }
