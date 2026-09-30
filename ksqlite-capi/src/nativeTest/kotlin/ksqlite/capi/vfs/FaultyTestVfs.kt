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
@file:OptIn(ExperimentalForeignApi::class)

package ksqlite.capi.vfs

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.free
import kotlinx.cinterop.invoke
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import platform.posix.memcpy
import ksqlite.foreign.sqlite3_file as s3_file
import ksqlite.foreign.sqlite3_io_methods as s3_io_methods
import ksqlite.foreign.sqlite3_vfs as s3_vfs
import ksqlite.foreign.sqlite3_vfs_find as native_sqlite3_vfs_find
import ksqlite.foreign.sqlite3_vfs_register as native_sqlite3_vfs_register
import ksqlite.foreign.sqlite3_vfs_unregister as native_sqlite3_vfs_unregister

private const val SQLITE_OPEN_MAIN_DB = 0x00000100
private const val SQLITE_CANTOPEN = 14
private const val SQLITE_IOERR_CLOSE = 10 or (16 shl 8)

/**
 * A test-only VFS wrapping the default VFS, injecting the [Fault] currently selected by [fault]
 * into its main-database files. Used to exercise the VFS shim's failure paths, which the default
 * VFS never takes on its own.
 */
internal object FaultyTestVfs {

    const val NAME = "ksqlite_faulty_test_vfs"

    enum class Fault {
        NONE,

        /** xClose really closes the file, but then reports SQLITE_IOERR_CLOSE. */
        FAIL_CLOSE,

        /**
         * xOpen really opens the file (leaving pMethods set, as the xOpen contract permits), but
         * then reports SQLITE_CANTOPEN.
         */
        FAIL_OPEN_AFTER_REAL_OPEN,
    }

    var fault: Fault = Fault.NONE

    private var realVfs: CPointer<s3_vfs>? = null
    private var vfs: s3_vfs? = null
    private var name: CPointer<ByteVar>? = null

    // FAIL_CLOSE swaps a main-database file's methods for this copy, differing only in xClose.
    private var realMethods: CPointer<s3_io_methods>? = null
    private var faultyMethods: s3_io_methods? = null

    private val FaultyClose = staticCFunction { file: CPointer<s3_file>? ->
        val _ = realMethods!!.pointed.xClose!!.invoke(file)
        SQLITE_IOERR_CLOSE
    }

    private val FaultyOpen = staticCFunction {
            _: CPointer<s3_vfs>?,
            zName: CPointer<ByteVar>?,
            file: CPointer<s3_file>?,
            flags: Int,
            pOutFlags: CPointer<IntVar>? ->
        val real = realVfs!!
        val result = real.pointed.xOpen!!.invoke(real, zName, file, flags, pOutFlags)

        if (result != 0 || (flags and SQLITE_OPEN_MAIN_DB) == 0) {
            result
        } else when (fault) {
            Fault.NONE -> result

            Fault.FAIL_CLOSE -> {
                val methods = file!!.pointed.pMethods!!
                val copy = faultyMethods ?: nativeHeap.alloc<s3_io_methods>().also { faultyMethods = it }
                memcpy(copy.ptr, methods, sizeOf<s3_io_methods>().convert())
                copy.xClose = FaultyClose
                realMethods = methods
                file.pointed.pMethods = copy.ptr
                result
            }

            Fault.FAIL_OPEN_AFTER_REAL_OPEN -> SQLITE_CANTOPEN
        }
    }

    fun register() {
        val real = native_sqlite3_vfs_find(null)!!
        val copy = nativeHeap.alloc<s3_vfs>()
        memcpy(copy.ptr, real, sizeOf<s3_vfs>().convert())

        val nameBytes = NAME.encodeToByteArray()
        val namePointer = nativeHeap.allocArray<ByteVar>(nameBytes.size + 1)
        nameBytes.forEachIndexed { i, byte -> namePointer[i] = byte }
        namePointer[nameBytes.size] = 0

        copy.zName = namePointer
        copy.pNext = null
        copy.xOpen = FaultyOpen

        realVfs = real
        vfs = copy
        name = namePointer
        check(native_sqlite3_vfs_register(copy.ptr, 0) == 0)
    }

    fun unregister() {
        fault = Fault.NONE
        vfs?.let {
            native_sqlite3_vfs_unregister(it.ptr)
            nativeHeap.free(it)
        }
        faultyMethods?.let { nativeHeap.free(it) }
        name?.let { nativeHeap.free(it) }
        vfs = null
        name = null
        faultyMethods = null
        realMethods = null
        realVfs = null
    }
}
