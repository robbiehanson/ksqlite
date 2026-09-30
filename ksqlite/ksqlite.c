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
#ifndef __WASM__

#include "ksqlite.h"

#endif

#include <string.h>

///////////////////////////////////////////////////////////////////////////
// Constants
///////////////////////////////////////////////////////////////////////////

__attribute__((unused))
const sqlite3_destructor_type KSQLITE_TRANSIENT = SQLITE_TRANSIENT;

///////////////////////////////////////////////////////////////////////////
// Layouts
///////////////////////////////////////////////////////////////////////////

#ifndef offsetof
# define offsetof(ST,M) ((int)((char*)&((ST*)0)->M - (char*)0))
#endif

#pragma clang diagnostic push
#pragma ide diagnostic ignored "bugprone-sizeof-expression"

#define StructLayoutBegin(memberCount) \
    static const int arraySize = (memberCount) * 2 + 1; \
    int *buffer = sqlite3_malloc(sizeof(int) * arraySize); \
    if (buffer == 0) return 0; \
    int position = 0

#define StructLayoutAppend(type, member) \
    buffer[position++] = (int) offsetof(type, member); \
    buffer[position++] = (int) sizeof(((type*)0)->member)

#define StructLayoutEnd(type) \
    buffer[position] = (int) sizeof(type); \
    if (layoutSize != 0) *layoutSize = arraySize; \
    return buffer

/**
 * Returns the layout for `sqlite3_index_info`.
 */
static ksqlite_layout struct_layout_sqlite3_index_info(int* layoutSize) {
    StructLayoutBegin(13);
    StructLayoutAppend(sqlite3_index_info, nConstraint);
    StructLayoutAppend(sqlite3_index_info, aConstraint);
    StructLayoutAppend(sqlite3_index_info, nOrderBy);
    StructLayoutAppend(sqlite3_index_info, aOrderBy);
    StructLayoutAppend(sqlite3_index_info, aConstraintUsage);
    StructLayoutAppend(sqlite3_index_info, idxNum);
    StructLayoutAppend(sqlite3_index_info, idxStr);
    StructLayoutAppend(sqlite3_index_info, needToFreeIdxStr);
    StructLayoutAppend(sqlite3_index_info, orderByConsumed);
    StructLayoutAppend(sqlite3_index_info, estimatedCost);
    StructLayoutAppend(sqlite3_index_info, estimatedRows);
    StructLayoutAppend(sqlite3_index_info, idxFlags);
    StructLayoutAppend(sqlite3_index_info, colUsed);
    StructLayoutEnd(sqlite3_index_info);
}

/**
 * Returns the layout for `sqlite3_index_constraint`.
 */
static ksqlite_layout struct_layout_sqlite3_index_constraint(int* layoutSize) {
    StructLayoutBegin(4);
    StructLayoutAppend(struct sqlite3_index_constraint, iColumn);
    StructLayoutAppend(struct sqlite3_index_constraint, op);
    StructLayoutAppend(struct sqlite3_index_constraint, usable);
    StructLayoutAppend(struct sqlite3_index_constraint, iTermOffset);
    StructLayoutEnd(struct sqlite3_index_constraint);
}

/**
 * Returns the layout for `sqlite3_index_constraint_usage`.
 */
static ksqlite_layout struct_layout_sqlite3_index_constraint_usage(int* layoutSize) {
    StructLayoutBegin(2);
    StructLayoutAppend(struct sqlite3_index_constraint_usage, argvIndex);
    StructLayoutAppend(struct sqlite3_index_constraint_usage, omit);
    StructLayoutEnd(struct sqlite3_index_constraint_usage);
}

/**
 * Returns the layout for `sqlite3_index_orderby`.
 */
static ksqlite_layout struct_layout_sqlite3_index_order_by(int* layoutSize) {
    StructLayoutBegin(2);
    StructLayoutAppend(struct sqlite3_index_orderby, iColumn);
    StructLayoutAppend(struct sqlite3_index_orderby, desc);
    StructLayoutEnd(struct sqlite3_index_orderby);
}

/**
 * Returns the layout for `sqlite3_module`.
 */
static ksqlite_layout struct_layout_sqlite3_module(int* layoutSize) {
    StructLayoutBegin(25);
    StructLayoutAppend(sqlite3_module, iVersion);
    StructLayoutAppend(sqlite3_module, xCreate);
    StructLayoutAppend(sqlite3_module, xConnect);
    StructLayoutAppend(sqlite3_module, xBestIndex);
    StructLayoutAppend(sqlite3_module, xDisconnect);
    StructLayoutAppend(sqlite3_module, xDestroy);
    StructLayoutAppend(sqlite3_module, xOpen);
    StructLayoutAppend(sqlite3_module, xClose);
    StructLayoutAppend(sqlite3_module, xFilter);
    StructLayoutAppend(sqlite3_module, xNext);
    StructLayoutAppend(sqlite3_module, xEof);
    StructLayoutAppend(sqlite3_module, xColumn);
    StructLayoutAppend(sqlite3_module, xRowid);
    StructLayoutAppend(sqlite3_module, xUpdate);
    StructLayoutAppend(sqlite3_module, xBegin);
    StructLayoutAppend(sqlite3_module, xSync);
    StructLayoutAppend(sqlite3_module, xCommit);
    StructLayoutAppend(sqlite3_module, xRollback);
    StructLayoutAppend(sqlite3_module, xFindFunction);
    StructLayoutAppend(sqlite3_module, xRename);
    StructLayoutAppend(sqlite3_module, xSavepoint);
    StructLayoutAppend(sqlite3_module, xRelease);
    StructLayoutAppend(sqlite3_module, xRollbackTo);
    StructLayoutAppend(sqlite3_module, xShadowName);
    StructLayoutAppend(sqlite3_module, xIntegrity);
    StructLayoutEnd(sqlite3_module);
}

