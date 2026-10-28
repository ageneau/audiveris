//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                         O m r S e r v i c e                                   //
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

import org.audiveris.omr.OMR;
import org.audiveris.omr.sheet.Book;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the books opened by the MCP server.
 */
public class OmrService
{
    private static final Logger logger = LoggerFactory.getLogger(OmrService.class);

    private final Path workspace;

    private final Path booksDirectory;

    private final Map<String, BookSession> sessions = new ConcurrentHashMap<>();

    private final Map<String, String> sessionsByPath = new ConcurrentHashMap<>();

    public OmrService (Path workspace)
    {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.booksDirectory = this.workspace.resolve("books");
    }

    public Path getWorkspace ()
    {
        return workspace;
    }

    public synchronized OpenResult openInput (String inputValue,
                                              String outputDirValue,
                                              boolean saveBook)
        throws IOException
    {
        final Path input = resolve(inputValue);

        if (!Files.isRegularFile(input)) {
            throw new IOException("Input file not found: " + input);
        }

        final String key = canonicalKey(input);
        final BookSession existing = sessionByPath(key);

        if (existing != null) {
            existing.touch();
            return new OpenResult(existing, true);
        }

        final Book book = OMR.engine.loadInput(input);

        if (book == null) {
            throw new IOException("Could not load input: " + input);
        }

        if (book.getStubs().isEmpty()) {
            book.createStubs();
        }

        if (book.getStubs().isEmpty()) {
            throw new IOException("No sheet found in input: " + input);
        }

        final Path temporaryDirectory;
        final Path bookPath;

        if (saveBook) {
            final Path directory = (outputDirValue != null) ? resolve(outputDirValue)
                    : booksDirectory;
            Files.createDirectories(directory);
            bookPath = directory.resolve(book.getRadix() + ".omr");
            temporaryDirectory = null;
        } else {
            temporaryDirectory = Files.createTempDirectory(workspace, "audiveris-mcp-");
            bookPath = temporaryDirectory.resolve(book.getRadix() + ".omr");
        }

        book.store(bookPath, false);

        if (!Files.exists(bookPath)) {
            throw new IOException("Could not create book file: " + bookPath);
        }

        final BookSession session = new BookSession(
                UUID.randomUUID().toString(),
                book,
                input,
                bookPath,
                temporaryDirectory,
                saveBook);

        sessions.put(session.id, session);
        sessionsByPath.put(key, session.id);

        logger.info("Opened input {} as book {}", input, session.id);

        return new OpenResult(session, false);
    }

    public synchronized OpenResult openBook (String pathValue)
        throws IOException
    {
        final Path path = resolve(pathValue);

        if (!Files.isRegularFile(path)) {
            throw new IOException("Book file not found: " + path);
        }

        final String key = canonicalKey(path);
        final BookSession existing = sessionByPath(key);

        if (existing != null) {
            existing.touch();
            return new OpenResult(existing, true);
        }

        final Book book = OMR.engine.loadBook(path);

        if (book == null) {
            throw new IOException("Could not load book: " + path);
        }

        final BookSession session = new BookSession(
                UUID.randomUUID().toString(),
                book,
                book.getInputPath(),
                path,
                null,
                true);

        sessions.put(session.id, session);
        sessionsByPath.put(key, session.id);

        logger.info("Opened book {}", session.id);

        return new OpenResult(session, false);
    }

    public List<BookSession> list ()
    {
        final List<BookSession> list = new ArrayList<>(sessions.values());
        list.sort(Comparator.comparing(BookSession::getLastAccessedAt).reversed());
        return list;
    }

    public BookSession get (String id)
    {
        final BookSession session = sessions.get(id);

        if (session == null) {
            throw new NoSuchElementException("Unknown book_id: " + id);
        }

        session.touch();
        return session;
    }

    public void close (String id,
                       boolean save)
        throws IOException
    {
        final BookSession session = get(id);

        session.lock.lock();

        try {
            if (save && session.isPersistent()) {
                session.book.store(session.getBookPath(), false);
            }

            session.book.close(null);
            sessions.remove(id);
            sessionsByPath.entrySet().removeIf(entry -> entry.getValue().equals(id));

            if (session.temporaryDirectory != null) {
                deleteTree(session.temporaryDirectory);
            }

            logger.info("Closed book {}", id);
        } finally {
            session.lock.unlock();
        }
    }

    public Path save (BookSession session,
                      String pathValue)
        throws IOException
    {
        session.lock.lock();

        try {
            final Path target;

            if (pathValue != null) {
                target = resolve(pathValue);
            } else if (session.isPersistent() && session.getBookPath() != null) {
                target = session.getBookPath();
            } else {
                Files.createDirectories(booksDirectory);
                target = booksDirectory.resolve(session.book.getRadix() + ".omr");
            }

            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }

            session.book.store(target, false);

            if (session.book.getBookPath() == null) {
                throw new IOException("Could not save book to: " + target);
            }

            session.setBookPath(target, true);
            return target;
        } finally {
            session.lock.unlock();
        }
    }

    public synchronized void closeAll ()
    {
        for (BookSession session : new ArrayList<>(sessions.values())) {
            try {
                session.book.close(null);

                if (session.temporaryDirectory != null) {
                    deleteTree(session.temporaryDirectory);
                }
            } catch (Exception ex) {
                logger.warn("Could not close book {}: {}", session.id, ex.toString());
            }
        }

        sessions.clear();
        sessionsByPath.clear();
    }

    private BookSession sessionByPath (String key)
    {
        final String id = sessionsByPath.get(key);
        return id == null ? null : sessions.get(id);
    }

    private Path resolve (String value)
    {
        final Path path = Path.of(value);

        if (path.isAbsolute()) {
            return path.normalize();
        }

        return workspace.resolve(path).normalize();
    }

    private static String canonicalKey (Path path)
        throws IOException
    {
        final Path absolute = path.toAbsolutePath().normalize();

        try {
            return absolute.toRealPath().toString();
        } catch (IOException ex) {
            return absolute.toString();
        }
    }

    private static void deleteTree (Path root)
    {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort cleanup.
                }
            });
        } catch (IOException ignored) {
            // Best effort cleanup.
        }
    }

    public record OpenResult(BookSession session, boolean reused)
    {
    }
}
