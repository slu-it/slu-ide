package dev.sluide.converting.data

import tools.jackson.databind.JsonNode

/** The model of the data family: the documents of a text (several for multi-document YAML), none for blank text. */
internal data class DataContent(
    val documents: List<JsonNode>,
)