/**
 * Returns the layout for `sqlite3_vtab`.
 */
static ksqlite_layout struct_layout_sqlite3_vtab(int* layoutSize) {
    StructLayoutBegin(3);
    StructLayoutAppend(sqlite3_vtab, pModule);
    StructLayoutAppend(sqlite3_vtab, nRef);
    StructLayoutAppend(sqlite3_vtab, zErrMsg);
    StructLayoutEnd(sqlite3_vtab);
}

/**
 * Returns the layout for `sqlite3_vtab_cursor`.
 */
static ksqlite_layout struct_layout_sqlite3_vtab_cursor(int* layoutSize) {
    StructLayoutBegin(1);
    StructLayoutAppend(sqlite3_vtab_cursor, pVtab);
    StructLayoutEnd(sqlite3_vtab_cursor);
}

/**
 * Returns the layout for `sqlite3_file`.
 */
static ksqlite_layout struct_layout_sqlite3_file(int* layoutSize) {
    StructLayoutBegin(1);
    StructLayoutAppend(sqlite3_file, pMethods);
    StructLayoutEnd(sqlite3_file);
}

/**
 * Returns the layout for `sqlite3_io_methods`.
 */
static ksqlite_layout struct_layout_sqlite3_io_methods(int* layoutSize) {
    StructLayoutBegin(19);
    StructLayoutAppend(sqlite3_io_methods, iVersion);
    StructLayoutAppend(sqlite3_io_methods, xClose);
    StructLayoutAppend(sqlite3_io_methods, xRead);
    StructLayoutAppend(sqlite3_io_methods, xWrite);
    StructLayoutAppend(sqlite3_io_methods, xTruncate);
    StructLayoutAppend(sqlite3_io_methods, xSync);
    StructLayoutAppend(sqlite3_io_methods, xFileSize);
    StructLayoutAppend(sqlite3_io_methods, xLock);
    StructLayoutAppend(sqlite3_io_methods, xUnlock);
    StructLayoutAppend(sqlite3_io_methods, xCheckReservedLock);
    StructLayoutAppend(sqlite3_io_methods, xFileControl);
    StructLayoutAppend(sqlite3_io_methods, xSectorSize);
    StructLayoutAppend(sqlite3_io_methods, xDeviceCharacteristics);
    StructLayoutAppend(sqlite3_io_methods, xShmMap);
    StructLayoutAppend(sqlite3_io_methods, xShmLock);
    StructLayoutAppend(sqlite3_io_methods, xShmBarrier);
    StructLayoutAppend(sqlite3_io_methods, xShmUnmap);
    StructLayoutAppend(sqlite3_io_methods, xFetch);
    StructLayoutAppend(sqlite3_io_methods, xUnfetch);
    StructLayoutEnd(sqlite3_io_methods);
}

/**
 * Returns the layout for `sqlite3_vfs`.
 */
static ksqlite_layout struct_layout_sqlite3_vfs(int* layoutSize) {
    StructLayoutBegin(22);
    StructLayoutAppend(sqlite3_vfs, iVersion);
    StructLayoutAppend(sqlite3_vfs, szOsFile);
    StructLayoutAppend(sqlite3_vfs, mxPathname);
    StructLayoutAppend(sqlite3_vfs, pNext);
    StructLayoutAppend(sqlite3_vfs, zName);
    StructLayoutAppend(sqlite3_vfs, pAppData);
    StructLayoutAppend(sqlite3_vfs, xOpen);
    StructLayoutAppend(sqlite3_vfs, xDelete);
    StructLayoutAppend(sqlite3_vfs, xAccess);
    StructLayoutAppend(sqlite3_vfs, xFullPathname);
    StructLayoutAppend(sqlite3_vfs, xDlOpen);
    StructLayoutAppend(sqlite3_vfs, xDlError);
    StructLayoutAppend(sqlite3_vfs, xDlSym);
    StructLayoutAppend(sqlite3_vfs, xDlClose);
    StructLayoutAppend(sqlite3_vfs, xRandomness);
    StructLayoutAppend(sqlite3_vfs, xSleep);
    StructLayoutAppend(sqlite3_vfs, xCurrentTime);
    StructLayoutAppend(sqlite3_vfs, xGetLastError);
    StructLayoutAppend(sqlite3_vfs, xCurrentTimeInt64);
    StructLayoutAppend(sqlite3_vfs, xSetSystemCall);
    StructLayoutAppend(sqlite3_vfs, xGetSystemCall);
    StructLayoutAppend(sqlite3_vfs, xNextSystemCall);
    StructLayoutEnd(sqlite3_vfs);
}

/**
 * Returns the layout for `ksqlite_cipher_descriptor`.
 */
static ksqlite_layout struct_layout_ksqlite_cipher_descriptor(int* layoutSize) {
    StructLayoutBegin(11);
    StructLayoutAppend(ksqlite_cipher_descriptor, m_name);
    StructLayoutAppend(ksqlite_cipher_descriptor, m_allocateCipher);
    StructLayoutAppend(ksqlite_cipher_descriptor, m_freeCipher);
    StructLayoutAppend(ksqlite_cipher_descriptor, m_cloneCipher);
    StructLayoutAppend(ksqlite_cipher_descriptor, m_getLegacy);
    StructLayoutAppend(ksqlite_cipher_descriptor, m_getPageSize);
    StructLayoutAppend(ksqlite_cipher_descriptor, m_getReserved);
    StructLayoutAppend(ksqlite_cipher_descriptor, m_getSalt);
    StructLayoutAppend(ksqlite_cipher_descriptor, m_generateKey);
    StructLayoutAppend(ksqlite_cipher_descriptor, m_encryptPage);
    StructLayoutAppend(ksqlite_cipher_descriptor, m_decryptPage);
    StructLayoutEnd(ksqlite_cipher_descriptor);
}

