package dev.sluide.httpclient.curl

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals

class RequestInsertionTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `inserts requests after the request of the line, separated by ###`(
        case: String,
        text: String,
        line: Int,
        requests: String,
        expected: String,
        start: Int,
    ) {
        assertEquals(RequestInsertion(expected, start), insertRequests(text, line, requests))
    }

    companion object {
        @JvmStatic
        fun cases() =
            listOf(
                Arguments.of("empty file", "", 0, "GET https://n\n", "GET https://n\n", 0),
                Arguments.of(
                    "in a request, before the next",
                    "GET https://a\nAccept: x\n\n\n###\nGET https://b\n",
                    0,
                    "GET https://n\n",
                    "GET https://a\nAccept: x\n\n###\nGET https://n\n\n###\nGET https://b\n",
                    4,
                ),
                Arguments.of(
                    "after the last request",
                    "GET https://a\n",
                    0,
                    "### New\nGET https://n\n",
                    "GET https://a\n\n### New\nGET https://n\n",
                    2,
                ),
                Arguments.of(
                    "in a part without a request, before text that is no separator",
                    "@a = 1\n\n@b = 2",
                    1,
                    "GET https://n\n",
                    "@a = 1\n\n###\nGET https://n\n\n###\n@b = 2",
                    3,
                ),
                Arguments.of(
                    "at the top of a part without a request",
                    "# only a comment\n",
                    0,
                    "GET https://n\n\n###\nGET https://m\n",
                    "GET https://n\n\n###\nGET https://m\n\n###\n# only a comment\n",
                    0,
                ),
            )
    }
}
