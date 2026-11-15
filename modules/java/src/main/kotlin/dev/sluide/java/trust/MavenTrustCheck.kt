package dev.sluide.java.trust

import dev.sluide.java.MAX_FILE_BYTES
import dev.sluide.java.MAX_WALK_FOLDERS
import dev.sluide.java.listingFolder
import dev.sluide.java.pomModules
import dev.sluide.java.readLimitedBytes
import dev.sluide.java.walkFolders
import dev.sluide.projecttrust.ProjectTrustCheck
import dev.sluide.projecttrust.TrustFinding
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * What the Java language server would run when it imports a Maven build (ADR 0179): the build's plugins and
 * extensions. One finding per Maven reactor root below the folder: a folder with a `pom.xml` that is not a module of
 * its parent's pom. Its fingerprint covers every pom below the root, the poms of the modules the reactor lists wherever they are (inside the folder), and the root's `.mvn/extensions.xml` and
 * `.mvn/maven.config`. It is not checkable (`null`) if the walk is interrupted or reaches the folder cap. It only reads
 * files and runs nothing; poms are parsed without DTDs and with a size limit.
 */
@Component
class MavenTrustCheck internal constructor(
    private val maxFolders: Int,
) : ProjectTrustCheck {
    @Autowired
    constructor() : this(MAX_WALK_FOLDERS)

    override val id = ID

    override val name = "Maven"

    override fun check(folder: Path): List<TrustFinding>? {
        val top = folder.toAbsolutePath().normalize()
        if (!Files.isDirectory(top)) return null
        val poms = mutableListOf<Path>()
        val complete =
            walkFolders(top, maxFolders) { dir, children ->
                if (children.any { it.fileName.toString() == "pom.xml" && Files.isRegularFile(it) }) poms.add(dir)
                true
            }
        if (!complete) return null
        val findings = mutableListOf<TrustFinding>()
        for (dir in poms.sorted()) {
            if (listingFolder(dir, top) != null) continue
            val fingerprint = fingerprint(dir, top) ?: return null
            val relative = top.relativize(dir).toString().ifEmpty { "." }
            findings +=
                TrustFinding(
                    "Maven build in $relative",
                    "Importing it into the Java language server runs its build plugins and extensions.",
                    "maven\u0000$relative\u0000$fingerprint",
                )
        }
        return findings
    }

    /** The hash over the poms below [root] and its `.mvn` files, in path order; `null` if the walk is incomplete. */
    private fun fingerprint(
        root: Path,
        top: Path,
    ): String? {
        val files = sortedSetOf<Path>()
        val complete =
            walkFolders(root, maxFolders) { _, children ->
                children
                    .filter {
                        it.fileName.toString() == "pom.xml" &&
                            Files.isRegularFile(
                                it,
                            )
                    }.forEach { files.add(it) }
                true
            }
        if (!complete) return null
        if (!addModulePoms(root, top, files)) return null
        listOf(
            ".mvn/extensions.xml",
            ".mvn/maven.config",
        ).map { root.resolve(it) }.filter { Files.isRegularFile(it) }.forEach {
            files.add(it)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        for (file in files) {
            digest.update(root.relativize(file).joinToString("/").toByteArray())
            digest.update(0)
            val content = readLimitedBytes(file)
            if (content == null) {
                digest.update("unreadable".toByteArray())
            } else {
                digest.update(content, 0, minOf(content.size, MAX_FILE_BYTES))
                // The size tells a pom that grew past the read limit from the one that did not.
                if (content.size >
                    MAX_FILE_BYTES
                ) {
                    digest.update(runCatching { Files.size(file) }.getOrDefault(-1L).toString().toByteArray())
                }
            }
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Adds the poms of the modules [root]'s pom lists, and theirs in turn (also profiles), to [files]: jdtls imports
     * them with the reactor wherever they are, also in folders the walk skips. A module outside [top], a folder
     * without a pom and a folder seen before are left out. `false` if more than [maxFolders] folders are followed.
     */
    private fun addModulePoms(
        root: Path,
        top: Path,
        files: MutableSet<Path>,
    ): Boolean {
        val seen = mutableSetOf(root)
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty()) {
            for (module in pomModules(pending.removeFirst())) {
                val pom = module.resolve("pom.xml")
                if (!module.startsWith(top) || !Files.isRegularFile(pom) || !seen.add(module)) continue
                if (seen.size > maxFolders) return false
                files.add(pom)
                pending.addLast(module)
            }
        }
        return true
    }

    companion object {
        /** The id the Java language server spec asks [dev.sluide.projecttrust.ProjectTrust.allows] with. */
        const val ID = "maven"
    }
}
