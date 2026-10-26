package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test

class JspHighlightingTest {
    private val jsp =
        Highlighted(
            """
            |<%@ page language="java" %>
            |<%@taglib prefix="c" uri="x" %>
            |<%-- a comment --%>
            |<% for (int i = 0; i < 3; i++) { out.println(i); } %>
            |<p><%= request.getParameter("x") %> ${'$'}{user.name} #{bean.prop}</p>
            |<c:forEach var="item" items="${'$'}{items}">
            |  <c:out value="${'$'}{item}"/>
            |</c:forEach>
            |<jsp:include page="a.jsp"/>
            |
            """.trimMargin(),
            RSyntaxHighlighter.jsp,
        )

    @Test
    fun `directives are annotations`() {
        jsp.assertExactly(TokenStyle.ANNOTATION, "<%@ page")
        jsp.assertExactly(TokenStyle.ANNOTATION, "<%@taglib")
        jsp.assertExactly(TokenStyle.STRING, "\"java\"")
    }

    @Test
    fun `scriptlets are Java`() {
        jsp.assertExactly(TokenStyle.KEYWORD, "for", wholeWord = true)
        jsp.assertExactly(TokenStyle.TYPE, "int")
        jsp.assertExactly(TokenStyle.STRING, "\"x\"")
    }

    @Test
    fun `comments`() = jsp.assertExactly(TokenStyle.COMMENT, "<%-- a comment --%>")

    @Test
    fun `EL expressions are annotations, but not inside attribute values`() {
        jsp.assertExactly(TokenStyle.ANNOTATION, "\${user.name}")
        jsp.assertExactly(TokenStyle.ANNOTATION, "#{bean.prop}")
        jsp.assertExactly(TokenStyle.STRING, "\"\${items}\"")
    }

    @Test
    fun `an unclosed EL expression runs to the line end`() {
        Highlighted("<p>\${a.b and more\n", RSyntaxHighlighter.jsp)
            .assertExactly(TokenStyle.ANNOTATION, "\${a.b and more")
    }

    @Test
    fun `taglib tags are tags`() {
        jsp.assertExactly(TokenStyle.TAG, "<c:forEach")
        jsp.assertExactly(TokenStyle.TAG, "<c:out")
        jsp.assertExactly(TokenStyle.TAG, "</c:forEach")
        jsp.assertExactly(TokenStyle.TAG, "<jsp:include")
        jsp.assertExactly(TokenStyle.ATTRIBUTE, "items")
    }

    @Test
    fun `spans are well-formed`() = jsp.assertWellFormed()
}
