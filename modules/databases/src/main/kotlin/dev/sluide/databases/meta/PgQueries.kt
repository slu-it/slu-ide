package dev.sluide.databases.meta

/** The `pg_catalog` queries; each takes the schema name as its first bind parameter unless noted. */
internal object PgQueries {
    const val SCHEMAS = "select nspname as name from pg_namespace where has_schema_privilege(oid, 'USAGE')"

    /** Rows: name, kind (relkind). */
    const val RELATIONS =
        """select c.relname as name, c.relkind::text as kind
           from pg_class c join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = ? and c.relkind in ('r', 'p', 'f', 'v', 'm', 'S')
           order by c.relname"""

    /** Rows: tbl, name, type, notnull, dflt, identity, generated, ordinal. */
    const val COLUMNS =
        """select c.relname as tbl, a.attname as name, format_type(a.atttypid, a.atttypmod) as type,
                  a.attnotnull as notnull, pg_get_expr(d.adbin, d.adrelid) as dflt,
                  a.attidentity::text as identity, a.attgenerated::text as generated, a.attnum as ordinal
           from pg_attribute a
             join pg_class c on c.oid = a.attrelid
             join pg_namespace n on n.oid = c.relnamespace
             left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
           where n.nspname = ? and c.relkind in ('r', 'p', 'f', 'v', 'm') and a.attnum > 0 and not a.attisdropped
           order by c.relname, a.attnum"""

    /** Rows: tbl, name, type (p/u/f/c), def, cols, fschema, ftable, fcols. */
    const val CONSTRAINTS =
        """select c.relname as tbl, con.conname as name, con.contype::text as type,
                  pg_get_constraintdef(con.oid, true) as def,
                  (select string_agg(a.attname, ', ' order by k.ord)
                     from unnest(con.conkey) with ordinality as k(attnum, ord)
                     join pg_attribute a on a.attrelid = con.conrelid and a.attnum = k.attnum) as cols,
                  fn.nspname as fschema, fc.relname as ftable,
                  (select string_agg(a.attname, ', ' order by k.ord)
                     from unnest(con.confkey) with ordinality as k(attnum, ord)
                     join pg_attribute a on a.attrelid = con.confrelid and a.attnum = k.attnum) as fcols
           from pg_constraint con
             join pg_class c on c.oid = con.conrelid
             join pg_namespace n on n.oid = c.relnamespace
             left join pg_class fc on fc.oid = con.confrelid
             left join pg_namespace fn on fn.oid = fc.relnamespace
           where n.nspname = ? and con.contype in ('p', 'u', 'f', 'c')
           order by c.relname, con.conname"""

    /** Rows: tbl, name, uniq, cols, def, backs (the index backs a primary key or unique constraint). */
    const val INDEXES =
        """select t.relname as tbl, i.relname as name, x.indisunique as uniq,
                  (select string_agg(pg_get_indexdef(x.indexrelid, k, true), ', ' order by k)
                     from generate_series(1, x.indnkeyatts::int) as k) as cols,
                  pg_get_indexdef(x.indexrelid) as def,
                  exists (select 1 from pg_constraint con
                          where con.conindid = x.indexrelid and con.contype in ('p', 'u', 'x')) as backs
           from pg_index x
             join pg_class i on i.oid = x.indexrelid
             join pg_class t on t.oid = x.indrelid
             join pg_namespace n on n.oid = t.relnamespace
           where n.nspname = ?
           order by t.relname, i.relname"""

    /** Rows: tbl, name, def. */
    const val TRIGGERS =
        """select c.relname as tbl, t.tgname as name, pg_get_triggerdef(t.oid, true) as def
           from pg_trigger t
             join pg_class c on c.oid = t.tgrelid
             join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = ? and not t.tgisinternal
           order by c.relname, t.tgname"""

    /** Rows: name, kind (prokind), ident (identity arguments), types, result. */
    const val ROUTINES =
        """select p.proname as name, p.prokind::text as kind,
                  pg_get_function_identity_arguments(p.oid) as ident,
                  oidvectortypes(p.proargtypes) as types, pg_get_function_result(p.oid) as result
           from pg_proc p join pg_namespace n on n.oid = p.pronamespace
           where n.nspname = ? and p.prokind in ('f', 'p')
           order by p.proname, 3"""

    /** Rows: name, kind (typtype e/d/c), detail (enum labels, domain base type). */
    const val TYPES =
        """select t.typname as name, t.typtype::text as kind,
                  case t.typtype
                    when 'e' then (select string_agg(e.enumlabel, ', ' order by e.enumsortorder)
                                   from pg_enum e where e.enumtypid = t.oid)
                    when 'd' then format_type(t.typbasetype, t.typtypmod)
                  end as detail
           from pg_type t join pg_namespace n on n.oid = t.typnamespace
           where n.nspname = ?
             and (t.typtype in ('e', 'd')
                  or (t.typtype = 'c' and exists (select 1 from pg_class r where r.oid = t.typrelid and r.relkind = 'c')))
           order by t.typname"""

    /** Parameters: schema, table. Rows: columns. */
    const val PRIMARY_KEY =
        """select a.attname as name
           from pg_constraint con
             join pg_class c on c.oid = con.conrelid
             join pg_namespace n on n.oid = c.relnamespace
             cross join unnest(con.conkey) with ordinality as k(attnum, ord)
             join pg_attribute a on a.attrelid = con.conrelid and a.attnum = k.attnum
           where n.nspname = coalesce(?::text, current_schema()::text) and c.relname = ? and con.contype = 'p'
           order by k.ord"""

    // DDL sources; parameters: schema, name (function: schema, name, identity arguments).
    const val VIEW_DEF =
        """select pg_get_viewdef(c.oid, true) as def
           from pg_class c join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = ? and c.relname = ?"""

    const val FUNCTION_DEF =
        """select pg_get_functiondef(p.oid) as def
           from pg_proc p join pg_namespace n on n.oid = p.pronamespace
           where n.nspname = ? and p.proname = ? and pg_get_function_identity_arguments(p.oid) = ?"""

    const val SEQUENCE_DEF =
        """select data_type::text as type, start_value, min_value, max_value, increment_by, cycle, cache_size
           from pg_sequences where schemaname = ? and sequencename = ?"""

    const val ENUM_LABELS =
        """select e.enumlabel as label
           from pg_enum e join pg_type t on t.oid = e.enumtypid join pg_namespace n on n.oid = t.typnamespace
           where n.nspname = ? and t.typname = ? order by e.enumsortorder"""

    const val DOMAIN_DEF =
        """select format_type(t.typbasetype, t.typtypmod) as base, t.typnotnull as notnull, t.typdefault as dflt
           from pg_type t join pg_namespace n on n.oid = t.typnamespace
           where n.nspname = ? and t.typname = ?"""

    const val DOMAIN_CONSTRAINTS =
        """select con.conname as name, pg_get_constraintdef(con.oid, true) as def
           from pg_constraint con
             join pg_type t on t.oid = con.contypid
             join pg_namespace n on n.oid = t.typnamespace
           where n.nspname = ? and t.typname = ? order by con.conname"""

    const val COMPOSITE_ATTRIBUTES =
        """select a.attname as name, format_type(a.atttypid, a.atttypmod) as type
           from pg_attribute a
             join pg_type t on t.typrelid = a.attrelid
             join pg_namespace n on n.oid = t.typnamespace
           where n.nspname = ? and t.typname = ? and a.attnum > 0 and not a.attisdropped
           order by a.attnum"""
}
