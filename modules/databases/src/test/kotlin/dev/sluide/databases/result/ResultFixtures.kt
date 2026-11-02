package dev.sluide.databases.result

import dev.sluide.databases.session.ColumnInfo
import java.sql.Types

internal fun col(
    name: String,
    jdbcType: Int = Types.VARCHAR,
    typeName: String = "",
    table: String? = "t",
    schema: String? = "s",
    label: String = name,
) = ColumnInfo(name, label, typeName, jdbcType, schema, table, true, false)
