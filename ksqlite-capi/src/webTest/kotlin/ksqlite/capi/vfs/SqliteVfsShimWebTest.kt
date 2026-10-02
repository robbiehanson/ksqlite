/*
 * Copyright (C) 2026 Maanrifa Bacar Ali
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ksqlite.capi.vfs

import ksqlite.capi.findVfs
import ksqlite.capi.runSqliteTest
import ksqlite.capi.sqlite3
import ksqlite.capi.sqlite3_close
import ksqlite.capi.sqlite3_exec
import ksqlite.capi.sqlite3_open_v2
import ksqlite.capi.usingRealTempFile
import ksqlite.types.SqliteOpenFlag
import ksqlite.types.SqliteResultCode
import ksqlite.types.SqliteResultCode.OK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The VFS shim's tests for the web, where the default VFS ("unix-none", over Emscripten's in-memory
 * filesystem) has no shared memory and no memory-mapped I/O. So these use rollback-journal mode, or
 * exclusive-locking WAL where a WAL file is needed, and SHM_* / FETCH / UNFETCH events never fire.
 * See jvmNativeTest's SqliteVfsShimTest for the equivalent tests over a WAL-capable VFS.
 */
class SqliteVfsShimWebTest {

    @Test
    fun notifiesOnRead() {
        var readCount = 0

        withShim("read", READ_EVENTS, SqliteVfsShimListeners(didRead = { _, _, _, _ -> readCount++ })) { shim, path ->
            val db1 = openThrough(shim, path, create = true)
            exec(db1, "CREATE TABLE t(x INTEGER);")
            exec(db1, "INSERT INTO t VALUES (1);")
            assertEquals(OK, sqlite3_close(db1))

            readCount = 0

            val db2 = openThrough(shim, path)
            exec(db2, "SELECT * FROM t;")
            assertEquals(OK, sqlite3_close(db2))

            assertTrue(readCount > 0, "expected at least one read notification, got $readCount")
        }
    }

    @Test
    fun reportsSameFileInstanceAcrossReads() {
        val files = mutableListOf<SqliteVfsShimFile>()

        withShim("instance", READ_EVENTS, SqliteVfsShimListeners(didRead = { file, _, _, _ -> files += file })) { shim, path ->
            val db1 = openThrough(shim, path, create = true)
            exec(db1, "CREATE TABLE t(x BLOB);")
            exec(db1, "INSERT INTO t VALUES (zeroblob(20000));")
            assertEquals(OK, sqlite3_close(db1))

            files.clear()

            val db2 = openThrough(shim, path)
            exec(db2, "SELECT * FROM t;")
            assertEquals(OK, sqlite3_close(db2))

            assertTrue(files.size >= 2, "expected several reads, got ${files.size}")
            val first = files.first()
            assertTrue(files.all { it === first }, "expected one file instance")
        }
    }

    @Test
    fun attributesReadsToTheFileActuallyRead() = runSqliteTest {
        val reads = mutableListOf<String?>()
        val shim = register("attribution", READ_EVENTS, SqliteVfsShimListeners(didRead = { file, _, _, _ -> reads += file.filename }))

        try {
            findVfs().usingRealTempFile("vfs-shim-attribution-a-web.sqlite") { pathA ->
                findVfs().usingRealTempFile("vfs-shim-attribution-b-web.sqlite") { pathB ->
                    for (path in listOf(pathA, pathB)) {
                        val db = openThrough(shim, path, create = true)
                        exec(db, "CREATE TABLE t(x INTEGER);")
                        exec(db, "INSERT INTO t VALUES (1);")
                        assertEquals(OK, sqlite3_close(db))
                    }

                    val db = openThrough(shim, pathA)

                    try {
                        // Loading the schemas reads page 1 of both files, but not A's table page.
                        exec(db, "ATTACH '$pathB' AS b;")
                        reads.clear()
                        exec(db, "SELECT * FROM main.t;")
                    } finally {
                        assertEquals(OK, sqlite3_close(db))
                    }

                    val nameA = fileName(pathA)
                    assertTrue(reads.isNotEmpty(), "expected reads of A")
                    assertTrue(reads.all { fileName(it) == nameA }, "expected only reads of $nameA, got $reads")
                }
            }
        } finally {
            assertEquals(OK, shim.unregister())
        }
    }