/**
 * Returns the layout for `ksqlite_cipher_descriptor`.
 */
static ksqlite_layout struct_layout_ksqlite_cipher_params(int* layoutSize) {
    StructLayoutBegin(5);
    StructLayoutAppend(ksqlite_cipher_params, m_name);
    StructLayoutAppend(ksqlite_cipher_params, m_value);
    StructLayoutAppend(ksqlite_cipher_params, m_default);
    StructLayoutAppend(ksqlite_cipher_params, m_minValue);
    StructLayoutAppend(ksqlite_cipher_params, m_maxValue);
    StructLayoutEnd(ksqlite_cipher_params);
}

ksqlite_layout ksqlite_struct_layout_allocate(
    enum ksqlite_struct_type structType,
    int* layoutSize
) {
    switch (structType) {
        case Sqlite3IndexInfo:
            return struct_layout_sqlite3_index_info(layoutSize);
        case Sqlite3IndexConstraint:
            return struct_layout_sqlite3_index_constraint(layoutSize);
        case Sqlite3IndexConstraintUsage:
            return struct_layout_sqlite3_index_constraint_usage(layoutSize);
        case Sqlite3IndexOrderby:
            return struct_layout_sqlite3_index_order_by(layoutSize);
        case Sqlite3Module:
            return struct_layout_sqlite3_module(layoutSize);
        case Sqlite3Vtab:
            return struct_layout_sqlite3_vtab(layoutSize);
        case Sqlite3VtabCursor:
            return struct_layout_sqlite3_vtab_cursor(layoutSize);
        case Sqlite3File:
            return struct_layout_sqlite3_file(layoutSize);
        case Sqlite3IoMethods:
            return struct_layout_sqlite3_io_methods(layoutSize);
        case Sqlite3Vfs:
            return struct_layout_sqlite3_vfs(layoutSize);
        case KsqliteCipherDescriptor:
            return struct_layout_ksqlite_cipher_descriptor(layoutSize);
        case KsqliteCipherParams:
            return struct_layout_ksqlite_cipher_params(layoutSize);
        default:
            return 0;
    }
}

void ksqlite_struct_layout_free(ksqlite_layout layout) {
    sqlite3_free(layout);
}

#pragma clang diagnostic pop

///////////////////////////////////////////////////////////////////////////
// Functions
///////////////////////////////////////////////////////////////////////////

int ksqlite_auto_extension(ksqlite_xEntryPoint callback) {
    return sqlite3_auto_extension((void (*)(void)) callback);
}

int ksqlite_cancel_auto_extension(ksqlite_xEntryPoint callback) {
    return sqlite3_cancel_auto_extension((void (*)(void)) callback);
}

int ksqlite_prepare_v2(
    sqlite3* db,
    const char* zSql,
    int nByte,
    sqlite3_stmt** ppStmt,
    int* const pzTailOffset
) {
    const char* zTail = 0;
    const int rc = sqlite3_prepare_v2(db, zSql, nByte, ppStmt, &zTail);

    if (pzTailOffset && zTail) {
        *pzTailOffset = (int) (zTail ? (zTail - zSql) : 0);
    }

    return rc;
}

int ksqlite_prepare_v3(
    sqlite3* db,
    const char* zSql,
    int nByte,
    unsigned int prepFlags,
    sqlite3_stmt** ppStmt,
    int* const pzTailOffset
) {
    const char* zTail = 0;
    const int rc = sqlite3_prepare_v3(db, zSql, nByte, prepFlags, ppStmt, &zTail);

    if (pzTailOffset && zTail) {
        *pzTailOffset = (int) (zTail ? (zTail - zSql) : 0);
    }

    return rc;
}

///////////////////////////////////////////////////////////////////////////
// VFS shim
///////////////////////////////////////////////////////////////////////////

// A VFS shim is the classic SQLite "shim" technique (see SQLite's own test_vfstrace.c): a thin
// VFS forwarding every call to an underlying VFS, here reporting each call to a single event
// handler afterwards. Names are prefixed throughout, since the Wasm build compiles this file into
// the same translation unit as SQLite's own amalgamation.

/**
 * A registered shim. `base` must stay first: SQLite hands the shim's methods a `sqlite3_vfs*`.
 * The shim's name is stored right after this struct, in the same allocation.
 */
typedef struct KsqliteVfsShim {
    sqlite3_vfs base;
    sqlite3_vfs* pReal;
    unsigned int listenedEvents;
    unsigned int initialFileEvents;
    ksqlite_xVfsShimEvent xEvent;
    void* pAppData;
} KsqliteVfsShim;

/**
 * The shim's own part of each file it opens, followed (at KSQLITE_VFS_SHIM_FILE_HEADER_SIZE) by
 * the underlying VFS's own file. `base` must stay first. `base.pMethods` points at `methods`
 * while the file is open, so the shim needs no per-file allocation of its own.
 */
typedef struct KsqliteVfsShimFile {
    sqlite3_file base;
    sqlite3_io_methods methods;
    KsqliteVfsShim* pShim;
    void* pData;
    unsigned int events;
} KsqliteVfsShimFile;

// Rounded up to 8 so the underlying file stays suitably aligned on 32-bit targets too.
#define KSQLITE_VFS_SHIM_FILE_HEADER_SIZE ((int) ((sizeof(KsqliteVfsShimFile) + 7) & ~((size_t) 7)))

#define KsqliteVfsShimRealFile(p) \
    ((sqlite3_file*) (((char*) (p)) + KSQLITE_VFS_SHIM_FILE_HEADER_SIZE))

