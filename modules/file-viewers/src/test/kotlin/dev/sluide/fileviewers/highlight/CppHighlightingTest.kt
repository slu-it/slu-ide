package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class CppHighlightingTest {
    private val cpp =
        Highlighted(
            """
            |#include <vector>
            |template <typename T> concept Num = requires(T t) { t + t; };
            |constexpr auto f(int x) noexcept -> int { return x; }
            |consteval int g() { return 1; }
            |constinit int h = 2;
            |struct B final : Base { void run() override; };
            |task<int> co() { co_await x; co_yield 1; co_return 2; }
            |std::vector<std::string> v; std::unique_ptr<int> p = nullptr; std::move(v);
            |decltype(auto) q = 1'000'000; char c = 'a'; // end
            |auto s = "it's";
            |namespace app { class Widget; }
            |
            """.trimMargin(),
            RSyntaxHighlighter.cpp,
        )

    @Test
    fun `keywords since C++11`() {
        val words =
            listOf(
                "constexpr",
                "consteval",
                "constinit",
                "nullptr",
                "concept",
                "requires",
                "co_await",
                "co_yield",
                "co_return",
                "override",
                "final",
                "noexcept",
                "decltype",
                "auto",
                "template",
                "typename",
                "namespace",
                "class",
            )
        for (word in words) assertEquals(setOf(TokenStyle.KEYWORD), cpp.stylesOf(word), word)
    }

    @Test
    fun `std types are types, other std names are not`() {
        assertEquals(setOf(TokenStyle.TYPE), cpp.stylesOf("vector", occurrence = 1))
        assertEquals(setOf(TokenStyle.TYPE), cpp.stylesOf("string"))
        assertEquals(setOf(TokenStyle.TYPE), cpp.stylesOf("unique_ptr"))
        assertEquals(emptySet(), cpp.stylesOf("move"))
    }

    @Test
    fun `digit separators do not start character literals`() {
        assertEquals(setOf(TokenStyle.NUMBER), cpp.stylesOf("1'000'000"))
        cpp.assertExactly(TokenStyle.STRING, "'a'")
        cpp.assertExactly(TokenStyle.COMMENT, "// end")
        cpp.assertExactly(TokenStyle.STRING, "\"it's\"")
    }
}
