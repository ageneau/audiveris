//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                        B o o k S e s s i o n                                   //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Audiveris 2026. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package org.audiveris.mcp;

import org.audiveris.omr.sheet.Book;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One open Audiveris book and its server-side metadata.
 */
public class BookSession
{
    public final String id;

    public final Book book;

    public final Path inputPath;

    public final Path temporaryDirectory;

    public final Instant createdAt = Instant.now();

    public final ReentrantLock lock = new ReentrantLock();

    private final Map<Integer, EditHistory> editHistories = new ConcurrentHashMap<>();

    private volatile Path bookPath;

    private volatile boolean persistent;

    private volatile Instant lastAccessedAt = Instant.now();

    BookSession (String id,
                 Book book,
                 Path inputPath,
                 Path bookPath,
                 Path temporaryDirectory,
                 boolean persistent)
    {
        this.id = id;
        this.book = book;
        this.inputPath = inputPath;
        this.bookPath = bookPath;
        this.temporaryDirectory = temporaryDirectory;
        this.persistent = persistent;
    }

    public Path getBookPath ()
    {
        return bookPath;
    }

    void setBookPath (Path bookPath,
                      boolean persistent)
    {
        this.bookPath = bookPath;
        this.persistent = persistent;
    }

    public boolean isPersistent ()
    {
        return persistent;
    }

    public Instant getLastAccessedAt ()
    {
        return lastAccessedAt;
    }

    public void touch ()
    {
        lastAccessedAt = Instant.now();
    }

    public EditHistory getEditHistory (int sheet)
    {
        return editHistories.computeIfAbsent(sheet, key -> new EditHistory());
    }
}
