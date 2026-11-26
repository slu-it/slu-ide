package dev.sluide.plantuml

/**
 * Whether [text] has a line that starts, after blanks, with `@start` (`@startuml`, `@startmindmap`, …). Fences without
 * one are wrapped in `@startuml`/`@enduml` before they are drawn. PlantUML also needs an `@end…` line to make a block:
 * a text with a `@start…` line but none is drawn as a [Rendering] without diagrams, which callers handle.
 */
fun plantUmlBlocks(text: String): Boolean = text.lineSequence().any { it.trimStart().startsWith("@start") }
