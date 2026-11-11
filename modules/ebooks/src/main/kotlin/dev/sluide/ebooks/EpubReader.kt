package dev.sluide.ebooks

import java.io.IOException
import java.nio.file.Path
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.io.path.nameWithoutExtension

/** Reads an EPUB 2 or 3 file into an [EpubBook] (ADR 0165). Bad input gives [EpubResult.Invalid], never an exception. */
internal object EpubReader {
    fun read(
        file: Path,
        limits: EpubLimits = EpubLimits(),
    ): EpubResult =
        try {
            ZipFile(file.toFile()).use { EpubArchive(it, limits).use { archive -> readArchive(file, archive) } }
        } catch (_: TooLargeException) {
            EpubResult.Invalid("too large")
        } catch (_: ZipException) {
            EpubResult.Invalid("not a zip file")
        } catch (e: IOException) {
            EpubResult.Invalid("cannot read the file")
        }

    private class Unreadable(
        path: String,
    ) : Exception(path)

    private fun readArchive(
        file: Path,
        archive: EpubArchive,
    ): EpubResult {
        archive.checkLimits()
        if (archive.has("META-INF/rights.xml")) return EpubResult.Drm
        return try {
            val encryptionPath = "META-INF/encryption.xml"
            val encryption =
                archive
                    .bytes(
                        encryptionPath,
                    )?.let { parseOrFail(encryptionPath) { EpubPackage.encryption(it) } }
            if (encryption?.protected == true) return EpubResult.Drm
            val container =
                archive.bytes("META-INF/container.xml") ?: return EpubResult.Invalid("no META-INF/container.xml")
            val opfPath = parseOrFail("META-INF/container.xml") { EpubPackage.rootfile(container) }
            val opfBytes = opfPath?.let { archive.bytes(it) } ?: return EpubResult.Invalid("package file missing")
            val pkg = parseOrFail(opfPath) { EpubPackage.parse(opfPath, opfBytes) }
            assemble(file, archive, pkg, encryption?.obfuscated.orEmpty())
        } catch (e: Unreadable) {
            EpubResult.Invalid("unreadable XML in ${e.message}")
        }
    }

    private fun <T> parseOrFail(
        path: String,
        parse: () -> T,
    ): T =
        try {
            parse()
        } catch (e: org.xml.sax.SAXException) {
            throw Unreadable(path)
        }

    private fun assemble(
        file: Path,
        archive: EpubArchive,
        pkg: PackageDocument,
        obfuscated: Set<String>,
    ): EpubResult {
        val chapters =
            pkg.spine.mapNotNull { item ->
                archive.bytes(item.href)?.let { Chapter(item.href, null, EpubXml.decode(it)) }
            }
        if (chapters.isEmpty()) return EpubResult.Invalid("no chapters")

        val toc = readToc(archive, pkg, chapters)
        val titled = chapters.map { it.copy(title = EpubToc.titleFor(toc, it.href)) }

        val chapterPaths = chapters.map { it.href }.toSet()
        val resources = linkedMapOf<String, Resource>()
        for (item in pkg.manifest.values) {
            if (item.href in chapterPaths || item.href in obfuscated || item.href in resources) continue
            if (!ResourceUris.isKept(item.mediaType)) continue
            archive.bytes(item.href)?.let { resources[item.href] = Resource(item.mediaType, it) }
        }
        val title = pkg.title ?: file.nameWithoutExtension
        return EpubResult.Book(EpubBook(title, pkg.language, pkg.fixedLayout, titled, toc, resources))
    }

    private fun readToc(
        archive: EpubArchive,
        pkg: PackageDocument,
        chapters: List<Chapter>,
    ): List<TocEntry> {
        val nav = pkg.manifest.values.firstOrNull { "nav" in it.properties }
        val ncx =
            pkg.tocId?.let { pkg.manifest[it] }
                ?: pkg.manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        val fromNav = nav?.let { item -> archive.bytes(item.href)?.let { EpubToc.fromNav(item.href, it) } }.orEmpty()
        if (fromNav.isNotEmpty()) return fromNav
        val fromNcx = ncx?.let { item -> archive.bytes(item.href)?.let { EpubToc.fromNcx(item.href, it) } }.orEmpty()
        return fromNcx.ifEmpty { EpubToc.fromSpine(chapters) }
    }
}
