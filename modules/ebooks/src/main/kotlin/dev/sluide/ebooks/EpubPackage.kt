package dev.sluide.ebooks

import org.w3c.dom.Element

/** One `item` of the OPF manifest; [href] is the resolved zip path. */
internal class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String,
    val properties: Set<String>,
)

/** The parts of the OPF that the reader needs. */
internal class PackageDocument(
    val path: String,
    val title: String?,
    val language: String?,
    val fixedLayout: Boolean,
    val manifest: Map<String, ManifestItem>,
    val spine: List<ManifestItem>,
    val tocId: String?,
) {
    val dir: String get() = EpubPaths.dirOf(path)
}

/** DRM detection and the OPF. All parsing functions throw on XML that is not well-formed. */
internal object EpubPackage {
    private val fontObfuscation = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")

    /** The full path of the first `rootfile` of `META-INF/container.xml`, or null. */
    fun rootfile(container: ByteArray): String? =
        EpubXml
            .parse(container)
            .documentElement
            .descendants("rootfile")
            .firstNotNullOfOrNull { it.getAttribute("full-path").takeIf(String::isNotBlank) }
            ?.let { EpubPaths.normalise("", it) }

    /** What `encryption.xml` says: [protected] for any algorithm but font obfuscation, [obfuscated] the font paths. */
    class Encryption(
        val protected: Boolean,
        val obfuscated: Set<String>,
    )

    fun encryption(xml: ByteArray): Encryption {
        var protected = false
        val obfuscated = mutableSetOf<String>()
        val root = EpubXml.parse(xml).documentElement
        for (data in root.descendants("EncryptedData")) {
            val algorithm =
                data
                    .descendants("EncryptionMethod")
                    .firstOrNull()
                    ?.getAttribute("Algorithm")
                    .orEmpty()
            if (algorithm in fontObfuscation) {
                data.descendants("CipherReference").forEach {
                    obfuscated +=
                        EpubPaths.normalise("", EpubPaths.decode(it.getAttribute("URI")))
                }
            } else {
                protected = true
            }
        }
        // An EncryptionMethod outside any EncryptedData counts as well.
        root.descendants("EncryptionMethod").forEach {
            if (it.getAttribute("Algorithm") !in fontObfuscation) protected = true
        }
        return Encryption(protected, obfuscated)
    }

    fun parse(
        path: String,
        opf: ByteArray,
    ): PackageDocument {
        val root = EpubXml.parse(opf).documentElement
        val dir = EpubPaths.dirOf(path)
        val metadata = root.children("metadata").firstOrNull()
        val title =
            metadata
                ?.descendants("title")
                ?.firstOrNull { it.textContent.isNotBlank() }
                ?.textContent
                ?.trim()
        val language =
            metadata
                ?.descendants(
                    "language",
                )?.firstOrNull { it.textContent.isNotBlank() }
                ?.textContent
                ?.trim()
        val fixedLayout = metadata?.descendants("meta").orEmpty().any { it.isPrePaginated() }

        val manifest = linkedMapOf<String, ManifestItem>()
        root.children("manifest").firstOrNull()?.children("item")?.forEach { item ->
            val id = item.getAttribute("id")
            val href = item.getAttribute("href")
            if (id.isNotEmpty() && href.isNotEmpty()) {
                val resolved = EpubPaths.resolve(dir, href).path
                // The media type ends up in data URIs: anything that is not a plain type makes the resource unusable.
                val declared =
                    ResourceUris.validMediaType(
                        item.getAttribute("media-type").ifBlank { EpubPaths.mediaTypeOf(resolved) },
                    )
                        ?: ""
                val properties =
                    item
                        .getAttribute("properties")
                        .split(' ')
                        .filter { it.isNotEmpty() }
                        .toSet()
                manifest[id] = ManifestItem(id, resolved, declared, properties)
            }
        }
        val spineElement = root.children("spine").firstOrNull()
        val spine = spineElement?.children("itemref").orEmpty().mapNotNull { manifest[it.getAttribute("idref")] }
        return PackageDocument(
            path,
            title,
            language,
            fixedLayout,
            manifest,
            spine,
            spineElement
                ?.getAttribute(
                    "toc",
                )?.ifBlank {
                    null
                },
        )
    }

    private fun Element.isPrePaginated(): Boolean {
        val isLayout = getAttribute("property") == "rendition:layout" || getAttribute("name") == "rendition:layout"
        val value = textContent.trim().ifEmpty { getAttribute("content") }
        return isLayout && value == "pre-paginated"
    }
}
