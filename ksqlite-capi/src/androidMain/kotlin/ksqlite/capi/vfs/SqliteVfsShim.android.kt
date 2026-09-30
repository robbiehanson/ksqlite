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
import ksqlite.foreign.callbacks.VfsShimCallbacks
import ksqlite.types.SqliteResultCode
import ksqlite.types.internal.convertResultCode
import ksqlite.foreign.vfsShimFileLookup as jni_vfsShimFileLookup
import ksqlite.foreign.vfsShimFileSetEvents as jni_vfsShimFileSetEvents
import ksqlite.foreign.vfsShimRegister as jni_vfsShimRegister
import ksqlite.foreign.vfsShimUnregister as jni_vfsShimUnregister

// The shim itself (forwarding, file layout, event gating) lives in ksqlite.c, with thin C++
// trampolines routing its events here.

private class ShimCallbacks(private val dispatcher: VfsShimDispatcher) : VfsShimCallbacks {

    override fun onOpen(filePointer: Long, filename: String?, flags: Int): Any =
        dispatcher.createFile(filePointer, filename, flags)

    override fun onEvent(
        file: Any?,
        event: Int,
        a: Long,
        b: Long,
        c: Long,
        z1: String?,
        z2: String?,
        result: Int,
    ) {
        if (event == VFS_SHIM_EVENT_OPEN) {
            dispatcher.onOpened(file as SqliteVfsShimFile)
        } else {
            dispatcher.onEvent(file as SqliteVfsShimFile?, event, a, b, c, z1, z2, result)
        }
    }

    override fun onClose(file: Any, isEnabled: Boolean, result: Int) {
        dispatcher.onClose(file as SqliteVfsShimFile, isEnabled, result)
    }
}

internal actual fun vfsShimRegister(
    name: String,
    underlyingVfsName: String?,
    listenedEvents: Int,
    initialFileEvents: Int,
    dispatcher: VfsShimDispatcher,
): Long? = jni_vfsShimRegister(
    name,
    underlyingVfsName,
    listenedEvents,
    initialFileEvents,
    ShimCallbacks(dispatcher),
).takeIf { it != 0L }

internal actual fun vfsShimUnregister(vfsPointer: Long): SqliteResultCode =
    convertResultCode(jni_vfsShimUnregister(vfsPointer))

internal actual fun vfsShimFileLookup(
    vfsPointer: Long,
    db: sqlite3,
    schema: String,
    journal: Boolean,
): SqliteVfsShimFile? =
    jni_vfsShimFileLookup(vfsPointer, db.pointer, schema, journal) as SqliteVfsShimFile?

internal actual fun vfsShimFileSetEvents(filePointer: Long, events: Int) {
    jni_vfsShimFileSetEvents(filePointer, events)
}
