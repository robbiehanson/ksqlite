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
package ksqlite.foreign.callbacks

/**
 * Receives a VFS shim's events (see `ksqlite_vfs_shim_register` in ksqlite.h), all invoked from
 * JNI. None of them may throw.
 */
public interface VfsShimCallbacks {

    /**
     * The OPEN event, first half: returns the object representing the file just opened, which the
     * shim holds until the file closes and passes to [onEvent]/[onClose] - starting with an
     * [onEvent] call for the OPEN event itself, once the file can be looked up. Returning `null`
     * leaves the file untracked.
     *
     * [filePointer] identifies the file for `vfsShimFileSetEvents`, and is valid until [onClose].
     */
    public fun onOpen(filePointer: Long, filename: String?, flags: Int): Any?

    /**
     * Any event but CLOSE, with `ksqlite_xVfsShimEvent`'s arguments. [file] is `null` for VFS
     * events (DELETE, ACCESS, FULL_PATHNAME), and for files [onOpen] returned `null` for.
     */
    public fun onEvent(
        file: Any?,
        event: Int,
        a: Long,
        b: Long,
        c: Long,
        z1: String?,
        z2: String?,
        result: Int,
    )

    /**
     * The CLOSE event, after which [file]'s `filePointer` is no longer valid. [isEnabled] is whether
     * the file had CLOSE enabled.
     */
    public fun onClose(file: Any, isEnabled: Boolean, result: Int)
}
