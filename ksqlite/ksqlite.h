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
#ifndef KSQLITE_H
#define KSQLITE_H

#ifndef __WASM__

#include "sqlite3.h"

#endif

#ifdef __cplusplus
extern "C" {
#endif

///////////////////////////////////////////////////////////////////////////
// Constants
///////////////////////////////////////////////////////////////////////////

/**
 * Exposes the SQLITE_TRANSIENT macro as a constant so it can be referenced
 * from Kotlin/Native when using direct ccall mode.
 */
__attribute__((unused))
extern const sqlite3_destructor_type KSQLITE_TRANSIENT;

///////////////////////////////////////////////////////////////////////////
// Typedefs
///////////////////////////////////////////////////////////////////////////

/**
 * Holds the layout of a structure.
 */
typedef int* ksqlite_layout;

/**
 * Exposes the `CipherDescriptor` in a way some generator can handle it.
 */
__attribute__((unused))
typedef struct _CipherDescriptor ksqlite_cipher_descriptor;

/**
 * Exposes the `CipherParams` in a way some generator can handle it.
 */
__attribute__((unused))
typedef struct _CipherParams ksqlite_cipher_params;

/**
 * Callback for SQLITE_CONFIG_LOG, exposed for binding generation.
 */
__attribute__((unused))
typedef void(* ksqlite_xLog)(void*, int, const char*);

/**
 * Callback for SQLITE_CONFIG_SQLLOG, exposed for binding generation.
 */
__attribute__((unused))
typedef void(* ksqlite_xSqllog)(void*, sqlite3*, const char*, int);

/**
 * Callback expected by sqlite3_auto_extension() and sqlite3_cancel_auto_extension() with the
 * full signature.
 */
typedef int (* ksqlite_xEntryPoint)(
    sqlite3* db,
    char** pzErrMsg,
    const struct sqlite3_api_routines* pThunk
);

///////////////////////////////////////////////////////////////////////////
// Layout
///////////////////////////////////////////////////////////////////////////

/**
 * Recognized struct types.
 */
enum ksqlite_struct_type : int {
    Sqlite3IndexInfo = 0,
    Sqlite3IndexConstraint = 1,
    Sqlite3IndexConstraintUsage = 2,
    Sqlite3IndexOrderby = 3,
    Sqlite3Module = 4,
    Sqlite3Vtab = 5,
    Sqlite3VtabCursor = 6,
    Sqlite3File = 7,
    Sqlite3IoMethods = 8,
    Sqlite3Vfs = 9,
    KsqliteCipherDescriptor = 10,
    KsqliteCipherParams = 11
};

/**
 * Allocates and returns the layout of the struct identified by `structType`.
 *
 * For a struct member index M:
 *
 * - array[M*2] = offset
 * - array[M*2+1] = length
 *
 * The struct's total size can be obtained by reading the last element of the returned array.
 * The offset and length are written in the order they're declared in the C struct.
 *
 * The returned `ksqlite_layout` must be freed when no longer required by passing it to
 * `ksqlite_struct_layout_free`.
 */
ksqlite_layout ksqlite_struct_layout_allocate(
    enum ksqlite_struct_type structType,
    int* layoutSize
);

/**
 * Frees a `ksqlite_layout` instance previously obtained via `ksqlite_struct_layout_allocate`
 * @param layout
 */
void ksqlite_struct_layout_free(ksqlite_layout layout);

///////////////////////////////////////////////////////////////////////////
// Functions
///////////////////////////////////////////////////////////////////////////

/**
 * Wrappers function around sqlite3_auto_extension() with accept the xEntryPoint parameter with the
 * signature expected by SQLite. This is necessary for interop tools to generate compatible
 * code.
 *
 * @param xEntryPoint the XEntryPoint with expected signature.
 * @return sqlite3_auto_extension() result
 */
int ksqlite_auto_extension(ksqlite_xEntryPoint);

/**
 * Wrappers function around sqlite3_cancel_auto_extension() with accept the xEntryPoint parameter
 * with the signature expected by SQLite. This is necessary for interop tools to generate
 * compatible  code.
 *
 * @param xEntryPoint the XEntryPoint with expected signature.
 * @return sqlite3_cancel_auto_extension() result
 */
int ksqlite_cancel_auto_extension(ksqlite_xEntryPoint);

/**
 * Works like the canonical sqlite3_prepare_v2() but its "tail"  output parameter is returned as the
 * index offset into the given byte array at which SQL parsing stopped.
 */
int ksqlite_prepare_v2(
    sqlite3* db,            /* Database handle */
    const char* zSql,       /* SQL statement, UTF-8 encoded */
    int nByte,              /* Maximum length of zSql in bytes. */
    sqlite3_stmt** ppStmt,  /* OUT: Statement handle */
    int* pzTailOffset       /* OUT: Pointer to index of the unused portion of zSql */
);

/**
 * Works like the canonical sqlite3_prepare_v3() but its "tail"  output parameter is returned as the
 * index offset into the given byte array at which SQL parsing stopped.
 */
int ksqlite_prepare_v3(
    sqlite3* db,            /* Database handle */
    const char* zSql,       /* SQL statement, UTF-8 encoded */
    int nByte,              /* Maximum length of zSql in bytes. */
    unsigned int prepFlags, /* Zero or more SQLITE_PREPARE_ flags */
    sqlite3_stmt** ppStmt,  /* OUT: Statement handle */
    int* pzTailOffset       /* OUT: Pointer to index of the unused portion of zSql */
);

///////////////////////////////////////////////////////////////////////////
// VFS shim
///////////////////////////////////////////////////////////////////////////

/**
 * Events reported by a VFS shim (see `ksqlite_vfs_shim_register`), one per observable VFS or file
 * method. Each is also a bit position in the event masks: bit `1 << event`.
 *
 * File events carry the file they concern; VFS events (DELETE, ACCESS, FULL_PATHNAME) don't.
 */
enum ksqlite_vfs_shim_event {
    KSQLITE_VFS_SHIM_EVENT_OPEN = 0,
    KSQLITE_VFS_SHIM_EVENT_CLOSE = 1,
    KSQLITE_VFS_SHIM_EVENT_READ = 2,
    KSQLITE_VFS_SHIM_EVENT_WRITE = 3,
    KSQLITE_VFS_SHIM_EVENT_TRUNCATE = 4,
    KSQLITE_VFS_SHIM_EVENT_SYNC = 5,
    KSQLITE_VFS_SHIM_EVENT_FILE_SIZE = 6,
    KSQLITE_VFS_SHIM_EVENT_LOCK = 7,
    KSQLITE_VFS_SHIM_EVENT_UNLOCK = 8,
    KSQLITE_VFS_SHIM_EVENT_CHECK_RESERVED_LOCK = 9,
    KSQLITE_VFS_SHIM_EVENT_FILE_CONTROL = 10,
    KSQLITE_VFS_SHIM_EVENT_SHM_MAP = 11,
    KSQLITE_VFS_SHIM_EVENT_SHM_LOCK = 12,
    KSQLITE_VFS_SHIM_EVENT_SHM_BARRIER = 13,
    KSQLITE_VFS_SHIM_EVENT_SHM_UNMAP = 14,
    KSQLITE_VFS_SHIM_EVENT_FETCH = 15,
    KSQLITE_VFS_SHIM_EVENT_UNFETCH = 16,
    KSQLITE_VFS_SHIM_EVENT_DELETE = 17,
    KSQLITE_VFS_SHIM_EVENT_ACCESS = 18,
    KSQLITE_VFS_SHIM_EVENT_FULL_PATHNAME = 19
};

/**
 * Receives a VFS shim's events. Invoked after the underlying VFS's own method returns, with that
 * method's arguments and result code. It returns nothing: the shim always returns the underlying
 * VFS's own result to SQLite, whatever the handler does.
 *
 * @param pAppData the `pAppData` given to `ksqlite_vfs_shim_register`.
 * @param pFile the file concerned, or NULL for VFS events.
 * @param event a `ksqlite_vfs_shim_event`.
 * @param a,b,c event-specific integer arguments (0 when unused):
 *   OPEN: a = open flags, b = output flags. Always reported, on successful opens only.
 *   CLOSE: a = 1 if the file had CLOSE enabled, 0 otherwise. Always reported: it ends the file's
 *     lifecycle, so the handler can release whatever it attached with
 *     `ksqlite_vfs_shim_file_set_data`.
 *   READ, WRITE: a = amount, b = offset.
 *   TRUNCATE: a = size.   SYNC: a = sync flags.   FILE_SIZE: a = size (when rc is SQLITE_OK).
 *   LOCK, UNLOCK: a = lock level.   CHECK_RESERVED_LOCK: a = result (when rc is SQLITE_OK).
 *   FILE_CONTROL: a = opcode.   SHM_MAP: a = region, b = region size, c = is write.
 *   SHM_LOCK: a = offset, b = count, c = flags.   SHM_UNMAP: a = delete flag.
 *   FETCH: a = amount, b = offset, c = 1 if a page was mapped.
 *   UNFETCH: a = offset, b = 1 if releasing a page (0 if invalidating mappings).
 *   DELETE: a = sync-directory flag.   ACCESS: a = access flags, b = result (when rc is SQLITE_OK).
 * @param z1 OPEN, DELETE, ACCESS, FULL_PATHNAME: the filename passed to the method, else NULL.
 * @param z2 FULL_PATHNAME: the resulting full pathname (when rc is SQLITE_OK), else NULL.
 * @param rc the underlying method's result code (SQLITE_OK for SHM_BARRIER). "When rc is
 *   SQLITE_OK" above means its primary code is SQLITE_OK, so extended "OK" codes such as
 *   xFullPathname's SQLITE_OK_SYMLINK count too.
 */
typedef void (*ksqlite_xVfsShimEvent)(
    void* pAppData,
    sqlite3_file* pFile,
    int event,
    sqlite3_int64 a,
    sqlite3_int64 b,
    sqlite3_int64 c,
    const char* z1,
    const char* z2,
    int rc
);

/**
 * Registers a VFS shim named [zName] over the VFS named [zUnderlying] (or the default VFS when
 * NULL). The shim forwards every VFS and file method to the underlying VFS unchanged, and reports
 * each call to [xEvent] afterwards if that event is in [listenedEvents] and, for file events other
 * than OPEN/CLOSE, also enabled on that file (see `ksqlite_vfs_shim_file_set_events`). Every file
 * opened through the shim starts with [initialFileEvents] enabled.
 *
 * Returns SQLITE_OK and sets [ppVfs] on success; SQLITE_NOTFOUND if [zUnderlying] isn't a
 * registered VFS; otherwise an error code, with [ppVfs] set to NULL.
 */
int ksqlite_vfs_shim_register(
    const char* zName,
    const char* zUnderlying,
    unsigned int listenedEvents,
    unsigned int initialFileEvents,
    ksqlite_xVfsShimEvent xEvent,
    void* pAppData,
    sqlite3_vfs** ppVfs
);

/**
 * Unregisters and frees a VFS shim from `ksqlite_vfs_shim_register`. Must not be called while any
 * file opened through it is still open. Returns sqlite3_vfs_unregister()'s result; the shim is
 * only freed on SQLITE_OK. Doesn't touch the shim's `pAppData`, which the caller owns.
 */
int ksqlite_vfs_shim_unregister(sqlite3_vfs* pVfs);

/**
 * Returns the `pAppData` a VFS shim was registered with.
 */
void* ksqlite_vfs_shim_app_data(sqlite3_vfs* pVfs);

/**
 * Returns the caller-owned data attached to a shim file, NULL until set.
 */
void* ksqlite_vfs_shim_file_data(sqlite3_file* pFile);

/**
 * Attaches caller-owned data to a shim file, e.g. from the OPEN event.
 */
void ksqlite_vfs_shim_file_set_data(sqlite3_file* pFile, void* pData);

/**
 * Returns the events currently enabled on a shim file, as a `1 << event` mask.
 */
unsigned int ksqlite_vfs_shim_file_events(sqlite3_file* pFile);

/**
 * Sets the events enabled on a shim file, as a `1 << event` mask. Like the rest of a connection's
 * state, only change it under that connection's own serialization.
 */
void ksqlite_vfs_shim_file_set_events(sqlite3_file* pFile, unsigned int events);

/**
 * Returns [db]'s own open database file for [zSchema] - or, if [journal] is non-zero, its
 * journal/WAL file - if it was opened through the VFS shim [pVfs]. Otherwise NULL.
 */
sqlite3_file* ksqlite_vfs_shim_file_lookup(
    sqlite3_vfs* pVfs,
    sqlite3* db,
    const char* zSchema,
    int journal
);

#ifdef __cplusplus
}
#endif

#endif // KSQLITE_H