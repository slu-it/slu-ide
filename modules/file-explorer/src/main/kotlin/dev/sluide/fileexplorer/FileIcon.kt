package dev.sluide.fileexplorer

import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.materialdesign2.MaterialDesignA
import org.kordamp.ikonli.materialdesign2.MaterialDesignB
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.kordamp.ikonli.materialdesign2.MaterialDesignD
import org.kordamp.ikonli.materialdesign2.MaterialDesignE
import org.kordamp.ikonli.materialdesign2.MaterialDesignF
import org.kordamp.ikonli.materialdesign2.MaterialDesignG
import org.kordamp.ikonli.materialdesign2.MaterialDesignH
import org.kordamp.ikonli.materialdesign2.MaterialDesignK
import org.kordamp.ikonli.materialdesign2.MaterialDesignL
import org.kordamp.ikonli.materialdesign2.MaterialDesignM
import org.kordamp.ikonli.materialdesign2.MaterialDesignN
import org.kordamp.ikonli.materialdesign2.MaterialDesignP
import org.kordamp.ikonli.materialdesign2.MaterialDesignR
import org.kordamp.ikonli.materialdesign2.MaterialDesignS
import org.kordamp.ikonli.materialdesign2.MaterialDesignT
import org.kordamp.ikonli.materialdesign2.MaterialDesignU
import org.kordamp.ikonli.materialdesign2.MaterialDesignV
import org.kordamp.ikonli.materialdesign2.MaterialDesignW
import org.kordamp.ikonli.materialdesign2.MaterialDesignZ
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * The icons of the file explorer, from Material Design Icons (ADR 0012): every icon of the pack that stands for a
 * file type. A file gets the icon whose [names] contain its name, else the one whose [extensions] contain its
 * extension (both lower case); see [fileIconFor]. Each icon has a style class that colours it in `file-explorer.css`;
 * related types (all images, all archives, …) share one. A new file type is an entry here and, if it needs a new
 * colour, a rule in the CSS.
 */