#define KsqliteVfsShimBit(event) (1u << (event))

// Whether a file event other than OPEN/CLOSE is both listened to and enabled on the file.
#define KsqliteVfsShimFileWants(p, event) \
    (((p)->pShim->listenedEvents & (p)->events & KsqliteVfsShimBit(event)) != 0)

#define KsqliteVfsShimNotifyFile(p, event, a, b, c, rc) \
    (p)->pShim->xEvent((p)->pShim->pAppData, &(p)->base, (event), (a), (b), (c), 0, 0, (rc))

#define KsqliteVfsShimVfsWants(pShim, event) \
    (((pShim)->listenedEvents & KsqliteVfsShimBit(event)) != 0)

// Success by primary result code: VFSes can return extended "OK" codes, e.g. xFullPathname's
// SQLITE_OK_SYMLINK.
#define KsqliteVfsShimIsOk(rc) (((rc) & 0xff) == SQLITE_OK)

#define KsqliteVfsShimOf(pVfs) ((KsqliteVfsShim*) (pVfs))

#define KsqliteVfsShimFileOf(pFile) ((KsqliteVfsShimFile*) (pFile))

// sqlite3_io_methods

static int ksqlite_vfs_shim_io_close(sqlite3_file* pFile) {
    KsqliteVfsShimFile* p = KsqliteVfsShimFileOf(pFile);
    sqlite3_file* pReal = KsqliteVfsShimRealFile(p);
    int rc = pReal->pMethods->xClose(pReal);

    // SQLite ignores xClose's result and never retries a close (see sqlite3OsClose), so the file's
    // lifecycle ends here regardless of rc - and the handler is always told, so it can release
    // whatever it attached.
    p->pShim->xEvent(
        p->pShim->pAppData,
        pFile,
        KSQLITE_VFS_SHIM_EVENT_CLOSE,
        KsqliteVfsShimFileWants(p, KSQLITE_VFS_SHIM_EVENT_CLOSE) ? 1 : 0,
        0,
        0,
        0,
        0,
        rc
    );

    p->pData = 0;
    p->events = 0;
    return rc;
}

static int ksqlite_vfs_shim_io_read(sqlite3_file* pFile, void* zBuf, int iAmt, sqlite3_int64 iOfst) {
    KsqliteVfsShimFile* p = KsqliteVfsShimFileOf(pFile);
    sqlite3_file* pReal = KsqliteVfsShimRealFile(p);
    int rc = pReal->pMethods->xRead(pReal, zBuf, iAmt, iOfst);

    if (KsqliteVfsShimFileWants(p, KSQLITE_VFS_SHIM_EVENT_READ)) {
        KsqliteVfsShimNotifyFile(p, KSQLITE_VFS_SHIM_EVENT_READ, iAmt, iOfst, 0, rc);
    }

    return rc;
}

static int ksqlite_vfs_shim_io_write(
    sqlite3_file* pFile,
    const void* zBuf,
    int iAmt,
    sqlite3_int64 iOfst
) {
    KsqliteVfsShimFile* p = KsqliteVfsShimFileOf(pFile);
    sqlite3_file* pReal = KsqliteVfsShimRealFile(p);
    int rc = pReal->pMethods->xWrite(pReal, zBuf, iAmt, iOfst);

    if (KsqliteVfsShimFileWants(p, KSQLITE_VFS_SHIM_EVENT_WRITE)) {
        KsqliteVfsShimNotifyFile(p, KSQLITE_VFS_SHIM_EVENT_WRITE, iAmt, iOfst, 0, rc);
    }

    return rc;
}

static int ksqlite_vfs_shim_io_truncate(sqlite3_file* pFile, sqlite3_int64 size) {
    KsqliteVfsShimFile* p = KsqliteVfsShimFileOf(pFile);
    sqlite3_file* pReal = KsqliteVfsShimRealFile(p);
    int rc = pReal->pMethods->xTruncate(pReal, size);

    if (KsqliteVfsShimFileWants(p, KSQLITE_VFS_SHIM_EVENT_TRUNCATE)) {
        KsqliteVfsShimNotifyFile(p, KSQLITE_VFS_SHIM_EVENT_TRUNCATE, size, 0, 0, rc);
    }

    return rc;
}

static int ksqlite_vfs_shim_io_sync(sqlite3_file* pFile, int flags) {
    KsqliteVfsShimFile* p = KsqliteVfsShimFileOf(pFile);
    sqlite3_file* pReal = KsqliteVfsShimRealFile(p);
    int rc = pReal->pMethods->xSync(pReal, flags);

    if (KsqliteVfsShimFileWants(p, KSQLITE_VFS_SHIM_EVENT_SYNC)) {
        KsqliteVfsShimNotifyFile(p, KSQLITE_VFS_SHIM_EVENT_SYNC, flags, 0, 0, rc);
    }

    return rc;
}

static int ksqlite_vfs_shim_io_file_size(sqlite3_file* pFile, sqlite3_int64* pSize) {
    KsqliteVfsShimFile* p = KsqliteVfsShimFileOf(pFile);
    sqlite3_file* pReal = KsqliteVfsShimRealFile(p);
    int rc = pReal->pMethods->xFileSize(pReal, pSize);

    if (KsqliteVfsShimFileWants(p, KSQLITE_VFS_SHIM_EVENT_FILE_SIZE)) {
        KsqliteVfsShimNotifyFile(
            p, KSQLITE_VFS_SHIM_EVENT_FILE_SIZE, KsqliteVfsShimIsOk(rc) ? *pSize : 0, 0, 0, rc
        );
    }

    return rc;
}

