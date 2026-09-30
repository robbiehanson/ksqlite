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
 * The kind of sync a VFS's xSync method is asked to perform: the flags passed to xSync, other
 * than SQLITE_SYNC_DATAONLY.
 *
 * [Synchronization Type Flags](https://sqlite.org/c3ref/c_sync_dataonly.html)
 */
public enum class SqliteSyncType(public val value: Int) {

    /**
     * A normal fsync().
     */
    NORMAL(0x00002),

    /**
     * A full sync, e.g. Mac OS X's fcntl(F_FULLFSYNC).
     */
    FULL(0x00003);

    public companion object {

        /**
         * The SQLITE_SYNC_DATAONLY flag, ORed into xSync's flags when only the file's data (and
         * not its metadata, such as its size) needs to be flushed.
         */
        public const val DATA_ONLY_FLAG: Int = 0x00010

        /**
         * Returns the [SqliteSyncType] within xSync [flags], or `null` if none is recognized.
         */
        public fun from(flags: Int): SqliteSyncType? =
            entries.firstOrNull { it.value == (flags and DATA_ONLY_FLAG.inv()) }
    }
}