    @Test
    fun sharedShimAttributesReadsToEachConnectionsContext() {
        val reads = mutableListOf<Any?>()

        withShim("shared", READ_EVENTS, SqliteVfsShimListeners(didRead = { file, _, _, _ -> reads += file.context })) { shim, path ->
            val setup = openThrough(shim, path, create = true)
            exec(setup, "CREATE TABLE t(x INTEGER);")
            exec(setup, "INSERT INTO t VALUES (1);")
            assertEquals(OK, sqlite3_close(setup))

            val a = openThrough(shim, path)
            val b = openThrough(shim, path)

            try {
                assertNotNull(shim.file(a, "main")).context = "A"
                assertNotNull(shim.file(b, "main")).context = "B"

                // Rollback-journal mode: every read transaction re-reads the database header.
                for ((db, label) in listOf(a to "A", b to "B", a to "A")) {
                    reads.clear()
                    exec(db, "SELECT * FROM t;")
                    assertTrue(reads.isNotEmpty(), "expected reads by $label")
                    assertTrue(reads.all { it == label }, "expected only $label's reads, got $reads")
                }
            } finally {
                assertEquals(OK, sqlite3_close(a))
                assertEquals(OK, sqlite3_close(b))
            }
        }
    }

    @Test
    fun journalFileIsTheWalOnceOpened() = withShim("journal", emptySet(), SqliteVfsShimListeners()) { shim, path ->
        val db = openThrough(shim, path, create = true)

        try {
            val main = assertNotNull(shim.file(db, "main"))
            assertTrue(SqliteOpenFlag.MAIN_DB in main.flags)
            assertNull(shim.journalFile(db, "main"), "no journal before any write")

            // unix-none has no shared memory, so WAL needs exclusive locking.
            exec(db, "PRAGMA locking_mode=EXCLUSIVE;")
            exec(db, "PRAGMA journal_mode=WAL;")
            exec(db, "CREATE TABLE t(x INTEGER);")

            val wal = assertNotNull(shim.journalFile(db, "main"))
            assertTrue(SqliteOpenFlag.WAL in wal.flags)
            assertTrue(wal.filename?.endsWith("-web.sqlite-wal") == true, "wal: ${wal.filename}")
            assertSame(main, shim.file(db, "main"))
            assertSame(wal, shim.journalFile(db, "main"))
        } finally {
            assertEquals(OK, sqlite3_close(db))
        }
    }

    @Test
    fun disablingEventsSkipsTheListener() {
        var readCount = 0

        withShim("notify", READ_EVENTS, SqliteVfsShimListeners(didRead = { _, _, _, _ -> readCount++ })) { shim, path ->
            val setup = openThrough(shim, path, create = true)
            exec(setup, "CREATE TABLE t(x INTEGER);")
            assertEquals(OK, sqlite3_close(setup))

            val db = openThrough(shim, path)

            try {
                val main = assertNotNull(shim.file(db, "main"))
                main.setEventsEnabled(READ_EVENTS, false)
                readCount = 0
                exec(db, "SELECT * FROM t;")
                assertEquals(0, readCount, "expected no notifications while disabled")

                main.setEventEnabled(SqliteVfsShimEvent.READ, true)
                exec(db, "SELECT * FROM t;")
                assertTrue(readCount > 0, "expected notifications once re-enabled")
            } finally {
                assertEquals(OK, sqlite3_close(db))
            }
        }
    }

    @Test
    fun filesStartWithInitiallyEnabledEvents() {
        var readCount = 0
        val listeners = SqliteVfsShimListeners(didRead = { _, _, _, _ -> readCount++ })

        withShim("initial_some", setOf(SqliteVfsShimEvent.READ), listeners) { shim, path ->
            val db = openThrough(shim, path, create = true)

            try {
                val file = assertNotNull(shim.file(db, "main"))
                assertTrue(file.isEventEnabled(SqliteVfsShimEvent.READ))
                assertFalse(file.isEventEnabled(SqliteVfsShimEvent.WRITE))
            } finally {
                assertEquals(OK, sqlite3_close(db))
            }
        }

        withShim("initial_none", emptySet(), listeners) { shim, path ->
            val setup = openThrough(shim, path, create = true)
            exec(setup, "CREATE TABLE t(x INTEGER);")
            assertEquals(OK, sqlite3_close(setup))

            readCount = 0
            val db = openThrough(shim, path)

            try {
                exec(db, "SELECT * FROM t;")
                assertEquals(0, readCount, "expected no notifications before any event is enabled")

                assertNotNull(shim.file(db, "main")).setEventsEnabled(READ_EVENTS, true)
                exec(db, "SELECT * FROM t;")
                assertTrue(readCount > 0, "expected notifications once enabled")
            } finally {
                assertEquals(OK, sqlite3_close(db))
            }
        }
    }

