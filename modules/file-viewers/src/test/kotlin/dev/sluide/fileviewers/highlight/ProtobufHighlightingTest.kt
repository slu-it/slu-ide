package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test

class ProtobufHighlightingTest {
    private val proto =
        Highlighted(
            """
            |edition = "2023";
            |package acme.v1;
            |import public "other.proto";
            |option (my.opt) = true;
            |// A user.
            |message User {
            |  optional int32 age = 2 [deprecated = true];
            |  map<string, Project> projects = 4;
            |  oneof contact { string email = 5; }
            |  reserved 7, 9 to 11;
            |  extensions 100 to max;
            |  string stream = 25;
            |  string map = 26;
            |  repeated group Result = 27 { required string url = 28; }
            |}
            |service Greeter {
            |  rpc Chat (stream Req) returns (stream Rep) {}
            |}
            |
            """.trimMargin(),
            RSyntaxHighlighter.protobuf,
        )

    @Test
    fun `declarations and types`() {
        for (word in listOf("package", "import", "public", "option", "message", "optional", "oneof", "reserved")) {
            proto.assertExactly(TokenStyle.KEYWORD, word, wholeWord = true)
        }
        for (word in listOf("service", "rpc", "returns")) proto.assertExactly(TokenStyle.KEYWORD, word)
        proto.assertExactly(TokenStyle.TYPE, "int32")
        proto.assertExactly(TokenStyle.TYPE, "string", occurrence = 1, wholeWord = true)
        proto.assertExactly(TokenStyle.COMMENT, "// A user.")
        proto.assertExactly(TokenStyle.STRING, "\"other.proto\"")
    }

    @Test
    fun `field numbers are numbers`() {
        proto.assertExactly(TokenStyle.NUMBER, "2", wholeWord = true)
        proto.assertExactly(TokenStyle.NUMBER, "25", wholeWord = true)
    }

    @Test
    fun `edition, map, stream, extensions and group are styled in their place`() {
        proto.assertExactly(TokenStyle.KEYWORD, "edition")
        proto.assertExactly(TokenStyle.TYPE, "map")
        proto.assertExactly(TokenStyle.KEYWORD, "extensions")
        proto.assertExactly(TokenStyle.KEYWORD, "group")
        proto.assertExactly(TokenStyle.KEYWORD, "stream", occurrence = 1)
        proto.assertExactly(TokenStyle.KEYWORD, "stream", occurrence = 2)
    }

    @Test
    fun `fields named like contextual words stay plain`() {
        proto.assertPlain("stream", occurrence = 0)
        proto.assertPlain("map", occurrence = 1)
        val edition = Highlighted("  string edition = 1;\n", RSyntaxHighlighter.protobuf)
        edition.assertPlain("edition")
    }

    @Test
    fun `each string ends at its own quote`() {
        val strings =
            Highlighted(
                """
                |reserved "FOO", "BAR";
                |option (google.api.http) = { post: "/v1" body: "*" };
                |string s = 1 [default = "x"]; // "y" here
                |string t = 2 [default = "a\"b"];
                |
                """.trimMargin(),
                RSyntaxHighlighter.protobuf,
            )
        for (text in listOf("\"FOO\"", "\"BAR\"", "\"/v1\"", "\"*\"", "\"x\"", "\"a\\\"b\"")) {
            strings.assertExactly(TokenStyle.STRING, text)
        }
        strings.assertPlain("body")
        strings.assertExactly(TokenStyle.COMMENT, "// \"y\" here")
        strings.assertExactly(TokenStyle.KEYWORD, "reserved")
    }

    @Test
    fun `spans are well-formed`() = proto.assertWellFormed()
}