static int ksqlite_vfs_shim_io_lock(sqlite3_file* pFile, int eLock) {
    KsqliteVfsShimFile* p = KsqliteVfsShimFileOf(pFile);
    sqlite3_file* pReal = KsqliteVfsShimRealFile(p);
    int rc = pReal->pMethods->xLock(pReal, eLock);

    if (KsqliteVfsShimFileWants(p, KSQLITE_VFS_SHIM_EVENT_LOCK)) {
        KsqliteVfsShimNotifyFile(p, KSQLITE_VFS_SHIM_EVENT_LOCK, eLock, 0, 0, rc);
    }

    return rc;
}

static int ksqlite_vfs_shim_io_unlock(sqlite3_file* pFile, int eLock) {
    KsqliteVfsShimFile* p = KsqliteVfsShimFileOf(pFile);
    sqlite3_file* pReal = KsqliteVfsShimRealFile(p);
    int rc = pReal->pMethods->xUnlock(pReal, eLock);

    if (KsqliteVfsShimFileWants(p, KSQLITE_VFS_SHIM_EVENT_UNLOCK)) {
        KsqliteVfsShimNotifyFile(p, KSQLITE_VFS_SHIM_EVENT_UNLOCK, eLock, 0, 0, rc);
    }

    return rc;
}

static int ksqlite_vfs_shim_io_check_reserved_lock(sqlite3_file* pFile, int* pResOut) {
    KsqliteVfsShimFile* p = KsqliteVfsShimFileOf(pFile);
    sqlite3_file* pReal = KsqliteVfsShimRealFile(p);
    int rc = pReal->pMethods->xCheckReservedLock(pReal, pResOut);

    if (KsqliteVfsShimFileWants(p, KSQLITE_VFS_SHIM_EVENT_CHECK_RESERVED_LOCK)) {
        KsqliteVfsShimNotifyFile(
            p,
            KSQLITE_VFS_SHIM_EVENT_CHECK_RESERVED_LOCK,
            KsqliteVfsShimIsOk(rc) ? *pResOut : 0,
            0,
            0,
            rc
        );
    }

    return rc;
}

static int ksqlite_vfs_shim_io_file_control(sqlite3_file* pFile, int op, void* pArg) {
    KsqliteVfsShimFile* p = KsqliteVfsShimFileOf(pFile);
    sqlite3_file* pReal = KsqliteVfsShimRealFile(p);
    int rc = pReal->pMethods->xFileControl(pReal, op, pArg);

    if (KsqliteVfsShimFileWants(p, KSQLITE_VFS_SHIM_EVENT_FILE_CONTROL)) {
        KsqliteVfsShimNotifyFile(p, KSQLITE_VFS_SHIM_EVENT_FILE_CONTROL, op, 0, 0, rc);
    }

    return rc;
}

static int ksqlite_vfs_shim_io_sector_size(sqlite3_file* pFile) {
    sqlite3_file* pReal = KsqliteVfsShimRealFile(pFile);
    return pReal->pMethods->xSectorSize(pReal);
}

static int ksqlite_vfs_shim_io_device_characteristics(sqlite3_file* pFile) {
    sqlite3_file* pReal = KsqliteVfsShimRealFile(pFile);
    return pReal->pMethods->xDeviceCharacteristics(pReal);
}

static int ksqlite_vfs_shim_io_shm_map(
    sqlite3_file* pFile,
    int iPg,
    int pgsz,
    int isWrite,
    void volatile** pp
) {
    KsqliteVfsShimFile* p = KsqliteVfsShimFileOf(pFile);
    sqlite3_file* pReal = KsqliteVfsShimRealFile(p);
    int rc = pReal->pMethods->xShmMap(pReal, iPg, pgsz, isWrite, pp);

    if (KsqliteVfsShimFileWants(p, KSQLITE_VFS_SHIM_EVENT_SHM_MAP)) {
        KsqliteVfsShimNotifyFile(p, KSQLITE_VFS_SHIM_EVENT_SHM_MAP, iPg, pgsz, isWrite, rc);
    }

    return rc;
}

static int ksqlite_vfs_shim_io_shm_lock(sqlite3_file* pFile, int offset, int n, int flags) {
    KsqliteVfsShimFile* p = KsqliteVfsShimFileOf(pFile);
    sqlite3_file* pReal = KsqliteVfsShimRealFile(p);
    int rc = pReal->pMethods->xShmLock(pReal, offset, n, flags);

    if (KsqliteVfsShimFileWants(p, KSQLITE_VFS_SHIM_EVENT_SHM_LOCK)) {
        KsqliteVfsShimNotifyFile(p, KSQLITE_VFS_SHIM_EVENT_SHM_LOCK, offset, n, flags, rc);
    }

    return rc;
}

static void ksqlite_vfs_shim_io_shm_barrier(sqlite3_file* pFile) {
    KsqliteVfsShimFile* p = KsqliteVfsShimFileOf(pFile);
    sqlite3_file* pReal = KsqliteVfsShimRealFile(p);
    pReal->pMethods->xShmBarrier(pReal);

    if (KsqliteVfsShimFileWants(p, KSQLITE_VFS_SHIM_EVENT_SHM_BARRIER)) {
        KsqliteVfsShimNotifyFile(p, KSQLITE_VFS_SHIM_EVENT_SHM_BARRIER, 0, 0, 0, SQLITE_OK);
    }
}

static int ksqlite_vfs_shim_io_shm_unmap(sqlite3_file* pFile, int deleteFlag) {
    KsqliteVfsShimFile* p = KsqliteVfsShimFileOf(pFile);
    sqlite3_file* pReal = KsqliteVfsShimRealFile(p);
    int rc = pReal->pMethods->xShmUnmap(pReal, deleteFlag);

    if (KsqliteVfsShimFileWants(p, KSQLITE_VFS_SHIM_EVENT_SHM_UNMAP)) {
        KsqliteVfsShimNotifyFile(p, KSQLITE_VFS_SHIM_EVENT_SHM_UNMAP, deleteFlag, 0, 0, rc);
    }

    return rc;
}

