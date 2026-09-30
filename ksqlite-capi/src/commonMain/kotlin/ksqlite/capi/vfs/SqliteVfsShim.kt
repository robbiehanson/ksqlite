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
@file:Suppress("ClassName")

package ksqlite.capi.vfs

import ksqlite.capi.sqlite3
import ksqlite.capi.sqlite3_log
import ksqlite.types.SqliteAccessFlag
import ksqlite.types.SqliteOpenFlag
import ksqlite.types.SqliteResultCode
import ksqlite.types.internal.convertResultCode
import ksqlite.types.vfs.SqliteLockLevel
import ksqlite.types.vfs.SqliteSyncType

/**
 * The file events a VFS shim can report, each of which can be enabled or disabled per file (see
 * [SqliteVfsShimFile.setEventEnabled]).
 */
public enum class SqliteVfsShimEvent(internal val code: Int) {
    CLOSE(VFS_SHIM_EVENT_CLOSE),
    READ(VFS_SHIM_EVENT_READ),
    WRITE(VFS_SHIM_EVENT_WRITE),
    TRUNCATE(VFS_SHIM_EVENT_TRUNCATE),
    SYNC(VFS_SHIM_EVENT_SYNC),
    FILE_SIZE(VFS_SHIM_EVENT_FILE_SIZE),
    LOCK(VFS_SHIM_EVENT_LOCK),
    UNLOCK(VFS_SHIM_EVENT_UNLOCK),
    CHECK_RESERVED_LOCK(VFS_SHIM_EVENT_CHECK_RESERVED_LOCK),
    FILE_CONTROL(VFS_SHIM_EVENT_FILE_CONTROL),
    SHM_MAP(VFS_SHIM_EVENT_SHM_MAP),
    SHM_LOCK(VFS_SHIM_EVENT_SHM_LOCK),
    SHM_BARRIER(VFS_SHIM_EVENT_SHM_BARRIER),
    SHM_UNMAP(VFS_SHIM_EVENT_SHM_UNMAP),
    FETCH(VFS_SHIM_EVENT_FETCH),
    UNFETCH(VFS_SHIM_EVENT_UNFETCH);

    internal val bit: Int
        get() = 1 shl code
}

/**
 * A file opened through a VFS shim - a database, WAL, journal, or temporary file - valid from
 * SQLite's xOpen of it until its xClose. Look up a connection's own files via [SqliteVfsShim.file]
 * and [SqliteVfsShim.journalFile].
 *
 * [context] and the file's enabled events are plain, unsynchronized state belonging to the
 * connection that opened the file (a file is never shared between connections): only change them
 * under the same serialization the connection itself requires - that is, never while another
 * thread is running an SQLite call on that connection. Between calls, or from within a listener
 * (which runs inside that connection's own call), is always fine.
 */
public class SqliteVfsShimFile internal constructor(
    private val pointer: Long,
    /** The filename SQLite opened this file under, or `null` for an anonymous temporary file. */
    public val filename: String?,
    /** The flags SQLite opened this file with, e.g. [SqliteOpenFlag.MAIN_DB] or [SqliteOpenFlag.WAL]. */
    public val flags: SqliteOpenFlag.Vfs,
    // Mirrors the native file's own event mask, which is what the shim actually checks. Every
    // change goes through setEventsEnabled, so the mirror never needs reading back.
    private var events: Int,
) {

    private var open: Boolean = true

    /** Whether this file is still open. Once `false`, changing the file's state has no effect. */
    public val isOpen: Boolean
        get() = open

    /**
     * Arbitrary caller-owned data - typically, whatever object owns the connection that opened
     * this file. `null` until set, and reset to `null` when the file closes.
     */
    public var context: Any? = null
        set(value) {
            if (open) field = value
        }

    /** Whether [event] is currently reported for this file (given a listener for it). */
    public fun isEventEnabled(event: SqliteVfsShimEvent): Boolean = (events and event.bit) != 0

    /** Enables or disables reporting [event] for this file. */
    public fun setEventEnabled(event: SqliteVfsShimEvent, enabled: Boolean) {
        setEventMask(event.bit, enabled)
    }

    /** Enables or disables reporting all of [events] for this file, in one update. */
    public fun setEventsEnabled(events: Set<SqliteVfsShimEvent>, enabled: Boolean) {
        setEventMask(events.fold(0) { mask, event -> mask or event.bit }, enabled)
    }

    private fun setEventMask(mask: Int, enabled: Boolean) {
        if (!open) return

        val updated = if (enabled) events or mask else events and mask.inv()

        if (updated != events) {
            events = updated
            vfsShimFileSetEvents(pointer, updated)
        }
    }

    internal fun markClosed() {
        context = null
        events = 0
        open = false
    }
}

