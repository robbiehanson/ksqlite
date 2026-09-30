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

import ksqlite.capi.memory.StaticMemoryAllocator
import ksqlite.capi.memory.allocateUtf8
import ksqlite.capi.memory.memScoped
import ksqlite.capi.memory.toKStringFromUtf8OrNull
import ksqlite.capi.sqlite3
import ksqlite.capi.sqlite3_log
import ksqlite.foreign.ksqlite_xVfsShimEvent
import ksqlite.internal.runtime.concurrency.ConcurrentMutableMap
import ksqlite.types.SqliteResultCode
import ksqlite.types.internal.convertResultCode
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.atomic.AtomicLong
import ksqlite.foreign.sqlite3 as native

// The shim itself (forwarding, file layout, event gating) lives in ksqlite.c. This side only
// routes its events to the Kotlin dispatcher.
//
// Unlike Kotlin/Native (StableRef) or JNI (global refs), FFM can't store a JVM object reference in
// native memory. So the shim's pAppData holds an ID into VfsShimDispatchers, and each file's
// SqliteVfsShimFile is kept in VfsShimFiles, keyed by the file's native address (unique while the
// file is open; entries are added on OPEN and removed on CLOSE).

private val VfsShimDispatchers = ConcurrentMutableMap<Long, VfsShimDispatcher>()

private val VfsShimFiles = ConcurrentMutableMap<Long, SqliteVfsShimFile>()

private val NextVfsShimDispatcherId = AtomicLong(1)

private val VfsShimEventHandler: MemorySegment = ksqlite_xVfsShimEvent.allocate(
    { appData, file, event, a, b, c, z1, z2, rc ->
        // An exception escaping an FFM upcall terminates the JVM. Listener exceptions are already
        // contained by the dispatcher; this only guards against the handler's own bugs.
        try {
            val dispatcher = VfsShimDispatchers[appData.address()]
            val fileAddress = file.address()

            when {
                dispatcher == null -> Unit

                event == VFS_SHIM_EVENT_OPEN -> {
                    val shimFile = dispatcher.createFile(fileAddress, z1.toKStringFromUtf8OrNull(), a.toInt())
                    VfsShimFiles[fileAddress] = shimFile
                    dispatcher.onOpened(shimFile)
                }

                event == VFS_SHIM_EVENT_CLOSE -> {
                    dispatcher.onClose(VfsShimFiles[fileAddress], isEnabled = a != 0L, rc = rc)
                    VfsShimFiles.remove(fileAddress)
                }

                else -> dispatcher.onEvent(
                    file = if (fileAddress == 0L) null else VfsShimFiles[fileAddress],
                    event = event,
                    a = a,
                    b = b,
                    c = c,
                    z1 = z1.toKStringFromUtf8OrNull(),
                    z2 = z2.toKStringFromUtf8OrNull(),
                    rc = rc,
                )
            }
        } catch (exception: Throwable) {
            sqlite3_log(
                SqliteResultCode.WARNING.code,
                "VFS shim event handler threw\n${exception.stackTraceToString()}"
            )
        }
    },
    StaticMemoryAllocator,
)

internal actual fun vfsShimRegister(
    name: String,
    underlyingVfsName: String?,
    listenedEvents: Int,
    initialFileEvents: Int,
    dispatcher: VfsShimDispatcher,
): Long? {
    val dispatcherId = NextVfsShimDispatcherId.getAndIncrement()
    VfsShimDispatchers[dispatcherId] = dispatcher

    val vfsAddress = memScoped {
        val outVfs = allocate(ValueLayout.ADDRESS)

        val result = native.ksqlite_vfs_shim_register(
            name.allocateUtf8(),
            underlyingVfsName.allocateUtf8(),
            listenedEvents,
            initialFileEvents,
            VfsShimEventHandler,
            MemorySegment.ofAddress(dispatcherId),
            outVfs,
        )

        if (result == 0) outVfs.get(ValueLayout.ADDRESS, 0).address() else 0L
    }

    if (vfsAddress == 0L) {
        VfsShimDispatchers.remove(dispatcherId)
        return null
    }

    return vfsAddress
}

internal actual fun vfsShimUnregister(vfsPointer: Long): SqliteResultCode {
    val vfs = MemorySegment.ofAddress(vfsPointer)
    val dispatcherId = native.ksqlite_vfs_shim_app_data(vfs).address()
    val result = convertResultCode(native.ksqlite_vfs_shim_unregister(vfs))

    if (result == SqliteResultCode.OK) {
        VfsShimDispatchers.remove(dispatcherId)
    }

    return result
}

internal actual fun vfsShimFileLookup(
    vfsPointer: Long,
    db: sqlite3,
    schema: String,
    journal: Boolean,
): SqliteVfsShimFile? {
    val file = memScoped {
        native.ksqlite_vfs_shim_file_lookup(
            MemorySegment.ofAddress(vfsPointer),
            db.pointer,
            schema.allocateUtf8(),
            if (journal) 1 else 0,
        )
    }

    return if (file.address() == 0L) null else VfsShimFiles[file.address()]
}

internal actual fun vfsShimFileSetEvents(filePointer: Long, events: Int) {
    native.ksqlite_vfs_shim_file_set_events(MemorySegment.ofAddress(filePointer), events)
}
