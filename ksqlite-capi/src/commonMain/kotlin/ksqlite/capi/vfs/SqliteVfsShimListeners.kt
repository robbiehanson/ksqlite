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

import ksqlite.types.SqliteAccessFlag
import ksqlite.types.SqliteResultCode
import ksqlite.types.vfs.SqliteLockLevel
import ksqlite.types.vfs.SqliteSyncType

// Each listener is invoked after the underlying VFS's own method returns, with that method's
// arguments and [SqliteResultCode] result (an extended code Kotlin doesn't model, such as SQLite's
// internal SQLITE_OK_SYMLINK, is reported as its primary code). Listeners observe only: whatever they do, SQLite gets
// the underlying VFS's own result. File listeners (all but didOpen, didDelete, didAccess and
// didFullPathname) are invoked only while their event is enabled on the file concerned - see
// SqliteVfsShimFile.setEventEnabled. See SqliteVfsShimListeners for threading and exceptions.

/** Invoked after a file is successfully opened through the shim, whatever its enabled events. */
public fun interface SqliteVfsShimDidOpenListener {
    public fun didOpen(file: SqliteVfsShimFile)
}

/** Invoked after xClose. [file] is still open for the duration of the call. */
public fun interface SqliteVfsShimDidCloseListener {
    public fun didClose(file: SqliteVfsShimFile, result: SqliteResultCode)
}

/** Invoked after xRead of [amount] bytes at [offset]. */
public fun interface SqliteVfsShimDidReadListener {
    public fun didRead(file: SqliteVfsShimFile, amount: Int, offset: Long, result: SqliteResultCode)
}

/** Invoked after xWrite of [amount] bytes at [offset]. */
public fun interface SqliteVfsShimDidWriteListener {
    public fun didWrite(file: SqliteVfsShimFile, amount: Int, offset: Long, result: SqliteResultCode)
}

/** Invoked after xTruncate to [size] bytes. */
public fun interface SqliteVfsShimDidTruncateListener {
    public fun didTruncate(file: SqliteVfsShimFile, size: Long, result: SqliteResultCode)
}

/**
 * Invoked after xSync. [type] is `null` if xSync's flags hold no recognized sync type;
 * [isDataOnly] reflects SQLITE_SYNC_DATAONLY.
 */
public fun interface SqliteVfsShimDidSyncListener {
    public fun didSync(
        file: SqliteVfsShimFile,
        type: SqliteSyncType?,
        isDataOnly: Boolean,
        result: SqliteResultCode,
    )
}

/** Invoked after xFileSize. [size] is only meaningful if [result] is OK. */
public fun interface SqliteVfsShimDidFileSizeListener {
    public fun didFileSize(file: SqliteVfsShimFile, size: Long, result: SqliteResultCode)
}

/** Invoked after xLock, asked to raise the file's lock to [level]. */
public fun interface SqliteVfsShimDidLockListener {
    public fun didLock(file: SqliteVfsShimFile, level: SqliteLockLevel?, result: SqliteResultCode)
}

/** Invoked after xUnlock, asked to lower the file's lock to [level]. */
public fun interface SqliteVfsShimDidUnlockListener {
    public fun didUnlock(file: SqliteVfsShimFile, level: SqliteLockLevel?, result: SqliteResultCode)
}

/** Invoked after xCheckReservedLock. [isReserved] is only meaningful if [result] is OK. */
public fun interface SqliteVfsShimDidCheckReservedLockListener {
    public fun didCheckReservedLock(
        file: SqliteVfsShimFile,
        isReserved: Boolean,
        result: SqliteResultCode,
    )
}

/** Invoked after xFileControl with the SQLITE_FCNTL_* [opcode]. */
public fun interface SqliteVfsShimDidFileControlListener {
    public fun didFileControl(file: SqliteVfsShimFile, opcode: Int, result: SqliteResultCode)
}

/** Invoked after xShmMap of shared-memory [region] (of [regionSize] bytes). */
public fun interface SqliteVfsShimDidShmMapListener {
    public fun didShmMap(
        file: SqliteVfsShimFile,
        region: Int,
        regionSize: Int,
        isWrite: Boolean,
        result: SqliteResultCode,
    )
}

/**
 * Invoked after xShmLock of [count] shared-memory locks from [offset]: taking them if [isLock],
 * else releasing them, [isExclusive] or shared.
 */
public fun interface SqliteVfsShimDidShmLockListener {
    public fun didShmLock(
        file: SqliteVfsShimFile,
        offset: Int,
        count: Int,
        isLock: Boolean,
        isExclusive: Boolean,
        result: SqliteResultCode,
    )
}

