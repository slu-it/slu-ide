package dev.sluide.databases.meta

/** The `information_schema` queries of MariaDB; each takes the database name as its bind parameter. */
internal object MariaQueries {
    const val SCHEMAS = "select SCHEMA_NAME as name from information_schema.SCHEMATA"

    /** Rows: name, kind (TABLE_TYPE). */
    const val TABLES =
        """select TABLE_NAME as name, TABLE_TYPE as kind
           from information_schema.TABLES where TABLE_SCHEMA = ? order by TABLE_NAME"""

    /** Rows: tbl, name, type, nullable, dflt, extra. */
    const val COLUMNS =
        """select TABLE_NAME as tbl, COLUMN_NAME as name, COLUMN_TYPE as type, IS_NULLABLE as nullable,
                  COLUMN_DEFAULT as dflt, EXTRA as extra
           from information_schema.COLUMNS where TABLE_SCHEMA = ? order by TABLE_NAME, ORDINAL_POSITION"""

    /** Rows: tbl, name, type (PRIMARY KEY, UNIQUE, FOREIGN KEY; checks come from [CHECKS]). */
    const val CONSTRAINTS =
        """select TABLE_NAME as tbl, CONSTRAINT_NAME as name, CONSTRAINT_TYPE as type
           from information_schema.TABLE_CONSTRAINTS
           where TABLE_SCHEMA = ? and CONSTRAINT_TYPE in ('PRIMARY KEY', 'UNIQUE', 'FOREIGN KEY')"""

    /** Rows: tbl, name, col, ord, rschema, rtable, rcol. */
    const val KEY_COLUMNS =
        """select TABLE_NAME as tbl, CONSTRAINT_NAME as name, COLUMN_NAME as col, ORDINAL_POSITION as ord,
                  REFERENCED_TABLE_SCHEMA as rschema, REFERENCED_TABLE_NAME as rtable,
                  REFERENCED_COLUMN_NAME as rcol
           from information_schema.KEY_COLUMN_USAGE where TABLE_SCHEMA = ? order by TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION"""

    /** Rows: tbl, name, onupdate, ondelete. */
    const val REFERENTIAL =
        """select TABLE_NAME as tbl, CONSTRAINT_NAME as name, UPDATE_RULE as onupdate, DELETE_RULE as ondelete
           from information_schema.REFERENTIAL_CONSTRAINTS where CONSTRAINT_SCHEMA = ?"""

    /** Rows: tbl, name, clause. */
    const val CHECKS =
        """select TABLE_NAME as tbl, CONSTRAINT_NAME as name, CHECK_CLAUSE as clause
           from information_schema.CHECK_CONSTRAINTS where CONSTRAINT_SCHEMA = ?"""

    /** Rows: tbl, name, nonunique, col, seq. */
    const val STATISTICS =
        """select TABLE_NAME as tbl, INDEX_NAME as name, NON_UNIQUE as nonunique, COLUMN_NAME as col,
                  SEQ_IN_INDEX as seq
           from information_schema.STATISTICS where TABLE_SCHEMA = ? order by TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX"""

    /** Rows: tbl, name, timing, event. */
    const val TRIGGERS =
        """select EVENT_OBJECT_TABLE as tbl, TRIGGER_NAME as name, ACTION_TIMING as timing,
                  EVENT_MANIPULATION as event
           from information_schema.TRIGGERS where TRIGGER_SCHEMA = ?"""

    /** Rows: name, kind (FUNCTION/PROCEDURE), result. */
    const val ROUTINES =
        """select ROUTINE_NAME as name, ROUTINE_TYPE as kind, DTD_IDENTIFIER as result
           from information_schema.ROUTINES where ROUTINE_SCHEMA = ?"""

    /** Rows: routine, ord, type. */
    const val PARAMETERS =
        """select SPECIFIC_NAME as routine, ORDINAL_POSITION as ord, DTD_IDENTIFIER as type
           from information_schema.PARAMETERS
           where SPECIFIC_SCHEMA = ? and ORDINAL_POSITION > 0 order by SPECIFIC_NAME, ORDINAL_POSITION"""

    /** Rows: name, status. */
    const val EVENTS =
        """select EVENT_NAME as name, STATUS as status from information_schema.EVENTS where EVENT_SCHEMA = ?"""

    /** Parameters: schema (may be null for the default database), table. */
    const val PRIMARY_KEY =
        """select COLUMN_NAME as name from information_schema.KEY_COLUMN_USAGE
           where TABLE_SCHEMA = coalesce(?, database()) and TABLE_NAME = ? and CONSTRAINT_NAME = 'PRIMARY'
           order by ORDINAL_POSITION"""
}