static int ksqlite_vfs_shim_io_fetch(
    sqlite3_file* pFile,
    sqlite3_int64 iOfst,
    int iAmt,
    void** pp
) {
    KsqliteVfsShimFile* p = KsqliteVfsShimFileOf(pFile);
    sqlite3_file* pReal = KsqliteVfsShimRealFile(p);
    int rc = pReal->pMethods->xFetch(pReal, iOfst, iAmt, pp);

    if (KsqliteVfsShimFileWants(p, KSQLITE_VFS_SHIM_EVENT_FETCH)) {
        KsqliteVfsShimNotifyFile(
            p,
            KSQLITE_VFS_SHIM_EVENT_FETCH,
            iAmt,
            iOfst,
            KsqliteVfsShimIsOk(rc) && *pp != 0 ? 1 : 0,
            rc
        );
    }

    return rc;
}

static int ksqlite_vfs_shim_io_unfetch(sqlite3_file* pFile, sqlite3_int64 iOfst, void* pPage) {
    KsqliteVfsShimFile* p = KsqliteVfsShimFileOf(pFile);
    sqlite3_file* pReal = KsqliteVfsShimRealFile(p);
    int rc = pReal->pMethods->xUnfetch(pReal, iOfst, pPage);

    if (KsqliteVfsShimFileWants(p, KSQLITE_VFS_SHIM_EVENT_UNFETCH)) {
        KsqliteVfsShimNotifyFile(p, KSQLITE_VFS_SHIM_EVENT_UNFETCH, iOfst, pPage != 0, 0, rc);
    }

    return rc;
}

// sqlite3_vfs

static int ksqlite_vfs_shim_open(
    sqlite3_vfs* pVfs,
    sqlite3_filename zName,
    sqlite3_file* pFile,
    int flags,
    int* pOutFlags
) {
    KsqliteVfsShim* pShim = KsqliteVfsShimOf(pVfs);
    KsqliteVfsShimFile* p = KsqliteVfsShimFileOf(pFile);
    sqlite3_file* pReal = KsqliteVfsShimRealFile(p);
    const sqlite3_io_methods* pRealMethods;
    int rc;

    // SQLite doesn't zero the allocation, and a failed open must leave pMethods NULL.
    memset(p, 0, sizeof(KsqliteVfsShimFile));

    rc = pShim->pReal->xOpen(pShim->pReal, zName, pReal, flags, pOutFlags);
    pRealMethods = pReal->pMethods;

    if (pRealMethods == 0) {
        return rc;
    }

    // A failed open must leave pMethods NULL (see sqlite3OsOpen), so SQLite never calls xClose on
    // it. If the underlying xOpen failed after opening its file anyway (as the xOpen contract
    // permits), close it here instead.
    if (rc != SQLITE_OK) {
        pRealMethods->xClose(pReal);
        return rc;
    }

    p->methods.iVersion = pRealMethods->iVersion;
    p->methods.xClose = ksqlite_vfs_shim_io_close;
    p->methods.xRead = ksqlite_vfs_shim_io_read;
    p->methods.xWrite = ksqlite_vfs_shim_io_write;
    p->methods.xTruncate = ksqlite_vfs_shim_io_truncate;
    p->methods.xSync = ksqlite_vfs_shim_io_sync;
    p->methods.xFileSize = ksqlite_vfs_shim_io_file_size;
    p->methods.xLock = ksqlite_vfs_shim_io_lock;
    p->methods.xUnlock = ksqlite_vfs_shim_io_unlock;
    p->methods.xCheckReservedLock = ksqlite_vfs_shim_io_check_reserved_lock;
    p->methods.xFileControl = ksqlite_vfs_shim_io_file_control;
    p->methods.xSectorSize = ksqlite_vfs_shim_io_sector_size;
    p->methods.xDeviceCharacteristics = ksqlite_vfs_shim_io_device_characteristics;

    if (pRealMethods->iVersion >= 2) {
        p->methods.xShmMap = pRealMethods->xShmMap ? ksqlite_vfs_shim_io_shm_map : 0;
        p->methods.xShmLock = pRealMethods->xShmLock ? ksqlite_vfs_shim_io_shm_lock : 0;
        p->methods.xShmBarrier = pRealMethods->xShmBarrier ? ksqlite_vfs_shim_io_shm_barrier : 0;
        p->methods.xShmUnmap = pRealMethods->xShmUnmap ? ksqlite_vfs_shim_io_shm_unmap : 0;
    }

    if (pRealMethods->iVersion >= 3) {
        p->methods.xFetch = pRealMethods->xFetch ? ksqlite_vfs_shim_io_fetch : 0;
        p->methods.xUnfetch = pRealMethods->xUnfetch ? ksqlite_vfs_shim_io_unfetch : 0;
    }

    p->pShim = pShim;
    p->events = pShim->initialFileEvents;
    p->base.pMethods = &p->methods;

    // Always reported: the handler attaches its own per-file state here. The initial events are
    // already applied, so the handler can adjust them for this particular file.
    pShim->xEvent(
        pShim->pAppData,
        pFile,
        KSQLITE_VFS_SHIM_EVENT_OPEN,
        flags,
        pOutFlags ? *pOutFlags : 0,
        0,
        zName,
        0,
        rc
    );

    return rc;
}