/**
 * A VFS shim registered via [sqlite3_vfs_shim_register].
 *
 * The shim must be [unregister]ed by the owner when no longer required. It must not be
 * unregistered while any database connection opened through it (by name, as the `vfs` parameter
 * to `sqlite3_open_v2`) is still open.
 */
public class SqliteVfsShim internal constructor(
    /**
     * The name this shim was registered under. Pass this as the `vfs` parameter to
     * `sqlite3_open_v2` to open a database through this shim.
     */
    public val name: String,
    private val vfsPointer: Long,
) {

    public fun unregister(): SqliteResultCode = vfsShimUnregister(vfsPointer)

    /**
     * Returns [db]'s own open database file for [schema] (`"main"`, `"temp"`, or an attached
     * database's name), or `null` if that file isn't open or wasn't opened through this shim.
     */
    public fun file(db: sqlite3, schema: String): SqliteVfsShimFile? =
        vfsShimFileLookup(vfsPointer, db, schema, journal = false)

    /**
     * Returns [db]'s own open journal file for [schema]: its WAL file in WAL mode, otherwise its
     * rollback journal. `null` if that file isn't currently open - SQLite opens a WAL file lazily,
     * on the connection's first read transaction - or wasn't opened through this shim.
     */
    public fun journalFile(db: sqlite3, schema: String): SqliteVfsShimFile? =
        vfsShimFileLookup(vfsPointer, db, schema, journal = true)
}

/**
 * Registers a VFS shim named [name] over [underlyingVfsName] (or the default VFS if `null`): a
 * thin VFS that forwards every VFS and file I/O call to the underlying VFS unchanged, and reports
 * each call to [listeners] afterwards. It's a pure observer: SQLite always gets the underlying
 * VFS's own results, and listeners never see or touch file contents.
 *
 * File events are reported only while enabled on the file concerned. Every file opened through the
 * shim starts with exactly [initiallyEnabledEvents] enabled, before any I/O on it; a
 * [SqliteVfsShimListeners.didOpen] listener (always invoked, whatever the file's events) can then
 * adjust them per file, e.g. by its open flags. A single shim can serve any number of connections:
 * [SqliteVfsShim.file]/[SqliteVfsShim.journalFile] and [SqliteVfsShimFile.context] tell them apart.
 *
 * This is the "vfstrace"-style shim technique described in the SQLite docs.
 *
 * Returns `null` if [underlyingVfsName] is non-null but isn't a registered VFS, or if the shim
 * couldn't be registered. Also always `null` on platforms without VFS shim support (plain JVM,
 * JS/Wasm).
 */
public fun sqlite3_vfs_shim_register(
    name: String,
    underlyingVfsName: String?,
    initiallyEnabledEvents: Set<SqliteVfsShimEvent>,
    listeners: SqliteVfsShimListeners,
): SqliteVfsShim? {
    val initialEvents = initiallyEnabledEvents.fold(0) { mask, event -> mask or event.bit }
    val dispatcher = VfsShimDispatcher(listeners, initialEvents)

    val vfsPointer = vfsShimRegister(
        name = name,
        underlyingVfsName = underlyingVfsName,
        listenedEvents = dispatcher.listenedEvents,
        initialFileEvents = initialEvents,
        dispatcher = dispatcher,
    ) ?: return null

    return SqliteVfsShim(name, vfsPointer)
}

