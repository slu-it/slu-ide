package dev.sluide.previews.yaml

/** YAML texts covering what the preview lays out; [YamlTextTest] checks that each reads back the same formatted. */
internal object YamlFixtures {
    val kubernetes =
        """
        apiVersion: v1
        kind: Pod  # comment
        metadata: {name: web, labels: {app: x}}
        spec:
          containers:
          - name: web
            image: "nginx:1"
            ports: [80, 443]
          - image: x
            env: []
            args:
              - a
        ---
        # c
        kind: Service
        """.trimIndent() + "\n"

    val anchors =
        """
        base: &base
          a: 1
        derived:
          <<: *base
          b: !!str 2
          c: !Ref Foo
          d: ~
          e: TRUE
          f: 0x1F
          g: .inf
          h: plain text
        """.trimIndent()

    val blocks =
        "lit: |\n  line1\n    deeper\n\n  line3\nstrip: |-\n  x\n\n\nkeep: |+\n  y\n\n\n" +
            "fold: >\n  a\n  b\n\n  c\nind: |2\n    lead\n  x\nnext: 1\n"

    val multiline = "plain: a\n  b\n\n  c\nq: \"x\n    y \\\n   z\"\ns: 'p\n  q'\n"

    val complexKeys =
        "? [a, b]\n: 1\n? {x: 1}\n: 2\n*a : 3\nm: {? \"multi\n  key\": 4, ? plain\n  key: [x]}\n? |\n  blk\n: 6\n" +
            "? a: 1\n  b: 2\n: c: 3\n  d: 4\n? - x\n: - y\n? &k !t\n: &v\n"

    val directives = "%YAML 1.1\n%TAG !e! tag:e.com,2000:\n--- !e!foo\na: !e!bar 1\n...\n%YAML 1.1\n---\n- x\n"

    val emptyDocuments = "---\n---\na: 1\n---\n"

    val sequences =
        "- 😀 x: \"😀\"\n  y: |\n    😀 z\n- - a\n  - b\n- []\n- {}\n- &x\n  k: v\n" +
            "- !!map\n  k: v\n- k: v\n"

    val duplicateKeys = "a: 1\na: 2\n"

    val rootScalar = "top\n  more\n"

    val rootBlock = "--- |\n  root block\n"

    val rootAnchor = "--- &r\na: 1\n"

    val flow = "[a, [b, c], {d: e}, {f: [g]}, {? x}]\n"

    val properties = "k: !!null\nj: &a\nl: !t &b x\nm:\n  - |2-\n     sp\n    t\nn: !<tag:x.com,2000:y> z\n"

    val all =
        listOf(
            kubernetes,
            anchors,
            blocks,
            multiline,
            complexKeys,
            directives,
            emptyDocuments,
            sequences,
            duplicateKeys,
            rootScalar,
            rootBlock,
            rootAnchor,
            flow,
            properties,
        )
}