static int ksqlite_vfs_shim_delete(sqlite3_vfs* pVfs, const char* zName, int syncDir) {
    KsqliteVfsShim* pShim = KsqliteVfsShimOf(pVfs);
    int rc = pShim->pReal->xDelete(pShim->pReal, zName, syncDir);

    if (KsqliteVfsShimVfsWants(pShim, KSQLITE_VFS_SHIM_EVENT_DELETE)) {
        pShim->xEvent(
            pShim->pAppData, 0, KSQLITE_VFS_SHIM_EVENT_DELETE, syncDir, 0, 0, zName, 0, rc
        );
    }

    return rc;
}

static int ksqlite_vfs_shim_access(sqlite3_vfs* pVfs, const char* zName, int flags, int* pResOut) {
    KsqliteVfsShim* pShim = KsqliteVfsShimOf(pVfs);
    int rc = pShim->pReal->xAccess(pShim->pReal, zName, flags, pResOut);

    if (KsqliteVfsShimVfsWants(pShim, KSQLITE_VFS_SHIM_EVENT_ACCESS)) {
        pShim->xEvent(
            pShim->pAppData,
            0,
            KSQLITE_VFS_SHIM_EVENT_ACCESS,
            flags,
            KsqliteVfsShimIsOk(rc) ? *pResOut : 0,
            0,
            zName,
            0,
            rc
        );
    }

    return rc;
}

static int ksqlite_vfs_shim_full_pathname(
    sqlite3_vfs* pVfs,
    const char* zName,
    int nOut,
    char* zOut
) {
    KsqliteVfsShim* pShim = KsqliteVfsShimOf(pVfs);
    int rc = pShim->pReal->xFullPathname(pShim->pReal, zName, nOut, zOut);

    if (KsqliteVfsShimVfsWants(pShim, KSQLITE_VFS_SHIM_EVENT_FULL_PATHNAME)) {
        pShim->xEvent(
            pShim->pAppData,
            0,
            KSQLITE_VFS_SHIM_EVENT_FULL_PATHNAME,
            0,
            0,
            0,
            zName,
            KsqliteVfsShimIsOk(rc) ? zOut : 0,
            rc
        );
    }

    return rc;
}

// Forward-only: none of these say anything about database I/O.

static void* ksqlite_vfs_shim_dl_open(sqlite3_vfs* pVfs, const char* zFilename) {
    sqlite3_vfs* pReal = KsqliteVfsShimOf(pVfs)->pReal;
    return pReal->xDlOpen(pReal, zFilename);
}

static void ksqlite_vfs_shim_dl_error(sqlite3_vfs* pVfs, int nByte, char* zErrMsg) {
    sqlite3_vfs* pReal = KsqliteVfsShimOf(pVfs)->pReal;
    pReal->xDlError(pReal, nByte, zErrMsg);
}

static void (*ksqlite_vfs_shim_dl_sym(sqlite3_vfs* pVfs, void* pHandle, const char* zSymbol))(void) {
    sqlite3_vfs* pReal = KsqliteVfsShimOf(pVfs)->pReal;
    return pReal->xDlSym(pReal, pHandle, zSymbol);
}

static void ksqlite_vfs_shim_dl_close(sqlite3_vfs* pVfs, void* pHandle) {
    sqlite3_vfs* pReal = KsqliteVfsShimOf(pVfs)->pReal;
    pReal->xDlClose(pReal, pHandle);
}

static int ksqlite_vfs_shim_randomness(sqlite3_vfs* pVfs, int nByte, char* zOut) {
    sqlite3_vfs* pReal = KsqliteVfsShimOf(pVfs)->pReal;
    return pReal->xRandomness(pReal, nByte, zOut);
}

static int ksqlite_vfs_shim_sleep(sqlite3_vfs* pVfs, int microseconds) {
    sqlite3_vfs* pReal = KsqliteVfsShimOf(pVfs)->pReal;
    return pReal->xSleep(pReal, microseconds);
}

static int ksqlite_vfs_shim_current_time(sqlite3_vfs* pVfs, double* pTimeOut) {
    sqlite3_vfs* pReal = KsqliteVfsShimOf(pVfs)->pReal;
    return pReal->xCurrentTime(pReal, pTimeOut);
}

static int ksqlite_vfs_shim_get_last_error(sqlite3_vfs* pVfs, int nErr, char* zErr) {
    sqlite3_vfs* pReal = KsqliteVfsShimOf(pVfs)->pReal;
    return pReal->xGetLastError(pReal, nErr, zErr);
}

static int ksqlite_vfs_shim_current_time_int64(sqlite3_vfs* pVfs, sqlite3_int64* pTimeOut) {
    sqlite3_vfs* pReal = KsqliteVfsShimOf(pVfs)->pReal;
    return pReal->xCurrentTimeInt64(pReal, pTimeOut);
}

static int ksqlite_vfs_shim_set_system_call(
    sqlite3_vfs* pVfs,
    const char* zName,
    sqlite3_syscall_ptr pCall
) {
    sqlite3_vfs* pReal = KsqliteVfsShimOf(pVfs)->pReal;
    return pReal->xSetSystemCall(pReal, zName, pCall);
}

static sqlite3_syscall_ptr ksqlite_vfs_shim_get_system_call(sqlite3_vfs* pVfs, const char* zName) {
    sqlite3_vfs* pReal = KsqliteVfsShimOf(pVfs)->pReal;
    return pReal->xGetSystemCall(pReal, zName);
}

static const char* ksqlite_vfs_shim_next_system_call(sqlite3_vfs* pVfs, const char* zName) {
    sqlite3_vfs* pReal = KsqliteVfsShimOf(pVfs)->pReal;
    return pReal->xNextSystemCall(pReal, zName);
}

// Public API

