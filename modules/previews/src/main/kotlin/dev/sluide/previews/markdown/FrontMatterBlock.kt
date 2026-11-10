package dev.sluide.previews.markdown

import org.commonmark.node.CustomBlock

/** The front matter at the start of a Markdown file ([language] `yaml` or `toml`), [literal] as written. */
internal class FrontMatterBlock(
    val language: String,
    val literal: String,
) : CustomBlock()
