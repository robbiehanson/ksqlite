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
package ksqlite.types.vfs

/**
 * The file locking levels a VFS moves files between, via the xLock and xUnlock methods of
 * sqlite3_io_methods.
 *
 * [File Locking Levels](https://sqlite.org/c3ref/c_lock_exclusive.html)
 */
public enum class SqliteLockLevel(public val value: Int) {

    /**
     * No locks are held on the file.
     */
    NONE(0),

    /**
     * The file may be read, but not written. Any number of processes can hold SHARED locks at the
     * same time.
     */
    SHARED(1),

    /**
     * A process intends to write the file in the future, but is currently only reading it.
     */
    RESERVED(2),

    /**
     * A process wants to write the file as soon as possible, and is waiting for all current
     * SHARED locks to clear.
     */
    PENDING(3),

    /**
     * A process is writing the file. No other lock may coexist with it.
     */
    EXCLUSIVE(4);

    public companion object {

        /**
         * Returns the [SqliteLockLevel] for [value], or `null` if [value] isn't a known level.
         */
        public fun from(value: Int): SqliteLockLevel? = entries.firstOrNull { it.value == value }
    }
}
