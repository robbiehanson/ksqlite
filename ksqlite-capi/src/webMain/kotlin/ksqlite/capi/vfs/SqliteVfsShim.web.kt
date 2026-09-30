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
@file:Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")

package ksqlite.capi.vfs

import ksqlite.capi.exports
import ksqlite.capi.memory.allocateUtf8Pointer
import ksqlite.capi.memory.heapScoped
import ksqlite.capi.memory.toKStringFromUtf8OrNull
import ksqlite.capi.sqlite3
import ksqlite.capi.sqlite3_log
import ksqlite.capi.wasm
import ksqlite.foreign.wasm.FunctionSignature
import ksqlite.foreign.wasm.JsFunction
import ksqlite.foreign.wasm.WasmPointer
import ksqlite.foreign.wasm.installFunction
import ksqlite.types.SqliteResultCode
import ksqlite.types.internal.convertResultCode
import kotlin.js.toJsBigInt
import kotlin.js.toLong
import ksqlite.foreign.wasm.FunctionSignature.Int32 as I32
import ksqlite.foreign.wasm.FunctionSignature.Int64 as I64
import ksqlite.foreign.wasm.FunctionSignature.Pointer as Ptr

// The shim itself (forwarding, file layout, event gating) lives in ksqlite.c. This side only
// routes its events to the Kotlin dispatcher.
//
// As on the JVM, a Kotlin object can't be stored in wasm memory, so the shim's pAppData holds an
// ID into VfsShimDispatchers, and each file's SqliteVfsShimFile is kept in VfsShimFiles, keyed by
// the file's address (unique while the file is open; entries are added on OPEN and removed on
// CLOSE). The web is single-threaded, so plain maps suffice.

private val VfsShimDispatchers = mutableMapOf<Long, VfsShimDispatcher>()

private val VfsShimFiles = mutableMapOf<Long, SqliteVfsShimFile>()

private var nextVfsShimDispatcherId = 1L

@JsFun("(handler) => (p0, p1, p2, p3, p4, p5, p6, p7, p8) => handler(p0, p1, p2, p3, p4, p5, p6, p7, p8)")
private external fun vfsShimEvent(
    handler: (
        appData: WasmPointer,
        file: WasmPointer,
        event: Int,
        a: Long,
        b: Long,
        c: Long,
        z1: WasmPointer,
        z2: WasmPointer,
        rc: Int,
    ) -> Unit
): JsFunction

private val VfsShimEventHandler: WasmPointer by lazy {
    wasm.installFunction(
        signature = FunctionSignature.Void(Ptr, Ptr, I32, I64, I64, I64, Ptr, Ptr, I32),
        function = vfsShimEvent { appData, file, event, a, b, c, z1, z2, rc ->
            // An exception must not propagate through wasm into SQLite. Listener exceptions are
            // already contained by the dispatcher; this only guards against the handler's own bugs.
            try {
                handleVfsShimEvent(appData.toLong(), file.toLong(), event, a, b, c, z1, z2, rc)
            } catch (exception: Throwable) {
                sqlite3_log(
                    SqliteResultCode.WARNING.code,
                    "VFS shim event handler threw\n${exception.stackTraceToString()}"
                )
            }
        }
    )
}

@Suppress("LongParameterList")
private fun handleVfsShimEvent(
    dispatcherId: Long,
    fileAddress: Long,
    event: Int,
    a: Long,
    b: Long,
    c: Long,
    z1: WasmPointer,
    z2: WasmPointer,
    rc: Int,
) {
    val dispatcher = VfsShimDispatchers[dispatcherId] ?: return

    when (event) {
        VFS_SHIM_EVENT_OPEN -> {
            val shimFile = dispatcher.createFile(fileAddress, z1.toKStringFromUtf8OrNull(), a.toInt())
            VfsShimFiles[fileAddress] = shimFile
            dispatcher.onOpened(shimFile)
        }

        VFS_SHIM_EVENT_CLOSE -> {
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
}

internal actual fun vfsShimRegister(
    name: String,
    underlyingVfsName: String?,
    listenedEvents: Int,
    initialFileEvents: Int,
    dispatcher: VfsShimDispatcher,
): Long? {
    val dispatcherId = nextVfsShimDispatcherId++
    VfsShimDispatchers[dispatcherId] = dispatcher

    val vfsAddress = heapScoped {
        val outVfs = allocatePointer()

        val result = exports.ksqlite_vfs_shim_register(
            name.allocateUtf8Pointer(),
            underlyingVfsName.allocateUtf8Pointer(),
            listenedEvents,
            initialFileEvents,
            VfsShimEventHandler,
            dispatcherId.toJsBigInt(),
            outVfs,
        )

        if (result == 0) wasm.peekPtr(outVfs).toLong() else 0L
    }

    if (vfsAddress == 0L) {
        VfsShimDispatchers.remove(dispatcherId)
        return null
    }

    return vfsAddress
}

internal actual fun vfsShimUnregister(vfsPointer: Long): SqliteResultCode {
    val vfs = vfsPointer.toJsBigInt()
    val dispatcherId = exports.ksqlite_vfs_shim_app_data(vfs).toLong()
    val result = convertResultCode(exports.ksqlite_vfs_shim_unregister(vfs))

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
    val fileAddress = heapScoped {
        exports.ksqlite_vfs_shim_file_lookup(
            vfsPointer.toJsBigInt(),
            db.pointer,
            schema.allocateUtf8Pointer(),
            if (journal) 1 else 0,
        ).toLong()
    }

    return if (fileAddress == 0L) null else VfsShimFiles[fileAddress]
}

internal actual fun vfsShimFileSetEvents(filePointer: Long, events: Int) {
    exports.ksqlite_vfs_shim_file_set_events(filePointer.toJsBigInt(), events)
}