    @Test
    fun didOpenFiresRegardlessOfEventsAndCanAdjustThem() {
        val opened = mutableListOf<SqliteVfsShimFile>()
        val writes = mutableListOf<SqliteVfsShimFile>()

        val listeners = SqliteVfsShimListeners(
            didOpen = { file ->
                opened += file
                // Only the main database file gets write notifications, not its rollback journal.
                if (SqliteOpenFlag.MAIN_DB in file.flags) file.setEventEnabled(SqliteVfsShimEvent.WRITE, true)
            },
            didWrite = { file, _, _, _ -> writes += file },
        )

        withShim("did_open", emptySet(), listeners) { shim, path ->
            val db = openThrough(shim, path, create = true)

            try {
                exec(db, "CREATE TABLE t(x INTEGER);")
                exec(db, "INSERT INTO t VALUES (1);")
            } finally {
                assertEquals(OK, sqlite3_close(db))
            }

            assertTrue(opened.any { SqliteOpenFlag.MAIN_DB in it.flags }, "expected the main file opened")
            assertTrue(opened.any { SqliteOpenFlag.MAIN_JOURNAL in it.flags }, "expected the journal opened")
            assertTrue(writes.isNotEmpty(), "expected main-file writes")
            assertTrue(writes.all { SqliteOpenFlag.MAIN_DB in it.flags }, "expected only main-file writes")
        }
    }

    @Test
    fun oneShotArmingFiresOncePerArming() {
        val reports = mutableListOf<SqliteVfsShimFile>()

        // The original YapDatabase's xNotifyDidRead pattern: armed per transaction, and disarmed by
        // the notification itself.
        val listeners = SqliteVfsShimListeners(
            didRead = { file, _, _, _ ->
                reports += file
                file.setEventsEnabled(READ_EVENTS, false)
            },
        )

        withShim("one_shot", emptySet(), listeners) { shim, path ->
            val setup = openThrough(shim, path, create = true)
            exec(setup, "CREATE TABLE t(x BLOB);")
            exec(setup, "INSERT INTO t VALUES (zeroblob(20000));")
            assertEquals(OK, sqlite3_close(setup))

            val db = openThrough(shim, path)

            try {
                val main = assertNotNull(shim.file(db, "main"))

                for (round in 1..2) {
                    main.setEventsEnabled(READ_EVENTS, true)
                    reports.clear()

                    // Several reads (header, schema, the blob's overflow pages), several statements.
                    exec(db, "SELECT * FROM t;")
                    exec(db, "SELECT * FROM t;")

                    assertEquals(1, reports.size, "round $round: expected exactly one report")
                    assertFalse(main.isEventEnabled(SqliteVfsShimEvent.READ))
                }
            } finally {
                assertEquals(OK, sqlite3_close(db))
            }
        }
    }

    @Test
    fun throwingListenerIsIgnored() {
        var throwing = false
        var throwCount = 0

        val listeners = SqliteVfsShimListeners(
            didRead = { _, _, _, _ -> if (throwing) { throwCount++; error("didRead failure") } },
        )

        withShim("throwing", READ_EVENTS, listeners) { shim, path ->
            val setup = openThrough(shim, path, create = true)
            exec(setup, "CREATE TABLE t(x INTEGER);")
            exec(setup, "INSERT INTO t VALUES (1);")
            assertEquals(OK, sqlite3_close(setup))

            val db = openThrough(shim, path)

            try {
                throwing = true
                val result = sqlite3_exec(db, "SELECT * FROM t;", null, null, null)
                throwing = false

                assertTrue(throwCount > 0, "expected the listener to be invoked (and throw)")
                assertEquals(OK, result, "a throwing listener must not affect the call")
                exec(db, "SELECT * FROM t;")
            } finally {
                throwing = false
                assertEquals(OK, sqlite3_close(db))
            }
        }
    }

