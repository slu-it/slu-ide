package dev.sluide.httpclient.curl

/** The curl options [CurlReader] knows: long names of short ones, which take a value, which go without a note. */
internal object CurlOptions {
    val SHORT =
        mapOf(
            'X' to "--request",
            'H' to "--header",
            'd' to "--data",
            'F' to "--form",
            'u' to "--user",
            'b' to "--cookie",
            'A' to "--user-agent",
            'e' to "--referer",
            'G' to "--get",
            'I' to "--head",
            'L' to "--location",
            'k' to "--insecure",
            'm' to "--max-time",
            'T' to "--upload-file",
            'o' to "--output",
            'w' to "--write-out",
            's' to "--silent",
            'S' to "--show-error",
            'v' to "--verbose",
            'i' to "--include",
            'f' to "--fail",
            'O' to "--remote-name",
            '#' to "--progress-bar",
            'N' to "--no-buffer",
            'g' to "--globoff",
        )

    /** Short options that take a value: the mapped ones and others that are ignored. */
    val SHORT_WITH_VALUE = "XHdFubAemTowcCDEKrUxyYztQP".toSet()

    /** Long options that take a value: the mapped ones, then ignored ones whose value must not be taken for the URL. */
    val LONG_WITH_VALUE =
        options(
            "request header data data-ascii data-binary data-raw data-urlencode json form form-string user",
            "oauth2-bearer cookie user-agent referer url max-time connect-timeout upload-file output write-out",
            "cookie-jar continue-at dump-header cert key cacert capath pass config range proxy-user proxy",
            "speed-limit speed-time time-cond telnet-option quote resolve connect-to interface max-redirs retry",
            "retry-delay retry-max-time limit-rate stderr trace trace-ascii unix-socket abstract-unix-socket proto",
            "proto-redir cert-type key-type ciphers local-port dns-servers expect100-timeout keepalive-time",
            "max-filesize aws-sigv4 proxy-header request-target output-dir variable hsts alt-svc etag-save",
            "etag-compare happy-eyeballs-timeout-ms create-file-mode noproxy preproxy login-options sasl-authzid",
            "tlsuser tlspassword tls-max",
        )

    /** Options that only change what curl prints or where it writes: dropped without a note. */
    val DROPPED =
        options(
            "silent show-error output remote-name verbose include fail fail-with-body write-out progress-bar",
            "no-progress-meter no-buffer globoff compressed remote-name-all remote-header-name",
        )

    private fun options(vararg names: String): Set<String> = names.flatMap { it.split(' ') }.map { "--$it" }.toSet()
}
