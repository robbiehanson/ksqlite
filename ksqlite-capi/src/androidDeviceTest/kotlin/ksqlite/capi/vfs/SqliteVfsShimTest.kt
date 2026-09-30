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

import ksqlite.capi.runSqliteWalTest
import ksqlite.capi.sqlite3
import ksqlite.capi.sqlite3_close
import ksqlite.capi.sqlite3_exec
import ksqlite.capi.sqlite3_open_v2
import ksqlite.capi.usingRealTempFile
import ksqlite.types.SqliteOpenFlag
import ksqlite.types.SqliteResultCode
import ksqlite.types.SqliteResultCode.OK
import ksqlite.types.vfs.SqliteLockLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SqliteVfsShimTest {

    @Test
    fun notifiesOnRead() {
        var readCount = 0

        withShim("read", READ_EVENTS, SqliteVfsShimListeners(didRead = { _, _, _, _ -> readCount++ })) { shim, path ->
            val db1 = openThrough(shim, path, create = true)
            exec(db1, "CREATE TABLE t(x INTEGER);")
            exec(db1, "INSERT INTO t VALUES (1);")
            assertEquals(OK, sqlite3_close(db1))

            // Reads may be served from the pager cache rather than hitting the VFS again, so only
            // start counting once we force a fresh connection with an empty cache.
            readCount = 0

            val db2 = openThrough(shim, path)
            exec(db2, "SELECT * FROM t;")
            assertEquals(OK, sqlite3_close(db2))

            assertTrue(readCount > 0, "expected at least one read notification, got $readCount")
        }
    }

    @Test
    fun reportsPerFileFilenameAndWalFlag() {
        val reads = mutableListOf<Pair<String?, Boolean>>()

        withShim(
            "per_file",
            READ_EVENTS,
            SqliteVfsShimListeners(didRead = { file, _, _, _ -> reads += file.filename to (SqliteOpenFlag.WAL in file.flags) })
        ) { shim, path ->
            val writer = openThrough(shim, path, create = true)

            try {
                exec(writer, "PRAGMA journal_mode=WAL;")
                exec(writer, "PRAGMA wal_autocheckpoint=0;")
                exec(writer, "CREATE TABLE t(x INTEGER);")
                exec(writer, "INSERT INTO t VALUES (1);")

                // The writer stays open, so its changes remain un-checkpointed in the WAL. A fresh
                // connection must then read both the main database file and the WAL.
                reads.clear()

                val reader = openThrough(shim, path)
                exec(reader, "SELECT * FROM t;")
                assertEquals(OK, sqlite3_close(reader))
            } finally {
                assertEquals(OK, sqlite3_close(writer))
            }

            val mainReads = reads.filter { !it.second }
            val walReads = reads.filter { it.second }

            assertTrue(mainReads.isNotEmpty(), "expected main-db reads, got $reads")
            assertTrue(walReads.isNotEmpty(), "expected WAL reads, got $reads")
            assertTrue(mainReads.all { it.first?.endsWith(".sqlite") == true }, "main-db reads: $mainReads")
            assertTrue(walReads.all { it.first?.endsWith(".sqlite-wal") == true }, "WAL reads: $walReads")
        }
    }

    @Test
    fun attributesReadsToTheFileActuallyRead() = runSqliteWalTest { vfs ->
        val reads = mutableListOf<String?>()

        val shim = register(
            "attribution",
            READ_EVENTS,
            SqliteVfsShimListeners(didRead = { file, _, _, _ -> reads += file.filename })
        )

        try {
            vfs.usingRealTempFile("vfs-shim-attribution-a-android.sqlite") { pathA ->
                vfs.usingRealTempFile("vfs-shim-attribution-b-android.sqlite") { pathB ->
                    for (path in listOf(pathA, pathB)) {
                        val db = openThrough(shim, path, create = true)
                        exec(db, "CREATE TABLE t(x INTEGER);")
                        exec(db, "INSERT INTO t VALUES (1);")
                        assertEquals(OK, sqlite3_close(db))
                    }

                    val db = openThrough(shim, pathA)

                    try {
                        // Loading the schemas reads page 1 of both files, but not A's table page.
                        // Reading that page afterwards - once B has been opened more recently than
                        // A - must still be attributed to A.
                        exec(db, "ATTACH '$pathB' AS b;")
                        reads.clear()
                        exec(db, "SELECT * FROM main.t;")
                    } finally {
                        assertEquals(OK, sqlite3_close(db))
                    }

                    // SQLite passes xOpen the canonicalized path, so compare by file name only.
                    val nameA = pathA.substringAfterLast('/')
                    assertTrue(reads.isNotEmpty(), "expected reads of A's table page")
                    assertTrue(reads.all { it?.endsWith("/$nameA") == true }, "expected only reads of $nameA, got $reads")
                }
            }
        } finally {
            assertEquals(OK, shim.unregister())
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

            // One SqliteVfsShimFile per open file, created once by xOpen - not once per read.
            assertTrue(files.size >= 2, "expected several reads, got ${files.size}")
            val first = files.first()
            assertTrue(files.all { it === first }, "expected one file instance, got $files")
        }
    }

    @Test
    fun throwingListenerIsIgnoredOnRead() = runThrowingListenerTest(mmap = false)

    @Test
    fun throwingListenerIsIgnoredOnFetch() = runThrowingListenerTest(mmap = true)

    /**
     * Opens a database through a shim whose read listeners throw, then checks that a query that
     * triggers them still succeeds, and that the connection stays usable afterwards.
     *
     * The throwing query only needs to read the table's own page: page 1 is already cached, and in
     * WAL mode (unlike rollback mode) starting a read transaction doesn't xRead the database header.
     * So with [mmap], that one page - and therefore the listener - goes through xFetch only, and
     * otherwise through xRead only. (The filler table keeps it from being the file's last page,
     * which the unix VFS never maps, as it requires 256 addressable bytes past a mapped page.)
     */
    private fun runThrowingListenerTest(mmap: Boolean) {
        var throwing = false
        var throwCount = 0

        val listeners = SqliteVfsShimListeners(
            didRead = { _, _, _, _ -> if (throwing && !mmap) { throwCount++; error("didRead failure") } },
            didFetch = { _, _, _, isMapped, _ -> if (throwing && mmap && isMapped) { throwCount++; error("didFetch failure") } },
        )

        withShim("throwing_$mmap", READ_EVENTS, listeners) { shim, path ->
            val db1 = openThrough(shim, path, create = true)
            exec(db1, "PRAGMA journal_mode=WAL;")
            exec(db1, "CREATE TABLE t(x INTEGER);")
            exec(db1, "INSERT INTO t VALUES (1);")
            exec(db1, "CREATE TABLE filler(x BLOB);")
            exec(db1, "INSERT INTO filler VALUES (zeroblob(20000));")
            assertEquals(OK, sqlite3_close(db1))

            val db2 = openThrough(shim, path)

            try {
                exec(db2, "PRAGMA mmap_size=${if (mmap) 1_000_000 else 0};")
                exec(db2, "PRAGMA user_version;")

                throwing = true
                val result = sqlite3_exec(db2, "SELECT * FROM t;", null, null, null)
                throwing = false

                assertTrue(throwCount > 0, "expected the listener to be invoked (and throw)")
                assertEquals(OK, result, "a throwing listener must not affect the call")
                exec(db2, "SELECT * FROM t;")
            } finally {
                throwing = false
                assertEquals(OK, sqlite3_close(db2))
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

            // The file's lifecycle must end even though its listener threw.
            assertTrue(closeCount > 0, "expected didClose to be invoked (and throw)")
            assertFalse(file.isOpen)
            assertNull(file.context)
        }
    }

    @Test
    fun sharedShimAttributesReadsToEachConnectionsContext() {
        val reads = mutableListOf<Pair<Any?, Boolean>>()

        withShim(
            "shared",
            READ_EVENTS,
            SqliteVfsShimListeners(didRead = { file, _, _, _ -> reads += file.context to (SqliteOpenFlag.WAL in file.flags) })
        ) { shim, path ->
            // Closing the last connection checkpoints, leaving table u only in the main file.
            val setup = openThrough(shim, path, create = true)
            exec(setup, "PRAGMA journal_mode=WAL;")
            exec(setup, "CREATE TABLE u(x INTEGER);")
            exec(setup, "INSERT INTO u VALUES (1);")
            assertEquals(OK, sqlite3_close(setup))

            val writer = openThrough(shim, path)
            val a = openThrough(shim, path)
            val b = openThrough(shim, path)

            try {
                exec(writer, "CREATE TABLE t(x INTEGER);")

                // Each connection's first read transaction opens its own WAL file, after which its
                // journal file can be looked up and labeled too.
                for ((db, label) in listOf(a to "A", b to "B")) {
                    assertNotNull(shim.file(db, "main")).context = label
                    exec(db, "PRAGMA user_version;")
                    assertNotNull(shim.journalFile(db, "main")).context = label
                }

                // Committing through the writer invalidates both readers' caches, so each has to
                // read again - table u's page from its own main file, and everything the writer
                // changed from its own WAL file.
                exec(writer, "INSERT INTO t VALUES (1);")

                for ((db, label) in listOf(a to "A", b to "B")) {
                    reads.clear()
                    exec(db, "SELECT * FROM t, u;")

                    assertTrue(reads.isNotEmpty(), "expected reads by $label")
                    assertTrue(reads.all { it.first == label }, "expected only $label's reads, got $reads")
                    assertTrue(reads.any { !it.second }, "expected main-file reads by $label, got $reads")
                    assertTrue(reads.any { it.second }, "expected WAL reads by $label, got $reads")
                }
            } finally {
                assertEquals(OK, sqlite3_close(a))
                assertEquals(OK, sqlite3_close(b))
                assertEquals(OK, sqlite3_close(writer))
            }
        }
    }

    @Test
    fun journalFileIsTheWalOnceOpened() = withShim("journal", emptySet(), SqliteVfsShimListeners()) { shim, path ->
        val setup = openThrough(shim, path, create = true)
        exec(setup, "PRAGMA journal_mode=WAL;")
        exec(setup, "CREATE TABLE t(x INTEGER);")
        assertEquals(OK, sqlite3_close(setup))

        val db = openThrough(shim, path)

        try {
            val main = assertNotNull(shim.file(db, "main"))
            assertTrue(SqliteOpenFlag.MAIN_DB in main.flags)
            assertTrue(main.filename?.endsWith("-android.sqlite") == true, "main: ${main.filename}")
            assertNull(shim.journalFile(db, "main"), "WAL file shouldn't be open before any read")

            exec(db, "PRAGMA user_version;")

            val wal = assertNotNull(shim.journalFile(db, "main"))
            assertTrue(SqliteOpenFlag.WAL in wal.flags)
            assertTrue(wal.filename?.endsWith("-android.sqlite-wal") == true, "wal: ${wal.filename}")
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

            // Rollback-journal mode: every read transaction re-reads the database header, so each
            // query below reads the main file (and only the main file) at least once.
            val db = openThrough(shim, path)

            try {
                val main = assertNotNull(shim.file(db, "main"))
                main.setEventsEnabled(READ_EVENTS, false)
                assertFalse(main.isEventEnabled(SqliteVfsShimEvent.READ))
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
                assertFalse(file.isEventEnabled(SqliteVfsShimEvent.FETCH))
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
        val reads = mutableListOf<SqliteVfsShimFile>()

        val listeners = SqliteVfsShimListeners(
            didOpen = { file ->
                opened += file
                // Only the main database file gets read notifications.
                if (SqliteOpenFlag.MAIN_DB in file.flags) file.setEventsEnabled(READ_EVENTS, true)
            },
            didRead = { file, _, _, _ -> reads += file },
        )

        withShim("did_open", emptySet(), listeners) { shim, path ->
            val writer = openThrough(shim, path, create = true)

            try {
                exec(writer, "PRAGMA journal_mode=WAL;")
                exec(writer, "PRAGMA wal_autocheckpoint=0;")
                exec(writer, "CREATE TABLE t(x INTEGER);")
                exec(writer, "INSERT INTO t VALUES (1);")

                opened.clear()
                reads.clear()

                val reader = openThrough(shim, path)
                exec(reader, "SELECT * FROM t;")
                assertEquals(OK, sqlite3_close(reader))
            } finally {
                assertEquals(OK, sqlite3_close(writer))
            }

            assertTrue(opened.any { SqliteOpenFlag.MAIN_DB in it.flags }, "expected the main file opened")
            assertTrue(opened.any { SqliteOpenFlag.WAL in it.flags }, "expected the WAL file opened")
            assertTrue(reads.isNotEmpty(), "expected main-file reads")
            assertTrue(reads.all { SqliteOpenFlag.MAIN_DB in it.flags }, "expected only main-file reads")
        }
    }

    @Test
    fun oneShotArmingFiresOncePerArmedFile() {
        val reports = mutableListOf<SqliteVfsShimFile>()

        // The original YapDatabase's xNotifyDidRead pattern: armed per transaction, and disarmed by
        // the notification itself, on the file that fired only.
        val onDidRead = { file: SqliteVfsShimFile ->
            reports += file
            file.setEventsEnabled(READ_EVENTS, false)
        }

        val listeners = SqliteVfsShimListeners(
            didRead = { file, _, _, _ -> onDidRead(file) },
            didFetch = { file, _, _, _, _ -> onDidRead(file) },
        )

        withShim("one_shot", emptySet(), listeners) { shim, path ->
            val setup = openThrough(shim, path, create = true)
            exec(setup, "PRAGMA journal_mode=WAL;")
            exec(setup, "CREATE TABLE u(x INTEGER);")
            exec(setup, "INSERT INTO u VALUES (1);")
            assertEquals(OK, sqlite3_close(setup))

            val writer = openThrough(shim, path)
            val reader = openThrough(shim, path)

            try {
                exec(writer, "CREATE TABLE t(x INTEGER);")
                exec(reader, "PRAGMA user_version;")

                val main = assertNotNull(shim.file(reader, "main"))
                val wal = assertNotNull(shim.journalFile(reader, "main"))

                for (round in 1..2) {
                    // Invalidates the reader's cache, so it reads both of its files again - several
                    // pages from each.
                    exec(writer, "INSERT INTO t VALUES ($round);")

                    main.setEventsEnabled(READ_EVENTS, true)
                    wal.setEventsEnabled(READ_EVENTS, true)
                    reports.clear()

                    exec(reader, "SELECT * FROM t, u;")

                    assertEquals(1, reports.count { it === main }, "round $round: main reports: $reports")
                    assertEquals(1, reports.count { it === wal }, "round $round: WAL reports: $reports")
                    assertFalse(main.isEventEnabled(SqliteVfsShimEvent.READ))
                    assertFalse(wal.isEventEnabled(SqliteVfsShimEvent.READ))
                }
            } finally {
                assertEquals(OK, sqlite3_close(reader))
                assertEquals(OK, sqlite3_close(writer))
            }
        }
    }

    @Test
    fun reportsEventArguments() {
        val events = mutableListOf<String>()

        fun ok(result: SqliteResultCode) = if (result == OK) "OK" else "$result"

        val listeners = SqliteVfsShimListeners(
            didOpen = { file -> events += "open:${if (SqliteOpenFlag.WAL in file.flags) "wal" else "other"}" },
            didClose = { _, result -> events += "close:${ok(result)}" },
            didRead = { _, amount, _, result -> events += "read:${amount > 0}:${ok(result)}" },
            didWrite = { _, amount, _, result -> events += "write:${amount > 0}:${ok(result)}" },
            didTruncate = { _, _, result -> events += "truncate:${ok(result)}" },
            didSync = { _, type, _, result -> events += "sync:${type != null}:${ok(result)}" },
            didFileSize = { _, size, result -> events += "fileSize:${size > 0}:${ok(result)}" },
            didLock = { _, level, result -> events += "lock:$level:${ok(result)}" },
            didUnlock = { _, level, result -> events += "unlock:$level:${ok(result)}" },
            didFileControl = { _, _, _ -> events += "fileControl" },
            didShmMap = { _, _, regionSize, _, result -> events += "shmMap:${regionSize > 0}:${ok(result)}" },
            didShmLock = { _, _, count, isLock, _, result -> events += "shmLock:${count > 0}:$isLock:${ok(result)}" },
            didShmBarrier = { _ -> events += "shmBarrier" },
            didShmUnmap = { _, _, result -> events += "shmUnmap:${ok(result)}" },
            didFetch = { _, _, _, isMapped, result -> events += "fetch:$isMapped:${ok(result)}" },
            didUnfetch = { _, _, isRelease, result -> events += "unfetch:$isRelease:${ok(result)}" },
            didDelete = { filename, _, result -> events += "delete:${filename?.endsWith("-wal")}:${ok(result)}" },
            didAccess = { _, flags, _, result -> events += "access:$flags:${ok(result)}" },
            didFullPathname = { filename, fullPathname, result ->
                val name = filename?.substringAfterLast('/')
                events += "fullPathname:${name != null && fullPathname?.endsWith(name) == true}:${ok(result)}"
            },
        )

        withShim("arguments", SqliteVfsShimEvent.entries.toSet(), listeners) { shim, path ->
            val setup = openThrough(shim, path, create = true)
            exec(setup, "PRAGMA journal_mode=WAL;")
            exec(setup, "CREATE TABLE t(x INTEGER);")
            exec(setup, "INSERT INTO t VALUES (1);")
            exec(setup, "CREATE TABLE filler(x BLOB);")
            exec(setup, "INSERT INTO filler VALUES (zeroblob(20000));")
            assertEquals(OK, sqlite3_close(setup))

            val db = openThrough(shim, path)
            exec(db, "PRAGMA mmap_size=1000000;")
            exec(db, "SELECT * FROM t;")
            exec(db, "INSERT INTO t VALUES (2);")
            exec(db, "PRAGMA wal_checkpoint(TRUNCATE);")
            assertEquals(OK, sqlite3_close(db))
        }

        // Every event listened to, except xCheckReservedLock, which SQLite only calls in contended
        // rollback-journal scenarios.
        val expected = listOf(
            "open:other", "open:wal", "close:OK",
            "read:true:OK", "write:true:OK", "truncate:OK", "sync:true:OK", "fileSize:true:OK",
            "lock:SHARED:OK", "lock:EXCLUSIVE:OK", "unlock:NONE:OK", "fileControl",
            "shmMap:true:OK", "shmLock:true:true:OK", "shmLock:true:false:OK", "shmBarrier", "shmUnmap:OK",
            "fetch:true:OK", "unfetch:true:OK",
            "delete:true:OK", "access:EXISTS:OK", "fullPathname:true:OK",
        )

        val missing = expected.filter { it !in events }
        assertTrue(missing.isEmpty(), "missing events: $missing\nall events: ${events.distinct()}")
    }

    @Test
    fun lookupOnlyReturnsThisShimsOwnFiles() = runSqliteWalTest { vfs ->
        val shim = register("owner", emptySet(), SqliteVfsShimListeners())
        val otherShim = register("other", emptySet(), SqliteVfsShimListeners())

        try {
            vfs.usingRealTempFile("vfs-shim-owner-test-android.sqlite") { path ->
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
            assertTrue(file.isOpen)

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
    fun returnsNullForUnknownUnderlyingVfs() = runSqliteWalTest {
        val shim = sqlite3_vfs_shim_register(
            name = "ksqlite_vfs_shim_test_unknown_android",
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
            name = "ksqlite_vfs_shim_test_${name}_android",
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
    ) = runSqliteWalTest { vfs ->
        val shim = register(name, initiallyEnabledEvents, listeners)

        try {
            vfs.usingRealTempFile("vfs-shim-$name-test-android.sqlite") { path -> block(shim, path) }
        } finally {
            assertEquals(OK, shim.unregister())
        }
    }

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
