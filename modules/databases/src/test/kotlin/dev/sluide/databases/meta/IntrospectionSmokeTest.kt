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
            val customer = i.ddl(c, tables.single { it.name == "customer" })
            val orders = i.ddl(c, tables.single { it.name == "orders" })
            val touch = i.ddl(c, tree.group("routines").children.single { it.name == "touch" })
            // serial's default refers to its sequence, which is a node of its own
            val serial = i.ddl(c, tree.group("sequences").child("customer_id_seq"))
            c.createStatement().use { st ->
                st.execute("drop schema if exists scratch cascade")
                st.execute("create schema scratch")
                listOf(serial, customer, touch, orders).forEach { st.execute(it.replace("demo", "scratch")) }
            }
            val copy = i.schema(c, "scratch").group("tables")
            assertEquals(listOf("customer", "orders"), copy.children.map { it.name })
            assertEquals(
                tables.single { it.name == "orders" }.children.map { it.name },
                copy.child("orders").children.map { it.name },
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
            assertEquals(listOf("f", "p"), tree.group("routines").children.map { it.name })
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
                "create function demo.touch() returns trigger language plpgsql as 'begin return new; end'",
                "create trigger orders_touch before insert on demo.orders for each row execute function demo.touch()",
                "create view demo.open_orders as select id, qty from demo.orders",
                "create materialized view demo.order_sums as select customer_id, sum(qty) as q from demo.orders group by 1",
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
                "create procedure demo.p(in x int) select x",
                "create sequence demo.seq1",
                "create event demo.nightly on schedule every 1 day do select 1",
            )
    }
}