internal enum class FileIcon(
    val ikon: Ikon,
    val styleClass: String,
    val extensions: Set<String> = emptySet(),
    val names: Set<String> = emptySet(),
) {
    FOLDER(MaterialDesignF.FOLDER, "file-icon-folder"),
    FOLDER_OPEN(MaterialDesignF.FOLDER_OPEN, "file-icon-folder"),

    /** Readable file of no known type. */
    TEXT(MaterialDesignF.FILE_DOCUMENT_OUTLINE, "file-icon-text", setOf("txt", "text", "log")),

    /** File of no known type that no viewer can show. */
    BINARY(MaterialDesignF.FILE_QUESTION_OUTLINE, "file-icon-binary"),

    // Languages with a code viewer
    KOTLIN(MaterialDesignL.LANGUAGE_KOTLIN, "file-icon-kotlin", setOf("kt", "kts")),
    JAVA(MaterialDesignL.LANGUAGE_JAVA, "file-icon-java", setOf("java")),
    PYTHON(MaterialDesignL.LANGUAGE_PYTHON, "file-icon-python", setOf("py", "pyw", "pyi")),
    JAVASCRIPT(MaterialDesignL.LANGUAGE_JAVASCRIPT, "file-icon-javascript", setOf("js", "mjs", "cjs")),
    TYPESCRIPT(MaterialDesignL.LANGUAGE_TYPESCRIPT, "file-icon-typescript", setOf("ts", "mts", "cts")),
    HTML(MaterialDesignL.LANGUAGE_HTML5, "file-icon-html", setOf("html", "htm", "xhtml")),
    JSON(MaterialDesignC.CODE_JSON, "file-icon-json", setOf("json", "jsonc", "json5")),
    XML(MaterialDesignF.FILE_XML_BOX, "file-icon-xml", setOf("xml", "xsd", "xsl", "xslt", "svg", "fxml", "plist")),

    // Material Design Icons has no YAML icon.
    YAML(MaterialDesignC.CODE_BRACES, "file-icon-yaml", setOf("yaml", "yml")),

    // Other languages
    MARKDOWN(MaterialDesignL.LANGUAGE_MARKDOWN, "file-icon-markdown", setOf("md", "markdown", "mdx")),
    C(MaterialDesignL.LANGUAGE_C, "file-icon-c", setOf("c", "h")),
    CPP(MaterialDesignL.LANGUAGE_CPP, "file-icon-cpp", setOf("cpp", "cc", "cxx", "c++", "hpp", "hh", "hxx", "ino")),
    CSHARP(MaterialDesignL.LANGUAGE_CSHARP, "file-icon-csharp", setOf("cs", "csx")),
    CSS(MaterialDesignL.LANGUAGE_CSS3, "file-icon-css", setOf("css")),
    SASS(MaterialDesignS.SASS, "file-icon-sass", setOf("scss", "sass")),
    FORTRAN(
        MaterialDesignL.LANGUAGE_FORTRAN,
        "file-icon-fortran",
        setOf("f", "for", "f77", "f90", "f95", "f03", "f08"),
    ),
    GO(MaterialDesignL.LANGUAGE_GO, "file-icon-go", setOf("go"), setOf("go.mod", "go.sum", "go.work")),
    HASKELL(MaterialDesignL.LANGUAGE_HASKELL, "file-icon-haskell", setOf("hs", "lhs")),
    LUA(MaterialDesignL.LANGUAGE_LUA, "file-icon-lua", setOf("lua")),
    PHP(MaterialDesignL.LANGUAGE_PHP, "file-icon-php", setOf("php", "phtml")),
    R(MaterialDesignL.LANGUAGE_R, "file-icon-r", setOf("r", "rmd", "rds", "rdata")),
    RUBY(MaterialDesignL.LANGUAGE_RUBY, "file-icon-ruby", setOf("rb", "rake", "gemspec"), setOf("gemfile", "rakefile")),
    RUBY_ON_RAILS(MaterialDesignL.LANGUAGE_RUBY_ON_RAILS, "file-icon-ruby", setOf("erb")),
    RUST(MaterialDesignL.LANGUAGE_RUST, "file-icon-rust", setOf("rs")),
    SWIFT(MaterialDesignL.LANGUAGE_SWIFT, "file-icon-swift", setOf("swift")),
    XAML(MaterialDesignL.LANGUAGE_XAML, "file-icon-dotnet", setOf("xaml")),
    DOTNET(MaterialDesignD.DOT_NET, "file-icon-dotnet", setOf("csproj", "fsproj", "vbproj", "sln", "fs", "fsx", "vb")),
    REACT(MaterialDesignR.REACT, "file-icon-react", setOf("jsx", "tsx")),
    VUE(MaterialDesignV.VUEJS, "file-icon-vue", setOf("vue")),
    GRAPHQL(MaterialDesignG.GRAPHQL, "file-icon-graphql", setOf("graphql", "gql", "graphqls")),

    /** Requests of the HTTP client. */
    HTTP(MaterialDesignA.API, "file-icon-http", setOf("http", "rest")),
    SHELL(
        MaterialDesignB.BASH,
        "file-icon-shell",
        setOf("sh", "bash", "zsh", "fish", "ksh"),
        setOf(".bashrc", ".bash_profile", ".zshrc", ".zprofile", ".profile"),
    ),
    POWERSHELL(MaterialDesignP.POWERSHELL, "file-icon-powershell", setOf("ps1", "psm1", "psd1")),
    BATCH(MaterialDesignC.CONSOLE, "file-icon-console", setOf("bat", "cmd")),
    SQL(MaterialDesignD.DATABASE, "file-icon-database", setOf("sql", "db", "sqlite", "sqlite3", "mdb", "accdb")),
    NOTEBOOK(MaterialDesignN.NOTEBOOK, "file-icon-notebook", setOf("ipynb")),

    /** Source code in a language without its own icon. */
    CODE(
        MaterialDesignF.FILE_CODE,
        "file-icon-code",
        setOf(
            "scala",
            "sc",
            "groovy",
            "dart",
            "clj",
            "cljs",
            "cljc",
            "edn",
            "ex",
            "exs",
            "erl",
            "hrl",
            "ml",
            "mli",
            "nim",
            "zig",
            "pl",
            "pm",
            "jl",
            "elm",
            "sol",
            "proto",
            "asm",
            "s",
            "m",
            "mm",
            "v",
            "sv",
            "vhd",
            "vhdl",
            "tcl",
            "cr",
            "d",
            "pas",
            "lisp",
            "el",
            "scm",
            "rkt",
            "purs",
            "hx",
            "wat",
            "svelte",
            "astro",
        ),
    ),

    // Tools and their files
    GIT(
        MaterialDesignS.SOURCE_BRANCH,
        "file-icon-git",
        names = setOf(".gitignore", ".gitattributes", ".gitmodules", ".gitkeep", ".git-blame-ignore-revs", ".mailmap"),
    ),
    GITHUB(MaterialDesignG.GITHUB, "file-icon-github", names = setOf("codeowners", "funding.yml", "dependabot.yml")),
    GITLAB(MaterialDesignG.GITLAB, "file-icon-gitlab", names = setOf(".gitlab-ci.yml")),
    DOCKER(
        MaterialDesignD.DOCKER,
        "file-icon-docker",
        setOf("dockerfile", "containerfile"),
        setOf(
            "dockerfile",
            "containerfile",
            ".dockerignore",
            "docker-compose.yml",
            "docker-compose.yaml",
            "compose.yml",
            "compose.yaml",
        ),
    ),
    KUBERNETES(
        MaterialDesignK.KUBERNETES,
        "file-icon-kubernetes",
        names = setOf("kustomization.yaml", "kustomization.yml"),
    ),
    TERRAFORM(MaterialDesignT.TERRAFORM, "file-icon-terraform", setOf("tf", "tfvars", "tfstate", "hcl")),
    ANSIBLE(MaterialDesignA.ANSIBLE, "file-icon-ansible", names = setOf("ansible.cfg")),

    // Gradle's logo is an elephant.
    GRADLE(
        MaterialDesignE.ELEPHANT,
        "file-icon-gradle",
        setOf("gradle"),
        setOf(
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
            "gradlew",
            "gradlew.bat",
            "gradle-wrapper.properties",
            "libs.versions.toml",
        ),
    ),
    MAKE(
        MaterialDesignH.HAMMER_WRENCH,
        "file-icon-make",
        setOf("mk", "cmake"),
        setOf("makefile", "gnumakefile", "cmakelists.txt"),
    ),
    NODEJS(
        MaterialDesignN.NODEJS,
        "file-icon-nodejs",
        names = setOf("package.json", ".nvmrc", ".node-version", ".npmrc", ".npmignore"),
    ),
    ANGULAR(MaterialDesignA.ANGULAR, "file-icon-angular", names = setOf("angular.json", ".angular-cli.json")),
    WEBPACK(
        MaterialDesignW.WEBPACK,
        "file-icon-webpack",
        names = setOf("webpack.config.js", "webpack.config.ts", "webpack.config.cjs", "webpack.config.mjs"),
    ),
    BABEL(
        MaterialDesignB.BABEL,
        "file-icon-babel",
        names =
            setOf(
                ".babelrc",
                ".babelrc.json",
                "babel.config.js",
                "babel.config.json",
                "babel.config.cjs",
                "babel.config.mjs",
            ),
    ),
    TAILWIND(
        MaterialDesignT.TAILWIND,
        "file-icon-tailwind",
        names = setOf("tailwind.config.js", "tailwind.config.ts", "tailwind.config.cjs", "tailwind.config.mjs"),
    ),
    NUXT(MaterialDesignN.NUXT, "file-icon-nuxt", names = setOf("nuxt.config.js", "nuxt.config.ts")),
    UNITY(MaterialDesignU.UNITY, "file-icon-unity", setOf("unity", "prefab", "unitypackage")),
    UNREAL(MaterialDesignU.UNREAL, "file-icon-unreal", setOf("uproject", "uasset", "umap", "uplugin")),

    // Platforms: installers and binaries
    ANDROID(MaterialDesignA.ANDROID, "file-icon-android", setOf("apk", "aab")),
    APPLE(MaterialDesignA.APPLE, "file-icon-apple", setOf("dmg", "ipa", "pkg")),
    WINDOWS(MaterialDesignM.MICROSOFT_WINDOWS, "file-icon-windows", setOf("exe", "msi", "dll", "lnk")),

    // Settings and security
    CONFIG(
        MaterialDesignF.FILE_COG,
        "file-icon-config",
        // Also the dot-files .env, .editorconfig, .prettierrc and .eslintrc: their extension is the name without the dot.
        setOf("ini", "cfg", "conf", "config", "properties", "toml", "env", "editorconfig", "prettierrc", "eslintrc"),
    ),
    CERTIFICATE(
        MaterialDesignF.FILE_CERTIFICATE,
        "file-icon-security",
        setOf("crt", "cer", "pem", "der", "p12", "pfx", "csr", "p7b"),
    ),
    KEY(
        MaterialDesignF.FILE_KEY,
        "file-icon-security",
        setOf("key", "pub", "asc", "gpg", "sig", "jks", "keystore", "kdbx"),
    ),
    LOCK(
        MaterialDesignF.FILE_LOCK,
        "file-icon-security",
        setOf("lock"),
        setOf("package-lock.json", "pnpm-lock.yaml", "npm-shrinkwrap.json", "gradle.lockfile"),
    ),
    LICENSE(
        MaterialDesignL.LICENSE,
        "file-icon-license",
        names =
            setOf(
                "license",
                "license.md",
                "license.txt",
                "licence",
                "licence.md",
                "licence.txt",
                "copying",
                "notice",
            ),
    ),

    // Documents and data
    PDF(MaterialDesignF.FILE_PDF_BOX, "file-icon-pdf", setOf("pdf")),
    WORD(
        MaterialDesignF.FILE_WORD_BOX,
        "file-icon-word",
        setOf("doc", "docx", "docm", "dot", "dotx", "odt", "rtf", "pages"),
    ),
    EXCEL(
        MaterialDesignF.FILE_EXCEL_BOX,
        "file-icon-excel",
        setOf("xls", "xlsx", "xlsm", "xlsb", "xlt", "xltx", "ods"),
    ),
    POWERPOINT(
        MaterialDesignF.FILE_POWERPOINT_BOX,
        "file-icon-powerpoint",
        setOf("ppt", "pptx", "pptm", "pot", "potx", "pps", "ppsx"),
    ),
    PRESENTATION(MaterialDesignF.FILE_PRESENTATION_BOX, "file-icon-powerpoint", setOf("odp", "keynote")),
    DELIMITED(MaterialDesignF.FILE_DELIMITED, "file-icon-table", setOf("csv", "tsv", "psv")),
    TABLE(
        MaterialDesignF.FILE_TABLE,
        "file-icon-table",
        setOf("parquet", "avro", "orc", "numbers", "arrow", "feather"),
    ),
    EMAIL(MaterialDesignE.EMAIL, "file-icon-email", setOf("eml", "msg", "mbox")),
    CALENDAR(MaterialDesignC.CALENDAR, "file-icon-calendar", setOf("ics", "ical", "vcs")),

    // Media
    PNG(MaterialDesignF.FILE_PNG_BOX, "file-icon-image", setOf("png", "apng")),
    JPG(MaterialDesignF.FILE_JPG_BOX, "file-icon-image", setOf("jpg", "jpeg", "jpe", "jfif")),
    GIF(MaterialDesignF.FILE_GIF_BOX, "file-icon-image", setOf("gif")),
    IMAGE(
        MaterialDesignF.FILE_IMAGE,
        "file-icon-image",
        setOf(
            "bmp",
            "webp",
            "ico",
            "icns",
            "tif",
            "tiff",
            "heic",
            "heif",
            "avif",
            "psd",
            "xcf",
            "raw",
            "cr2",
            "nef",
            "dng",
        ),
    ),
    VECTOR(MaterialDesignV.VECTOR_SQUARE, "file-icon-vector", setOf("ai", "eps", "sketch", "fig", "xd", "afdesign")),
    VIDEO(
        MaterialDesignF.FILE_VIDEO,
        "file-icon-video",
        setOf("mp4", "m4v", "mov", "avi", "mkv", "webm", "wmv", "flv", "mpg", "mpeg", "3gp"),
    ),
    AUDIO(
        MaterialDesignF.FILE_MUSIC,
        "file-icon-audio",
        setOf("mp3", "wav", "flac", "ogg", "oga", "opus", "m4a", "aac", "wma", "aiff", "mid", "midi"),
    ),
    FONT(MaterialDesignF.FORMAT_FONT, "file-icon-font", setOf("ttf", "otf", "woff", "woff2", "eot", "fon")),
    CAD(MaterialDesignF.FILE_CAD, "file-icon-cad", setOf("dwg", "dxf", "step", "stp", "iges", "igs", "ifc")),
    MODEL_3D(
        MaterialDesignC.CUBE_OUTLINE,
        "file-icon-cad",
        setOf("obj", "fbx", "gltf", "glb", "blend", "stl", "3mf", "dae", "usdz"),
    ),

    // Archives and packages
    ARCHIVE(
        MaterialDesignZ.ZIP_BOX,
        "file-icon-archive",
        setOf("zip", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "7z", "rar", "zst", "lz", "lzma", "cab", "iso"),
    ),
    PACKAGE(
        MaterialDesignP.PACKAGE_VARIANT_CLOSED,
        "file-icon-package",
        setOf(
            "jar",
            "war",
            "ear",
            "aar",
            "deb",
            "rpm",
            "whl",
            "egg",
            "gem",
            "nupkg",
            "snap",
            "flatpak",
            "appimage",
            "vsix",
            "crx",
            "xpi",
        ),
    ),
}

/** Icons by lower-case file name, which win over [iconsByExtension]. */
private val iconsByName: Map<String, FileIcon> =
    FileIcon.entries.flatMap { icon -> icon.names.map { it to icon } }.toMap()

/** Icons by lower-case extension. */
private val iconsByExtension: Map<String, FileIcon> =
    FileIcon.entries.flatMap { icon -> icon.extensions.map { it to icon } }.toMap()

/**
 * The icon for [path]: an open or closed folder, the icon of a known file name or else extension (case-insensitive),
 * or else [FileIcon.TEXT] or [FileIcon.BINARY] depending on [readable]. [readable] may read the file, so it is only
 * called for files of no known type.
 */
internal fun fileIconFor(
    path: Path,
    directory: Boolean,
    expanded: Boolean,
    readable: () -> Boolean,
): FileIcon =
    when {
        directory -> {
            if (expanded) FileIcon.FOLDER_OPEN else FileIcon.FOLDER
        }

        else -> {
            iconsByName[path.name.lowercase()]
                ?: iconsByExtension[path.extension.lowercase()]
                ?: if (readable()) FileIcon.TEXT else FileIcon.BINARY
        }
    }