    @Test
    fun throwingCloseListenerStillClosesTheFile() {
        var closeCount = 0

        withShim(
            "throwing_close",
            setOf(SqliteVfsShimEvent.CLOSE),
            SqliteVfsShimListeners(didClose = { _, _ -> closeCount++; error("didClose failure") })
        ) { shim, path ->
            val db = openThrough(shim, path, create = true)
            val file = assertNotNull(shim.file(db, "main"))
            file.context = "context"

            assertEquals(OK, sqlite3_close(db))

            assertTrue(closeCount > 0, "expected didClose to be invoked (and throw)")
            assertFalse(file.isOpen)
            assertNull(file.context)
        }
    }

    @Test
    fun reportsEventArguments() {
        val events = mutableListOf<String>()

        fun ok(result: SqliteResultCode) = if (result == OK) "OK" else "$result"

        val listeners = SqliteVfsShimListeners(
            didOpen = { file -> events += "open:${if (SqliteOpenFlag.MAIN_JOURNAL in file.flags) "journal" else "other"}" },
            didClose = { _, result -> events += "close:${ok(result)}" },
            didRead = { _, amount, _, result -> events += "read:${amount > 0}:${ok(result)}" },
            didWrite = { _, amount, _, result -> events += "write:${amount > 0}:${ok(result)}" },
            didTruncate = { _, _, result -> events += "truncate:${ok(result)}" },
            didSync = { _, type, _, result -> events += "sync:${type != null}:${ok(result)}" },
            didFileSize = { _, _, result -> events += "fileSize:${ok(result)}" },
            didLock = { _, level, result -> events += "lock:$level:${ok(result)}" },
            didUnlock = { _, level, result -> events += "unlock:$level:${ok(result)}" },
            didFileControl = { _, _, _ -> events += "fileControl" },
            didDelete = { filename, _, result -> events += "delete:${filename?.endsWith("-journal")}:${ok(result)}" },
            didAccess = { _, flags, _, result -> events += "access:$flags:${ok(result)}" },
            didFullPathname = { filename, fullPathname, result ->
                val name = fileName(filename)
                events += "fullPathname:${name != null && fullPathname?.endsWith(name) == true}:${ok(result)}"
            },
        )

        withShim("arguments", SqliteVfsShimEvent.entries.toSet(), listeners) { shim, path ->
            val setup = openThrough(shim, path, create = true)
            exec(setup, "CREATE TABLE t(x INTEGER);")
            exec(setup, "INSERT INTO t VALUES (1);")
            assertEquals(OK, sqlite3_close(setup))

            val db = openThrough(shim, path)
            exec(db, "SELECT * FROM t;")
            // TRUNCATE mode truncates the journal at commit; DELETE mode deletes it.
            exec(db, "PRAGMA journal_mode=TRUNCATE;")
            exec(db, "INSERT INTO t VALUES (2);")
            exec(db, "PRAGMA journal_mode=DELETE;")
            exec(db, "INSERT INTO t VALUES (3);")
            assertEquals(OK, sqlite3_close(db))
        }

        // Everything the web's VFS can produce. SHM_* and FETCH/UNFETCH need shared memory and
        // memory-mapped I/O, which unix-none lacks; xCheckReservedLock needs lock contention.
        val expected = listOf(
            "open:other", "open:journal", "close:OK",
            "read:true:OK", "write:true:OK", "truncate:OK", "sync:true:OK", "fileSize:OK",
            "lock:SHARED:OK", "lock:EXCLUSIVE:OK", "unlock:NONE:OK", "fileControl",
            "delete:true:OK", "access:EXISTS:OK", "fullPathname:true:OK",
        )

        val missing = expected.filter { it !in events }
        assertTrue(missing.isEmpty(), "missing events: $missing\nall events: ${events.distinct()}")
    }

