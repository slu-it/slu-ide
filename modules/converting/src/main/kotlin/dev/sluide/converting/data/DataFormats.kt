package dev.sluide.converting.data

/** The formats of the data family, in picker order. */
internal object DataFormats {
    val all: List<DataFormat> =
        listOf(
            JsonFormat,
            YamlFormat,
            TomlFormat,
            PropertiesFormat,
            CsvFormat,
            TsvFormat,
        )
}