int ksqlite_vfs_shim_register(
    const char* zName,
    const char* zUnderlying,
    unsigned int listenedEvents,
    unsigned int initialFileEvents,
    ksqlite_xVfsShimEvent xEvent,
    void* pAppData,
    sqlite3_vfs** ppVfs
) {
    sqlite3_vfs* pReal;
    KsqliteVfsShim* pShim;
    char* zNameCopy;
    size_t nName;
    int rc;

    *ppVfs = 0;

    pReal = sqlite3_vfs_find(zUnderlying);
    if (pReal == 0) {
        return SQLITE_NOTFOUND;
    }

    nName = strlen(zName);
    pShim = (KsqliteVfsShim*) sqlite3_malloc64(sizeof(KsqliteVfsShim) + nName + 1);
    if (pShim == 0) {
        return SQLITE_NOMEM;
    }

    memset(pShim, 0, sizeof(KsqliteVfsShim));
    zNameCopy = (char*) &pShim[1];
    memcpy(zNameCopy, zName, nName + 1);

    pShim->pReal = pReal;
    pShim->listenedEvents = listenedEvents;
    pShim->initialFileEvents = initialFileEvents;
    pShim->xEvent = xEvent;
    pShim->pAppData = pAppData;

    pShim->base.iVersion = pReal->iVersion;
    pShim->base.szOsFile = KSQLITE_VFS_SHIM_FILE_HEADER_SIZE + pReal->szOsFile;
    pShim->base.mxPathname = pReal->mxPathname;
    pShim->base.zName = zNameCopy;
    pShim->base.xOpen = ksqlite_vfs_shim_open;
    pShim->base.xDelete = ksqlite_vfs_shim_delete;
    pShim->base.xAccess = ksqlite_vfs_shim_access;
    pShim->base.xFullPathname = ksqlite_vfs_shim_full_pathname;
    pShim->base.xDlOpen = pReal->xDlOpen ? ksqlite_vfs_shim_dl_open : 0;
    pShim->base.xDlError = pReal->xDlError ? ksqlite_vfs_shim_dl_error : 0;
    pShim->base.xDlSym = pReal->xDlSym ? ksqlite_vfs_shim_dl_sym : 0;
    pShim->base.xDlClose = pReal->xDlClose ? ksqlite_vfs_shim_dl_close : 0;
    pShim->base.xRandomness = ksqlite_vfs_shim_randomness;
    pShim->base.xSleep = ksqlite_vfs_shim_sleep;
    pShim->base.xCurrentTime = ksqlite_vfs_shim_current_time;
    pShim->base.xGetLastError = pReal->xGetLastError ? ksqlite_vfs_shim_get_last_error : 0;

    if (pReal->iVersion >= 2) {
        pShim->base.xCurrentTimeInt64 =
            pReal->xCurrentTimeInt64 ? ksqlite_vfs_shim_current_time_int64 : 0;
    }

    if (pReal->iVersion >= 3) {
        pShim->base.xSetSystemCall = pReal->xSetSystemCall ? ksqlite_vfs_shim_set_system_call : 0;
        pShim->base.xGetSystemCall = pReal->xGetSystemCall ? ksqlite_vfs_shim_get_system_call : 0;
        pShim->base.xNextSystemCall =
            pReal->xNextSystemCall ? ksqlite_vfs_shim_next_system_call : 0;
    }

    rc = sqlite3_vfs_register(&pShim->base, 0);
    if (rc != SQLITE_OK) {
        sqlite3_free(pShim);
        return rc;
    }

    *ppVfs = &pShim->base;
    return SQLITE_OK;
}

int ksqlite_vfs_shim_unregister(sqlite3_vfs* pVfs) {
    int rc = sqlite3_vfs_unregister(pVfs);

    if (rc == SQLITE_OK) {
        sqlite3_free(pVfs);
    }

    return rc;
}

void* ksqlite_vfs_shim_app_data(sqlite3_vfs* pVfs) {
    return KsqliteVfsShimOf(pVfs)->pAppData;
}

void* ksqlite_vfs_shim_file_data(sqlite3_file* pFile) {
    return KsqliteVfsShimFileOf(pFile)->pData;
}

void ksqlite_vfs_shim_file_set_data(sqlite3_file* pFile, void* pData) {
    KsqliteVfsShimFileOf(pFile)->pData = pData;
}

unsigned int ksqlite_vfs_shim_file_events(sqlite3_file* pFile) {
    return KsqliteVfsShimFileOf(pFile)->events;
}

void ksqlite_vfs_shim_file_set_events(sqlite3_file* pFile, unsigned int events) {
    KsqliteVfsShimFileOf(pFile)->events = events;
}

sqlite3_file* ksqlite_vfs_shim_file_lookup(
    sqlite3_vfs* pVfs,
    sqlite3* db,
    const char* zSchema,
    int journal
) {
    sqlite3_file* pFile = 0;
    int op = journal ? SQLITE_FCNTL_JOURNAL_POINTER : SQLITE_FCNTL_FILE_POINTER;

    // SQLite hands back the connection's own sqlite3_file, opened or not, through whichever VFS.
    // It's one of ours only if it's open with our own methods, and belongs to this shim.
    if (sqlite3_file_control(db, zSchema, op, &pFile) != SQLITE_OK
        || pFile == 0
        || pFile->pMethods == 0
        || pFile->pMethods->xClose != ksqlite_vfs_shim_io_close
        || KsqliteVfsShimFileOf(pFile)->pShim != KsqliteVfsShimOf(pVfs)) {
        return 0;
    }

    return pFile;
}

///////////////////////////////////////////////////////////////////////////
// Misc
///////////////////////////////////////////////////////////////////////////

#ifdef SQLITE_ENABLE_SQLLOG

// Requirement of SQLITE_ENABLE_SQLLOG
__attribute__((unused))
void sqlite3_init_sqllog(void) {
    // No logging by default, it is up to the application to set its own logging interceptor using
    // sqlite3_config
}

#endif