    @Test
    fun lookupOnlyReturnsThisShimsOwnFiles() = runSqliteTest {
        val shim = register("owner", emptySet(), SqliteVfsShimListeners())
        val otherShim = register("other", emptySet(), SqliteVfsShimListeners())

        try {
            findVfs().usingRealTempFile("vfs-shim-owner-web.sqlite") { path ->
                val throughShim = openThrough(shim, path, create = true)
                val outPlain = sqlite3.OutputParam()
                assertEquals(OK, sqlite3_open_v2(path, outPlain, SqliteOpenFlag.READWRITE, null))
                val plain = assertNotNull(outPlain.value)

                try {
                    assertNotNull(shim.file(throughShim, "main"))
                    assertNull(otherShim.file(throughShim, "main"), "another shim's file")
                    assertNull(shim.file(plain, "main"), "a file opened through the default VFS")
                    assertNull(shim.file(throughShim, "not_a_schema"), "an unknown schema")
                } finally {
                    assertEquals(OK, sqlite3_close(plain))
                    assertEquals(OK, sqlite3_close(throughShim))
                }
            }
        } finally {
            assertEquals(OK, otherShim.unregister())
            assertEquals(OK, shim.unregister())
        }
    }

    @Test
    fun fileIsClosedWithItsConnection() {
        val closed = mutableListOf<SqliteVfsShimFile>()

        withShim(
            "closed",
            setOf(SqliteVfsShimEvent.CLOSE),
            SqliteVfsShimListeners(didClose = { file, _ -> closed += file; assertTrue(file.isOpen) })
        ) { shim, path ->
            val db = openThrough(shim, path, create = true)
            val file = assertNotNull(shim.file(db, "main"))
            file.context = "context"

            assertEquals(OK, sqlite3_close(db))

            assertTrue(closed.any { it === file }, "expected didClose for the main file")
            assertFalse(file.isOpen)
            assertNull(file.context)
            file.context = "ignored"
            file.setEventsEnabled(READ_EVENTS, true)
            assertNull(file.context)
            assertFalse(file.isEventEnabled(SqliteVfsShimEvent.READ))
        }
    }

    @Test
    fun returnsNullForUnknownUnderlyingVfs() = runSqliteTest {
        val shim = sqlite3_vfs_shim_register(
            name = "ksqlite_vfs_shim_web_test_unknown",
            underlyingVfsName = "not_a_real_vfs_name",
            initiallyEnabledEvents = emptySet(),
            listeners = SqliteVfsShimListeners(),
        )

        assertNull(shim)
    }

    private fun register(
        name: String,
        initiallyEnabledEvents: Set<SqliteVfsShimEvent>,
        listeners: SqliteVfsShimListeners,
    ): SqliteVfsShim = assertNotNull(
        sqlite3_vfs_shim_register(
            name = "ksqlite_vfs_shim_web_test_$name",
            underlyingVfsName = null,
            initiallyEnabledEvents = initiallyEnabledEvents,
            listeners = listeners,
        )
    )

    /** Registers a shim, runs [block] with it and a fresh temp database path, then unregisters. */
    private fun withShim(
        name: String,
        initiallyEnabledEvents: Set<SqliteVfsShimEvent>,
        listeners: SqliteVfsShimListeners,
        block: (shim: SqliteVfsShim, path: String) -> Unit,
    ) = runSqliteTest {
        val shim = register(name, initiallyEnabledEvents, listeners)

        try {
            findVfs().usingRealTempFile("vfs-shim-$name-web.sqlite") { path -> block(shim, path) }
        } finally {
            assertEquals(OK, shim.unregister())
        }
    }

    /** The last component of [path], whichever separator the platform uses (`/`, or `\` on Windows). */
    private fun fileName(path: String?): String? = path?.substringAfterLast('/')?.substringAfterLast('\\')

    private fun openThrough(shim: SqliteVfsShim, path: String, create: Boolean = false): sqlite3 {
        val flags = if (create) SqliteOpenFlag.READWRITE or SqliteOpenFlag.CREATE else SqliteOpenFlag.READWRITE
        val outDb = sqlite3.OutputParam()
        assertEquals(OK, sqlite3_open_v2(path, outDb, flags, shim.name))
        return assertNotNull(outDb.value)
    }

    private fun exec(db: sqlite3, sql: String) {
        assertEquals(OK, sqlite3_exec(db, sql, null, null, null), sql)
    }

    private companion object {
        val READ_EVENTS = setOf(SqliteVfsShimEvent.READ, SqliteVfsShimEvent.FETCH)
    }
}
