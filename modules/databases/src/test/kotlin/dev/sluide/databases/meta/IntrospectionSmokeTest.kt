package dev.sluide.databases.meta

import dev.sluide.databases.RealDatabases
import dev.sluide.databases.connect.DatabaseKind
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Introspector]s against real PostgreSQL 17 and MariaDB 11 servers with a small `demo` schema each, opt-in like the
 * session smoke test (`SLUIDE_DB_SMOKE=1`, [RealDatabases]).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntrospectionSmokeTest {
    private val servers = mutableMapOf<DatabaseKind, RealDatabases.Server>()

    @AfterAll
    fun stopServers() {
        servers.values.forEach { RealDatabases.stop(it) }
    }

    private fun <T> withDemo(
        kind: DatabaseKind,
        block: (Connection, Introspector) -> T,
    ): T {
        val server = servers.getOrPut(kind) { RealDatabases.start(kind) }
        return server.connect().use { c ->
            if (c.rows(if (kind == DatabaseKind.POSTGRESQL) PG_PROBE else MARIA_PROBE).isEmpty()) {
                (if (kind == DatabaseKind.POSTGRESQL) PG_DEMO else MARIA_DEMO).forEach { sql ->
                    c.createStatement().use { it.execute(sql) }
                }
            }
            block(c, Introspector.of(kind))
        }
    }

    private fun List<DbObject>.group(name: String) = single { it.name == name }

    private fun DbObject.child(name: String) = children.single { it.name == name }

    private fun ddls(
        c: Connection,
        i: Introspector,
        tree: List<DbObject>,
    ): Map<DbObject, String> {
        val all = mutableListOf<DbObject>()

        fun walk(o: DbObject) {
            if (o.kind != ObjectKind.GROUP) all += o
            o.children.forEach(::walk)
        }
        tree.forEach(::walk)
        return all.associateWith { i.ddl(c, it) }
    }

    @Test
    fun `postgresql schemas and tree`() =
        withDemo(DatabaseKind.POSTGRESQL) { c, i ->
            assertTrue("demo" in i.schemas(c, false) && "public" in i.schemas(c, false))
            assertTrue(i.schemas(c, false).none { it == "pg_catalog" || it == "information_schema" })
            assertTrue(i.schemas(c, true).containsAll(listOf("pg_catalog", "information_schema", "demo")))

            val tree = i.schema(c, "demo")
            assertEquals(
                listOf("tables", "views", "materialized views", "routines", "sequences", "types"),
                tree.map { it.name },
            )
            val orders = tree.group("tables").child("orders")
            assertEquals(
                listOf("id", "customer_id", "qty", "note", "keys", "foreign keys", "indexes", "checks", "triggers"),
                orders.children.map { it.name },
            )
            assertEquals("integer not null generated always as identity", orders.child("id").detail)
            assertEquals("integer not null default 1", orders.child("qty").detail)
            assertEquals(
                "(customer_id) → demo.customer(id)",
                orders
                    .child("foreign keys")
                    .children
                    .single()
                    .detail,
            )
            assertTrue(
                orders
                    .child("checks")
                    .children
                    .single()
                    .detail
                    .startsWith("CHECK"),
            )
            assertEquals(
                "before insert",
                orders
                    .child("triggers")
                    .children
                    .single()
                    .detail,
            )
            assertEquals(
                listOf("orders_note_idx", "orders_pkey"),
                orders.child("indexes").children.map { it.name },
            )
            assertEquals(
                listOf(ObjectKind.PRIMARY_KEY),
                orders.child("keys").children.map { it.kind },
            )
            val customerKeys =
                tree
                    .group("tables")
                    .child("customer")
                    .child("keys")
                    .children
            assertEquals(listOf(ObjectKind.PRIMARY_KEY, ObjectKind.UNIQUE), customerKeys.map { it.kind })
            assertEquals(
                listOf("id", "qty"),
                tree
                    .group("views")
                    .children
                    .single()
                    .children
                    .map { it.name },
            )
            assertEquals(
                ObjectKind.MATERIALIZED_VIEW,
                tree
                    .group("materialized views")
                    .children
                    .single()
                    .kind,
            )
            val routines = tree.group("routines").children
            assertEquals(listOf("f", "f", "p", "touch"), routines.map { it.name })
            val tables = tree.group("tables").children
            assertEquals(listOf("bookings", "customer", "ext", "measurements", "orders"), tables.map { it.name })
            val measurements = tables.single { it.name == "measurements" }
            assertEquals("RANGE (logdate)", measurements.detail)
            assertEquals(listOf("logdate", "value", "partitions"), measurements.children.map { it.name })
            val partitions = measurements.child("partitions").children
            assertEquals(
                listOf("measurements_2024", "measurements_2025", "measurements_ext"),
                partitions.map { it.name },
            )
            assertTrue(partitions.all { it.detail.startsWith("FOR VALUES FROM") && it.children.size >= 2 })
            assertEquals(listOf("logdate", "value", "indexes", "checks"), partitions[0].children.map { it.name })
            val bookingKeys = tables.single { it.name == "bookings" }.child("keys").children
            assertEquals(listOf(ObjectKind.PRIMARY_KEY, ObjectKind.UNIQUE), bookingKeys.map { it.kind })
            assertTrue(bookingKeys[1].detail.startsWith("EXCLUDE USING gist"), bookingKeys[1].detail)
            assertEquals("foreign, server files", tables.single { it.name == "ext" }.detail)
            assertEquals("tsrange not null", tables.single { it.name == "bookings" }.child("during").detail)
            assertEquals("text", orders.child("note").detail)
            assertEquals(
                "indexes",
                tree
                    .group("materialized views")
                    .children
                    .single()
                    .children
                    .last()
                    .name,
            )
            assertEquals(listOf("a integer", "a text"), routines.take(2).map { it.ref })
            assertEquals("(integer) → integer", routines[0].detail)
            assertEquals(ObjectKind.PROCEDURE, routines[2].kind)
            assertEquals("(integer)", routines[2].detail)
            val types = tree.group("types").children
            assertEquals(
                listOf(
                    ObjectKind.ENUM to "sad, happy",
                    ObjectKind.COMPOSITE_TYPE to "",
                    ObjectKind.DOMAIN to "integer",
                ),
                types.map { it.kind to it.detail },
            )
        }

    @Test
    fun `postgresql ddl of every node and the table ddl runs in a scratch schema`() =
        withDemo(DatabaseKind.POSTGRESQL) { c, i ->
            val tree = i.schema(c, "demo")
            ddls(c, i, tree).forEach { (obj, ddl) ->
                assertTrue(ddl.endsWith(";\n"), "${obj.kind} ${obj.name}: $ddl")
                assertTrue(ddl.contains(obj.name), "${obj.kind} ${obj.name}: $ddl")
            }
            val tables = tree.group("tables").children

            fun table(name: String) = tables.single { it.name == name }
            val measurements = table("measurements")
            val replay =
                listOf(
                    i.ddl(c, table("customer")),
                    i.ddl(c, tree.group("routines").children.single { it.name == "touch" }),
                    i.ddl(c, table("orders")),
                    i.ddl(c, table("bookings")),
                    i.ddl(c, measurements),
                ) +
                    measurements.child("partitions").children.map { i.ddl(c, it) } +
                    listOf(i.ddl(c, table("ext")), i.ddl(c, tree.group("materialized views").children.single()))
            assertTrue(i.ddl(c, table("customer")).startsWith("CREATE SEQUENCE \"demo\".\"customer_id_seq\""))
            assertTrue(i.ddl(c, measurements).contains("PARTITION BY RANGE (logdate)"))
            val p2024 = i.ddl(c, measurements.child("partitions").child("measurements_2024"))
            assertTrue("m24_value_idx" in p2024 && "m24_value_pos" in p2024 && "FOREIGN" !in p2024, p2024)
            val pExt = i.ddl(c, measurements.child("partitions").child("measurements_ext"))
            assertTrue(pExt.startsWith("CREATE FOREIGN TABLE") && "PARTITION OF" in pExt, pExt)
            assertTrue(i.ddl(c, table("ext")).contains("SERVER \"files\""))
            c.createStatement().use { st ->
                st.execute("drop schema if exists scratch cascade")
                st.execute("create schema scratch")
                replay.forEach { st.execute(it.replace("demo", "scratch")) }
            }
            val copy = i.schema(c, "scratch").group("tables")
            assertEquals(tables.map { it.name }, copy.children.map { it.name })
            assertEquals(table("orders").children.map { it.name }, copy.child("orders").children.map { it.name })
            assertEquals(
                measurements.child("partitions").children.map { it.name },
                copy
                    .child("measurements")
                    .child("partitions")
                    .children
                    .map { it.name },
            )
            val overloads =
                tree
                    .group("routines")
                    .children
                    .take(2)
                    .map { i.ddl(c, it) }
            assertTrue(overloads[0].contains("integer") && overloads[1].contains("text"), overloads.toString())
            val view = i.ddl(c, tree.group("views").children.single())
            assertTrue(view.startsWith("CREATE OR REPLACE VIEW \"demo\".\"open_orders\" AS"), view)
        }

    @Test
    fun `postgresql ddl of dropped objects is empty`() =
        withDemo(DatabaseKind.POSTGRESQL) { c, i ->
            val skipped = setOf(ObjectKind.SCHEMA, ObjectKind.GROUP, ObjectKind.EVENT)
            ObjectKind.entries.filter { it !in skipped }.forEach {
                val dropped = DbObject(it, "demo", "dropped", table = "dropped_table", ref = "a integer")
                assertEquals("", i.ddl(c, dropped), "$it")
            }
        }

    @Test
    fun `postgresql primary keys`() =
        withDemo(DatabaseKind.POSTGRESQL) { c, i ->
            assertEquals(listOf("id"), i.primaryKey(c, "demo", "orders"))
            assertEquals(emptyList(), i.primaryKey(c, "demo", "order_sums"))
        }

    @Test
    fun `mariadb schemas and tree`() =
        withDemo(DatabaseKind.MARIADB) { c, i ->
            assertTrue("demo" in i.schemas(c, false))
            assertTrue(i.schemas(c, false).none { it == "mysql" || it == "information_schema" })
            assertTrue(i.schemas(c, true).containsAll(listOf("mysql", "information_schema", "demo")))

            val tree = i.schema(c, "demo")
            assertEquals(listOf("tables", "views", "routines", "sequences", "events"), tree.map { it.name })
            val orders = tree.group("tables").child("orders")
            assertEquals(
                listOf("id", "customer_id", "qty", "note", "keys", "foreign keys", "indexes", "checks", "triggers"),
                orders.children.map { it.name },
            )
            assertEquals("int(11) not null auto_increment", orders.child("id").detail)
            assertEquals("int(11) not null default 1", orders.child("qty").detail)
            assertEquals(
                "(customer_id) → demo.customer(id) on delete cascade",
                orders
                    .child("foreign keys")
                    .children
                    .single()
                    .detail,
            )
            assertEquals(
                "`qty` > 0",
                orders
                    .child("checks")
                    .children
                    .single()
                    .detail,
            )
            assertEquals(
                "before insert",
                orders
                    .child("triggers")
                    .children
                    .single()
                    .detail,
            )
            assertTrue(orders.child("indexes").children.any { it.name == "orders_note_idx" && it.detail == "(note)" })
            assertEquals(
                listOf(ObjectKind.PRIMARY_KEY, ObjectKind.UNIQUE),
                tree
                    .group("tables")
                    .child("customer")
                    .child("keys")
                    .children
                    .map { it.kind },
            )
            val routines = tree.group("routines").children
            assertEquals(listOf("f", "f", "p"), routines.map { it.name })
            assertEquals(
                listOf(ObjectKind.FUNCTION, ObjectKind.PROCEDURE, ObjectKind.PROCEDURE),
                routines.map { it.kind },
            )
            assertEquals("(text)", routines[1].detail)
            assertEquals("varchar(50)", orders.child("note").detail)
            assertEquals("(int(11)) → int(11)", tree.group("routines").children[0].detail)
            assertEquals(
                "nightly",
                tree
                    .group("events")
                    .children
                    .single()
                    .name,
            )
        }

    @Test
    fun `mariadb ddl of every node`() =
        withDemo(DatabaseKind.MARIADB) { c, i ->
            val tree = i.schema(c, "demo")
            ddls(c, i, tree).forEach { (obj, ddl) ->
                assertTrue(ddl.endsWith(";\n"), "${obj.kind} ${obj.name}: $ddl")
                val needle = obj.table ?: obj.name
                assertTrue(ddl.contains(needle), "${obj.kind} ${obj.name}: $ddl")
            }
            val ordersDdl = i.ddl(c, tree.group("tables").child("orders"))
            assertTrue(ordersDdl.startsWith("CREATE TABLE `orders`"), ordersDdl)
            assertTrue(i.ddl(c, tree.group("events").children.single()).contains("CREATE"), "event")
        }

    @Test
    fun `mariadb ddl of dropped objects is empty`() =
        withDemo(DatabaseKind.MARIADB) { c, i ->
            listOf(
                ObjectKind.TABLE,
                ObjectKind.VIEW,
                ObjectKind.FUNCTION,
                ObjectKind.PROCEDURE,
                ObjectKind.TRIGGER,
                ObjectKind.EVENT,
                ObjectKind.SEQUENCE,
            ).forEach { assertEquals("", i.ddl(c, DbObject(it, "demo", "dropped")), "$it") }
        }

    @Test
    fun `mariadb primary keys`() =
        withDemo(DatabaseKind.MARIADB) { c, i ->
            assertEquals(listOf("id"), i.primaryKey(c, "demo", "orders"))
            assertEquals(emptyList(), i.primaryKey(c, "demo", "open_orders"))
        }

    private companion object {
        const val PG_PROBE = "select 1 from pg_namespace where nspname = 'demo'"
        const val MARIA_PROBE = "select 1 from information_schema.SCHEMATA where SCHEMA_NAME = 'demo'"

        val PG_DEMO =
            listOf(
                "create schema demo",
                "create table demo.customer(id serial primary key, email text not null unique, name text)",
                """create table demo.orders(
                     id integer generated always as identity primary key,
                     customer_id int not null references demo.customer(id),
                     qty int not null default 1 check (qty > 0),
                     note text)""",
                "create index orders_note_idx on demo.orders(note)",
                "create table demo.bookings(id serial primary key, during tsrange not null, " +
                    "exclude using gist (during with &&))",
                "create table demo.measurements(logdate date not null, value int) partition by range (logdate)",
                "create table demo.measurements_2024 partition of demo.measurements " +
                    "for values from ('2024-01-01') to ('2025-01-01')",
                "create table demo.measurements_2025 partition of demo.measurements " +
                    "for values from ('2025-01-01') to ('2026-01-01')",
                "create index m24_value_idx on demo.measurements_2024(value)",
                "alter table demo.measurements_2024 add constraint m24_value_pos check (value > 0)",
                "create extension if not exists file_fdw",
                "create server if not exists files foreign data wrapper file_fdw",
                "create foreign table demo.measurements_ext partition of demo.measurements " +
                    "for values from ('2026-01-01') to ('2027-01-01') server files " +
                    "options (filename '/etc/hostname', format 'text')",
                "create foreign table demo.ext(a int, b text) server files " +
                    "options (filename '/etc/hostname', format 'text')",
                "create function demo.touch() returns trigger language plpgsql as 'begin return new; end'",
                "create trigger orders_touch before insert on demo.orders for each row execute function demo.touch()",
                "create view demo.open_orders as select id, qty from demo.orders",
                "create materialized view demo.order_sums as select customer_id, sum(qty) as q from demo.orders group by 1",
                "create index order_sums_idx on demo.order_sums(customer_id)",
                "create function demo.f(a int) returns int language sql as 'select a'",
                "create function demo.f(a text) returns text language sql as 'select a'",
                "create procedure demo.p(x int) language sql as 'select 1'",
                "create sequence demo.seq1",
                "create type demo.mood as enum ('sad', 'happy')",
                "create domain demo.pos as int check (value > 0)",
                "create type demo.pair as (a int, b text)",
            )

        val MARIA_DEMO =
            listOf(
                "create database demo",
                "create table demo.customer(id int auto_increment primary key, email varchar(100) not null, " +
                    "name varchar(50), unique key uq_email (email))",
                "create table demo.orders(id int auto_increment primary key, customer_id int not null, " +
                    "qty int not null default 1, note varchar(50), " +
                    "constraint fk_cust foreign key (customer_id) references demo.customer(id) on delete cascade, " +
                    "constraint qty_pos check (qty > 0), index orders_note_idx (note))",
                "create trigger demo.orders_bi before insert on demo.orders for each row set new.qty = coalesce(new.qty, 1)",
                "create view demo.open_orders as select id, qty from demo.orders",
                "create function demo.f(a int) returns int deterministic return a",
                "create procedure demo.f(in y text) select y",
                "create procedure demo.p(in x int) select x",
                "create sequence demo.seq1",
                "create event demo.nightly on schedule every 1 day do select 1",
            )
    }
}