/** Invoked after xShmBarrier. */
public fun interface SqliteVfsShimDidShmBarrierListener {
    public fun didShmBarrier(file: SqliteVfsShimFile)
}

/** Invoked after xShmUnmap, which also deletes the shared memory if [isDelete]. */
public fun interface SqliteVfsShimDidShmUnmapListener {
    public fun didShmUnmap(file: SqliteVfsShimFile, isDelete: Boolean, result: SqliteResultCode)
}

/**
 * Invoked after xFetch of [amount] bytes at [offset] (memory-mapped I/O). [isMapped] is `false`
 * when the VFS declined to map the page, in which case SQLite falls back to xRead.
 */
public fun interface SqliteVfsShimDidFetchListener {
    public fun didFetch(
        file: SqliteVfsShimFile,
        amount: Int,
        offset: Long,
        isMapped: Boolean,
        result: SqliteResultCode,
    )
}

/**
 * Invoked after xUnfetch: releasing the page fetched at [offset] if [isRelease], else asking the
 * VFS to invalidate its mappings.
 */
public fun interface SqliteVfsShimDidUnfetchListener {
    public fun didUnfetch(file: SqliteVfsShimFile, offset: Long, isRelease: Boolean, result: SqliteResultCode)
}

/** Invoked after the VFS's xDelete of [filename]. */
public fun interface SqliteVfsShimDidDeleteListener {
    public fun didDelete(filename: String?, syncDirectory: Boolean, result: SqliteResultCode)
}

/**
 * Invoked after the VFS's xAccess check of [filename]. [isGranted] is only meaningful if [result]
 * is OK. [flags] is `null` for an unrecognized access check.
 */
public fun interface SqliteVfsShimDidAccessListener {
    public fun didAccess(
        filename: String?,
        flags: SqliteAccessFlag?,
        isGranted: Boolean,
        result: SqliteResultCode,
    )
}

/**
 * Invoked after the VFS's xFullPathname of [filename]. [fullPathname] is `null` unless [result] is
 * OK.
 */
public fun interface SqliteVfsShimDidFullPathnameListener {
    public fun didFullPathname(filename: String?, fullPathname: String?, result: SqliteResultCode)
}

/**
 * The listeners a VFS shim reports to (see [sqlite3_vfs_shim_register]). Pass only the ones you
 * need: an event whose listener is `null` is never reported, and costs nothing beyond forwarding
 * the call to the underlying VFS.
 *
 * Listeners run synchronously, inside whichever SQLite call triggered the VFS method, on the thread
 * making that call. The one exception is SQLite's multi-threaded sorter (off by default, see
 * `PRAGMA threads`), which can do I/O on its own temporary files from SQLite worker threads.
 *
 * A listener that throws never affects SQLite: the exception is caught and logged via
 * `sqlite3_log`, and the call's result is unchanged.
 */
public class SqliteVfsShimListeners(
    public val didOpen: SqliteVfsShimDidOpenListener? = null,
    public val didClose: SqliteVfsShimDidCloseListener? = null,
    public val didRead: SqliteVfsShimDidReadListener? = null,
    public val didWrite: SqliteVfsShimDidWriteListener? = null,
    public val didTruncate: SqliteVfsShimDidTruncateListener? = null,
    public val didSync: SqliteVfsShimDidSyncListener? = null,
    public val didFileSize: SqliteVfsShimDidFileSizeListener? = null,
    public val didLock: SqliteVfsShimDidLockListener? = null,
    public val didUnlock: SqliteVfsShimDidUnlockListener? = null,
    public val didCheckReservedLock: SqliteVfsShimDidCheckReservedLockListener? = null,
    public val didFileControl: SqliteVfsShimDidFileControlListener? = null,
    public val didShmMap: SqliteVfsShimDidShmMapListener? = null,
    public val didShmLock: SqliteVfsShimDidShmLockListener? = null,
    public val didShmBarrier: SqliteVfsShimDidShmBarrierListener? = null,
    public val didShmUnmap: SqliteVfsShimDidShmUnmapListener? = null,
    public val didFetch: SqliteVfsShimDidFetchListener? = null,
    public val didUnfetch: SqliteVfsShimDidUnfetchListener? = null,
    public val didDelete: SqliteVfsShimDidDeleteListener? = null,
    public val didAccess: SqliteVfsShimDidAccessListener? = null,
    public val didFullPathname: SqliteVfsShimDidFullPathnameListener? = null,
)
