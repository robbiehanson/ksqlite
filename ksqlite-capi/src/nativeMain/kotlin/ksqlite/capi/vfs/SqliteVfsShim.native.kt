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
@file:Suppress("ClassName", "FunctionName")

package ksqlite.capi.vfs

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKStringFromUtf8
import kotlinx.cinterop.toLong
import kotlinx.cinterop.value
import ksqlite.capi.sqlite3
import ksqlite.capi.sqlite3_log
import ksqlite.types.SqliteResultCode
import ksqlite.types.internal.convertResultCode
import ksqlite.foreign.ksqlite_vfs_shim_app_data as native_vfs_shim_app_data
import ksqlite.foreign.ksqlite_vfs_shim_file_data as native_vfs_shim_file_data
import ksqlite.foreign.ksqlite_vfs_shim_file_lookup as native_vfs_shim_file_lookup
import ksqlite.foreign.ksqlite_vfs_shim_file_set_data as native_vfs_shim_file_set_data
import ksqlite.foreign.ksqlite_vfs_shim_file_set_events as native_vfs_shim_file_set_events
import ksqlite.foreign.ksqlite_vfs_shim_register as native_vfs_shim_register
import ksqlite.foreign.ksqlite_vfs_shim_unregister as native_vfs_shim_unregister
import ksqlite.foreign.sqlite3_file as s3_file
import ksqlite.foreign.sqlite3_vfs as s3_vfs

// The shim itself (forwarding, file layout, event gating) lives in ksqlite.c. This side only
// routes its events to the Kotlin dispatcher: the shim's pAppData is a StableRef to the
// VfsShimDispatcher, and each file's data slot a StableRef to its SqliteVfsShimFile.

private fun CPointer<s3_file>.shimFile(): SqliteVfsShimFile? =
    native_vfs_shim_file_data(this)?.asStableRef<SqliteVfsShimFile>()?.get()

private val VfsShimEventHandler = staticCFunction {
        appData: COpaquePointer?,
        file: CPointer<s3_file>?,
        event: Int,
        a: Long,
        b: Long,
        c: Long,
        z1: CPointer<ByteVar>?,
        z2: CPointer<ByteVar>?,
        rc: Int ->
    // Listener exceptions are already contained by the dispatcher; this only guards against the
    // handler's own bugs unwinding through SQLite's C frames.
    try {
        val dispatcher = appData!!.asStableRef<VfsShimDispatcher>().get()

        when (event) {
            VFS_SHIM_EVENT_OPEN -> {
                val shimFile = dispatcher.createFile(file!!.toLong(), z1?.toKStringFromUtf8(), a.toInt())
                native_vfs_shim_file_set_data(file, StableRef.create(shimFile).asCPointer())
                dispatcher.onOpened(shimFile)
            }

            VFS_SHIM_EVENT_CLOSE -> {
                val ref = native_vfs_shim_file_data(file!!)?.asStableRef<SqliteVfsShimFile>()
                dispatcher.onClose(ref?.get(), isEnabled = a != 0L, rc = rc)
                native_vfs_shim_file_set_data(file, null)
                ref?.dispose()
            }

            else -> dispatcher.onEvent(
                file = file?.shimFile(),
                event = event,
                a = a,
                b = b,
                c = c,
                z1 = z1?.toKStringFromUtf8(),
                z2 = z2?.toKStringFromUtf8(),
                rc = rc,
            )
        }
    } catch (exception: Throwable) {
        sqlite3_log(
            SqliteResultCode.WARNING.code,
            "VFS shim event handler threw\n${exception.stackTraceToString()}"
        )
    }

    Unit
}

internal actual fun vfsShimRegister(
    name: String,
    underlyingVfsName: String?,
    listenedEvents: Int,
    initialFileEvents: Int,
    dispatcher: VfsShimDispatcher,
): Long? = memScoped {
    val dispatcherRef = StableRef.create(dispatcher)
    val outVfs = alloc<CPointerVar<s3_vfs>>()

    val result = native_vfs_shim_register(
        name,
        underlyingVfsName,
        listenedEvents.toUInt(),
        initialFileEvents.toUInt(),
        VfsShimEventHandler,
        dispatcherRef.asCPointer(),
        outVfs.ptr,
    )

    val vfs = outVfs.value

    if (result != 0 || vfs == null) {
        dispatcherRef.dispose()
        null
    } else {
        vfs.toLong()
    }
}

internal actual fun vfsShimUnregister(vfsPointer: Long): SqliteResultCode {
    val vfs = vfsPointer.toCPointer<s3_vfs>()!!
    val dispatcherRef = native_vfs_shim_app_data(vfs)?.asStableRef<VfsShimDispatcher>()
    val result = convertResultCode(native_vfs_shim_unregister(vfs))

    if (result == SqliteResultCode.OK) {
        dispatcherRef?.dispose()
    }

    return result
}

internal actual fun vfsShimFileLookup(
    vfsPointer: Long,
    db: sqlite3,
    schema: String,
    journal: Boolean,
): SqliteVfsShimFile? =
    native_vfs_shim_file_lookup(vfsPointer.toCPointer(), db.pointer, schema, if (journal) 1 else 0)
        ?.shimFile()

internal actual fun vfsShimFileSetEvents(filePointer: Long, events: Int) {
    native_vfs_shim_file_set_events(filePointer.toCPointer(), events.toUInt())
}
