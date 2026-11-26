package dev.sluide.plantuml

/**
 * Whether [text] has a PlantUML block: a line that starts, after blanks, with `@start` (`@startuml`, `@startmindmap`,
 * …). Fences without one are wrapped in `@startuml`/`@enduml` before they are drawn.
 */
fun plantUmlBlocks(text: String): Boolean = text.lineSequence().any { it.trimStart().startsWith("@start") }