///////////////////////////////////////////////////////////////////////////
// Platform contract
///////////////////////////////////////////////////////////////////////////

// Event codes, matching ksqlite.h's `ksqlite_vfs_shim_event`.
internal const val VFS_SHIM_EVENT_OPEN = 0
internal const val VFS_SHIM_EVENT_CLOSE = 1
internal const val VFS_SHIM_EVENT_READ = 2
internal const val VFS_SHIM_EVENT_WRITE = 3
internal const val VFS_SHIM_EVENT_TRUNCATE = 4
internal const val VFS_SHIM_EVENT_SYNC = 5
internal const val VFS_SHIM_EVENT_FILE_SIZE = 6
internal const val VFS_SHIM_EVENT_LOCK = 7
internal const val VFS_SHIM_EVENT_UNLOCK = 8
internal const val VFS_SHIM_EVENT_CHECK_RESERVED_LOCK = 9
internal const val VFS_SHIM_EVENT_FILE_CONTROL = 10
internal const val VFS_SHIM_EVENT_SHM_MAP = 11
internal const val VFS_SHIM_EVENT_SHM_LOCK = 12
internal const val VFS_SHIM_EVENT_SHM_BARRIER = 13
internal const val VFS_SHIM_EVENT_SHM_UNMAP = 14
internal const val VFS_SHIM_EVENT_FETCH = 15
internal const val VFS_SHIM_EVENT_UNFETCH = 16
internal const val VFS_SHIM_EVENT_DELETE = 17
internal const val VFS_SHIM_EVENT_ACCESS = 18
internal const val VFS_SHIM_EVENT_FULL_PATHNAME = 19

/**
 * Registers the native shim (`ksqlite_vfs_shim_register`), routing its events to [dispatcher].
 * Returns the shim's `sqlite3_vfs` address, or `null` on failure.
 */
internal expect fun vfsShimRegister(
    name: String,
    underlyingVfsName: String?,
    listenedEvents: Int,
    initialFileEvents: Int,
    dispatcher: VfsShimDispatcher,
): Long?

/** Unregisters the native shim at [vfsPointer], releasing its dispatcher on success. */
internal expect fun vfsShimUnregister(vfsPointer: Long): SqliteResultCode

/** Looks up [db]'s file via `ksqlite_vfs_shim_file_lookup`, returning its [SqliteVfsShimFile]. */
internal expect fun vfsShimFileLookup(
    vfsPointer: Long,
    db: sqlite3,
    schema: String,
    journal: Boolean,
): SqliteVfsShimFile?

/** Sets the native event mask of the shim file at [filePointer]. */
internal expect fun vfsShimFileSetEvents(filePointer: Long, events: Int)

// SQLITE_SHM_* flags, for SHM_LOCK events.
private const val SQLITE_SHM_LOCK = 2
private const val SQLITE_SHM_EXCLUSIVE = 8

/**
 * Turns a shim's native events into [SqliteVfsShimListeners] calls. On OPEN, platforms create the
 * file's [SqliteVfsShimFile] via [createFile], attach it to the native file, then call [onOpened];
 * they pass it back to [onEvent]/[onClose], and release it after [onClose].
 */
