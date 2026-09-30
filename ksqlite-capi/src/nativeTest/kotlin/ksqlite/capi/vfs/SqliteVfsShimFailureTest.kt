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
import ksqlite.capi.sqlite3_memory_used
import ksqlite.capi.sqlite3_open_v2
import ksqlite.capi.usingRealTempFile
import ksqlite.capi.vfs.FaultyTestVfs.Fault
import ksqlite.types.SqliteOpenFlag
import ksqlite.types.SqliteResultCode
import ksqlite.types.SqliteResultCode.OK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the VFS shim's failure paths, using [FaultyTestVfs] as the underlying VFS to inject
 * failures the default VFS never produces on its own. Leaks are detected via SQLite's own memory
 * accounting ([sqlite3_memory_used]), which covers the underlying VFS's per-file state.
 */
class SqliteVfsShimFailureTest {

    @Test
    fun endsFileLifecycleWhenCloseFails() {
        val closeResults = mutableListOf<SqliteResultCode>()

        runShimOverFaultyVfsTest(
            SqliteVfsShimListeners(didClose = { _, result -> closeResults += result })
        ) { shim, path ->
            val baseline = sqlite3_memory_used()

            FaultyTestVfs.fault = Fault.FAIL_CLOSE
            val db = open(path, shim)
            assertEquals(OK, sqlite3_exec(db, "PRAGMA user_version;", null, null, null))
            val file = assertNotNull(shim.file(db, "main"))
            file.context = "context"
            closeResults.clear()

            // SQLite ignores xClose's result (see sqlite3OsClose), so the connection still closes -
            // and the shim must end the file's lifecycle all the same.
            assertEquals(OK, sqlite3_close(db))
            FaultyTestVfs.fault = Fault.NONE

            assertTrue(closeResults.single() is SqliteResultCode.IOERR, "close results: $closeResults")
            assertFalse(file.isOpen)
            assertNull(file.context)
            assertEquals(baseline, sqlite3_memory_used(), "memory leaked by a failed xClose")
        }
    }

    @Test
    fun releasesUnderlyingFileWhenUnderlyingOpenFailsAfterOpening() {
        var openCount = 0

        runShimOverFaultyVfsTest(
            SqliteVfsShimListeners(didOpen = { openCount++ })
        ) { shim, path ->
            val baseline = sqlite3_memory_used()
            openCount = 0

            FaultyTestVfs.fault = Fault.FAIL_OPEN_AFTER_REAL_OPEN
            val outDb = sqlite3.OutputParam()
            val result = sqlite3_open_v2(path, outDb, SqliteOpenFlag.READWRITE, shim.name)
            FaultyTestVfs.fault = Fault.NONE
            outDb.value?.let { sqlite3_close(it) }

            assertEquals(SqliteResultCode.CANTOPEN, result)
            assertEquals(0, openCount, "a failed open must not be reported as opened")
            assertEquals(baseline, sqlite3_memory_used(), "memory leaked by a failed xOpen")
        }
    }

    private fun open(path: String, shim: SqliteVfsShim): sqlite3 {
        val outDb = sqlite3.OutputParam()
        assertEquals(OK, sqlite3_open_v2(path, outDb, SqliteOpenFlag.READWRITE, shim.name))
        return assertNotNull(outDb.value)
    }

    /**
     * Registers a shim over [FaultyTestVfs] reporting to [listeners] (with every event enabled),
     * creates a database at `path` through it, and runs one fault-free open/read/close cycle before
     * invoking [block] - so that any of SQLite's lazily allocated global state is already in place
     * when [block] records its memory baseline.
     */
    private fun runShimOverFaultyVfsTest(
        listeners: SqliteVfsShimListeners,
        block: (shim: SqliteVfsShim, path: String) -> Unit,
    ) = runSqliteWalTest { vfs ->
        FaultyTestVfs.register()

        try {
            val shim = assertNotNull(
                sqlite3_vfs_shim_register(
                    name = "ksqlite_vfs_shim_failure_test",
                    underlyingVfsName = FaultyTestVfs.NAME,
                    initiallyEnabledEvents = SqliteVfsShimEvent.entries.toSet(),
                    listeners = listeners,
                )
            )

            try {
                vfs.usingRealTempFile("vfs-shim-failure-test.sqlite") { path ->
                    val outDb = sqlite3.OutputParam()
                    assertEquals(
                        OK,
                        sqlite3_open_v2(path, outDb, SqliteOpenFlag.READWRITE or SqliteOpenFlag.CREATE, shim.name)
                    )
                    val db = assertNotNull(outDb.value)
                    assertEquals(OK, sqlite3_exec(db, "CREATE TABLE t(x INTEGER);", null, null, null))
                    assertEquals(OK, sqlite3_close(db))

                    val warmupDb = open(path, shim)
                    assertEquals(OK, sqlite3_exec(warmupDb, "PRAGMA user_version;", null, null, null))
                    assertEquals(OK, sqlite3_close(warmupDb))

                    block(shim, path)
                }
            } finally {
                assertEquals(OK, shim.unregister())
            }
        } finally {
            FaultyTestVfs.unregister()
        }
    }
}