internal class VfsShimDispatcher(
    private val listeners: SqliteVfsShimListeners,
    private val initialFileEvents: Int,
) {

    /** The events with a listener, as a native `1 << event` mask. */
    val listenedEvents: Int = with(listeners) {
        listOfNotNull(
            didOpen?.let { VFS_SHIM_EVENT_OPEN },
            didClose?.let { VFS_SHIM_EVENT_CLOSE },
            didRead?.let { VFS_SHIM_EVENT_READ },
            didWrite?.let { VFS_SHIM_EVENT_WRITE },
            didTruncate?.let { VFS_SHIM_EVENT_TRUNCATE },
            didSync?.let { VFS_SHIM_EVENT_SYNC },
            didFileSize?.let { VFS_SHIM_EVENT_FILE_SIZE },
            didLock?.let { VFS_SHIM_EVENT_LOCK },
            didUnlock?.let { VFS_SHIM_EVENT_UNLOCK },
            didCheckReservedLock?.let { VFS_SHIM_EVENT_CHECK_RESERVED_LOCK },
            didFileControl?.let { VFS_SHIM_EVENT_FILE_CONTROL },
            didShmMap?.let { VFS_SHIM_EVENT_SHM_MAP },
            didShmLock?.let { VFS_SHIM_EVENT_SHM_LOCK },
            didShmBarrier?.let { VFS_SHIM_EVENT_SHM_BARRIER },
            didShmUnmap?.let { VFS_SHIM_EVENT_SHM_UNMAP },
            didFetch?.let { VFS_SHIM_EVENT_FETCH },
            didUnfetch?.let { VFS_SHIM_EVENT_UNFETCH },
            didDelete?.let { VFS_SHIM_EVENT_DELETE },
            didAccess?.let { VFS_SHIM_EVENT_ACCESS },
            didFullPathname?.let { VFS_SHIM_EVENT_FULL_PATHNAME },
        ).fold(0) { mask, event -> mask or (1 shl event) }
    }

    /**
     * The OPEN event, first half: creates the file's [SqliteVfsShimFile], which the platform then
     * attaches to the native file before calling [onOpened].
     */
    fun createFile(filePointer: Long, filename: String?, flags: Int): SqliteVfsShimFile =
        SqliteVfsShimFile(filePointer, filename, SqliteOpenFlag.Vfs.from(flags), initialFileEvents)

    /** The OPEN event, second half: notifies didOpen, once [file] can be looked up. */
    fun onOpened(file: SqliteVfsShimFile) {
        listeners.didOpen?.let { listener -> notify("didOpen") { listener.didOpen(file) } }
    }

    /** The CLOSE event: notifies didClose if [isEnabled], then marks [file] closed. */
    fun onClose(file: SqliteVfsShimFile?, isEnabled: Boolean, rc: Int) {
        if (file == null) return

        if (isEnabled) {
            listeners.didClose?.let { listener -> notify("didClose") { listener.didClose(file, result(rc)) } }
        }

        file.markClosed()
    }

    /** Any event but OPEN and CLOSE. [file] is `null` for VFS events. */
    @Suppress("CyclomaticComplexMethod")
    fun onEvent(
        file: SqliteVfsShimFile?,
        event: Int,
        a: Long,
        b: Long,
        c: Long,
        z1: String?,
        z2: String?,
        rc: Int,
    ) {
        when (event) {
            VFS_SHIM_EVENT_DELETE -> listeners.didDelete?.let { listener ->
                notify("didDelete") { listener.didDelete(z1, a != 0L, result(rc)) }
            }

            VFS_SHIM_EVENT_ACCESS -> listeners.didAccess?.let { listener ->
                val flags = SqliteAccessFlag.entries.firstOrNull { it.value == a.toInt() }
                notify("didAccess") { listener.didAccess(z1, flags, b != 0L, result(rc)) }
            }

            VFS_SHIM_EVENT_FULL_PATHNAME -> listeners.didFullPathname?.let { listener ->
                notify("didFullPathname") { listener.didFullPathname(z1, z2, result(rc)) }
            }

            else -> if (file != null) onFileEvent(file, event, a, b, c, rc)
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun onFileEvent(file: SqliteVfsShimFile, event: Int, a: Long, b: Long, c: Long, rc: Int) {
        with(listeners) {
            when (event) {
                VFS_SHIM_EVENT_READ -> didRead?.let { listener ->
                    notify("didRead") { listener.didRead(file, a.toInt(), b, result(rc)) }
                }

                VFS_SHIM_EVENT_WRITE -> didWrite?.let { listener ->
                    notify("didWrite") { listener.didWrite(file, a.toInt(), b, result(rc)) }
                }

                VFS_SHIM_EVENT_TRUNCATE -> didTruncate?.let { listener ->
                    notify("didTruncate") { listener.didTruncate(file, a, result(rc)) }
                }

                VFS_SHIM_EVENT_SYNC -> didSync?.let { listener ->
                    val flags = a.toInt()
                    val isDataOnly = (flags and SqliteSyncType.DATA_ONLY_FLAG) != 0
                    notify("didSync") {
                        listener.didSync(file, SqliteSyncType.from(flags), isDataOnly, result(rc))
                    }
                }

                VFS_SHIM_EVENT_FILE_SIZE -> didFileSize?.let { listener ->
                    notify("didFileSize") { listener.didFileSize(file, a, result(rc)) }
                }

                VFS_SHIM_EVENT_LOCK -> didLock?.let { listener ->
                    notify("didLock") {
                        listener.didLock(file, SqliteLockLevel.from(a.toInt()), result(rc))
                    }
                }

                VFS_SHIM_EVENT_UNLOCK -> didUnlock?.let { listener ->
                    notify("didUnlock") {
                        listener.didUnlock(file, SqliteLockLevel.from(a.toInt()), result(rc))
                    }
                }

                VFS_SHIM_EVENT_CHECK_RESERVED_LOCK -> didCheckReservedLock?.let { listener ->
                    notify("didCheckReservedLock") {
                        listener.didCheckReservedLock(file, a != 0L, result(rc))
                    }
                }

                VFS_SHIM_EVENT_FILE_CONTROL -> didFileControl?.let { listener ->
                    notify("didFileControl") { listener.didFileControl(file, a.toInt(), result(rc)) }
                }

                VFS_SHIM_EVENT_SHM_MAP -> didShmMap?.let { listener ->
                    notify("didShmMap") {
                        listener.didShmMap(file, a.toInt(), b.toInt(), c != 0L, result(rc))
                    }
                }

                VFS_SHIM_EVENT_SHM_LOCK -> didShmLock?.let { listener ->
                    val flags = c.toInt()
                    val isLock = (flags and SQLITE_SHM_LOCK) != 0
                    val isExclusive = (flags and SQLITE_SHM_EXCLUSIVE) != 0
                    notify("didShmLock") {
                        listener.didShmLock(file, a.toInt(), b.toInt(), isLock, isExclusive, result(rc))
                    }
                }

                VFS_SHIM_EVENT_SHM_BARRIER -> didShmBarrier?.let { listener ->
                    notify("didShmBarrier") { listener.didShmBarrier(file) }
                }

                VFS_SHIM_EVENT_SHM_UNMAP -> didShmUnmap?.let { listener ->
                    notify("didShmUnmap") { listener.didShmUnmap(file, a != 0L, result(rc)) }
                }

                VFS_SHIM_EVENT_FETCH -> didFetch?.let { listener ->
                    notify("didFetch") {
                        listener.didFetch(file, a.toInt(), b, c != 0L, result(rc))
                    }
                }

                VFS_SHIM_EVENT_UNFETCH -> didUnfetch?.let { listener ->
                    notify("didUnfetch") { listener.didUnfetch(file, a, b != 0L, result(rc)) }
                }
            }
        }
    }

    // VFSes can return result codes Kotlin doesn't model - e.g. SQLite's internal-only
    // SQLITE_OK_SYMLINK from xFullPathname - so an unrecognized extended code is reported as its
    // primary code instead.
    private fun result(rc: Int): SqliteResultCode =
        runCatching { convertResultCode(rc) }.getOrElse { convertResultCode(rc and 0xff) }

    // A listener exception can't be allowed to unwind through SQLite's own C frames, and must never
    // change the call's result, so it's only logged.
    private inline fun notify(listener: String, block: () -> Unit) {
        try {
            block()
        } catch (exception: Throwable) {
            sqlite3_log(
                SqliteResultCode.WARNING.code,
                "VFS shim $listener listener threw\n${exception.stackTraceToString()}"
            )
        }
    }
}
