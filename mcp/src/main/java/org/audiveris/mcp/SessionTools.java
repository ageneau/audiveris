//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                        S e s s i o n T o o l s                                //
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

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import org.audiveris.omr.score.Page;
import org.audiveris.omr.sheet.Book;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.SheetStub;
import org.audiveris.omr.sheet.Staff;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.step.OmrStep;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * MCP tools that manage open books and expose book/sheet summaries.
 */
public abstract class SessionTools
{
    private SessionTools ()
    {
    }

    public static List<SyncToolSpecification> specs (McpJsonMapper mapper,
                                                     OmrService service)
    {
        return List.of(
                openInput(mapper, service),
                openBook(mapper, service),
                closeBook(mapper, service),
                listBooks(mapper, service),
                getBook(mapper, service),
                getSheets(mapper, service),
                getSheet(mapper, service),
                saveBook(mapper, service),
                setSheetSelection(mapper, service),
                setSheetValidity(mapper, service));
    }

    private static SyncToolSpecification openInput (McpJsonMapper mapper,
                                                    OmrService service)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Schemas.string("Image or PDF input path."));
        properties.put("output_dir", Schemas.string(
                "Optional directory for the .omr book file."));
        properties.put("save_book", Schemas.bool(
                "Create a persistent .omr file (default: true)."));

        return spec(
                "open_input",
                "Open image or PDF",
                "Load an image or PDF and create an Audiveris book session.",
                Schemas.object(properties, "path"),
                annotations("Open image or PDF", false, false, true),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String path = requiredString(args, "path");
                        final String outputDir = optionalString(args, "output_dir");
                        final boolean saveBook = optionalBoolean(args, "save_book", true);
                        final OmrService.OpenResult result = service.openInput(
                                path,
                                outputDir,
                                saveBook);

                        final Map<String, Object> data = new LinkedHashMap<>();
                        data.put("book", bookSummary(result.session(), result.reused()));
                        data.put("reused", result.reused());
                        return ToolResults.success(mapper, data);
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification openBook (McpJsonMapper mapper,
                                                   OmrService service)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Schemas.string("Path to a .omr book file."));
        properties.put("auto_upgrade", Schemas.bool(
                "Schedule an upgrade after loading (default: false)."));

        return spec(
                "open_book",
                "Open .omr book",
                "Load an Audiveris .omr book file as a server session.",
                Schemas.object(properties, "path"),
                annotations("Open .omr book", false, false, true),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String path = requiredString(args, "path");
                        final boolean autoUpgrade = optionalBoolean(
                                args,
                                "auto_upgrade",
                                false);
                        final OmrService.OpenResult result = service.openBook(path);
                        final List<Map<String, Object>> warnings = new ArrayList<>();

                        if (autoUpgrade) {
                            warnings.add(Map.of(
                                    "code", "AUTO_UPGRADE_UNSUPPORTED",
                                    "message", "Automatic upgrade is not implemented yet."));
                        }

                        final Map<String, Object> data = new LinkedHashMap<>();
                        data.put("book", bookSummary(result.session(), result.reused()));
                        data.put("reused", result.reused());
                        return ToolResults.success(mapper, data, warnings);
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification closeBook (McpJsonMapper mapper,
                                                    OmrService service)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("book_id", Schemas.string("Book session identifier."));
        properties.put("save", Schemas.bool(
                "Save a persistent book before closing (default: true)."));

        return spec(
                "close_book",
                "Close book",
                "Close a book session, optionally saving it first.",
                Schemas.object(properties, "book_id"),
                annotations("Close book", false, true, false),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String bookId = requiredString(args, "book_id");
                        final boolean save = optionalBoolean(args, "save", true);
                        final BookSession session = service.get(bookId);
                        final boolean saved = save && session.isPersistent();
                        service.close(bookId, save);

                        final Map<String, Object> data = new LinkedHashMap<>();
                        data.put("book_id", bookId);
                        data.put("closed", true);
                        data.put("saved", saved);
                        return ToolResults.success(mapper, data);
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification listBooks (McpJsonMapper mapper,
                                                    OmrService service)
    {
        return spec(
                "list_books",
                "List open books",
                "List the book sessions currently open in the server.",
                Schemas.empty(),
                readOnly("List open books"),
                (exchange, request) -> {
                    try {
                        final List<Map<String, Object>> books = new ArrayList<>();
                        for (BookSession session : service.list()) {
                            books.add(bookSummary(session, false));
                        }
                        return ToolResults.success(mapper, Map.of("books", books));
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification getBook (McpJsonMapper mapper,
                                                  OmrService service)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("book_id", Schemas.string("Book session identifier."));

        return spec(
                "get_book",
                "Get book",
                "Report a book session summary.",
                Schemas.object(properties, "book_id"),
                readOnly("Get book"),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String bookId = requiredString(args, "book_id");
                        return ToolResults.success(
                                mapper,
                                bookSummary(service.get(bookId), false));
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification getSheets (McpJsonMapper mapper,
                                                    OmrService service)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("book_id", Schemas.string("Book session identifier."));

        return spec(
                "get_sheets",
                "Get sheets",
                "List the sheets of a book with their processing status.",
                Schemas.object(properties, "book_id"),
                readOnly("Get sheets"),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String bookId = requiredString(args, "book_id");
                        final BookSession session = service.get(bookId);
                        final List<Map<String, Object>> sheets = new ArrayList<>();

                        for (SheetStub stub : session.book.getStubs()) {
                            sheets.add(basicSheetSummary(stub));
                        }

                        return ToolResults.success(mapper, Map.of("sheets", sheets));
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification getSheet (McpJsonMapper mapper,
                                                   OmrService service)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("book_id", Schemas.string("Book session identifier."));
        properties.put("sheet", Schemas.integer("1-based sheet number."));
        properties.put("include_systems", Schemas.bool(
                "Include system and staff details (default: true)."));
        properties.put("include_staves", Schemas.bool(
                "Include staves inside each system (default: true)."));
        properties.put("include_symbols", Schemas.bool(
                "Include symbols (not implemented yet; default: false)."));

        return spec(
                "get_sheet",
                "Get sheet",
                "Report detailed information about one sheet.",
                Schemas.object(properties, "book_id", "sheet"),
                readOnly("Get sheet"),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        if (optionalBoolean(args, "include_symbols", false)) {
                            return ToolResults.error(
                                    mapper,
                                    "UNSUPPORTED",
                                    "include_symbols is not implemented yet");
                        }

                        final String bookId = requiredString(args, "book_id");
                        final int sheetNumber = requiredInteger(args, "sheet");
                        final boolean includeSystems = optionalBoolean(
                                args,
                                "include_systems",
                                true);
                        final boolean includeStaves = optionalBoolean(
                                args,
                                "include_staves",
                                true);
                        final BookSession session = service.get(bookId);
                        session.lock.lock();

                        try {
                            final SheetStub stub = session.book.getStub(sheetNumber);

                            if (stub == null) {
                                throw new NoSuchElementException(
                                        "No sheet " + sheetNumber + " in book " + bookId);
                            }

                            return ToolResults.success(
                                    mapper,
                                    detailedSheetSummary(stub, includeSystems, includeStaves));
                        } finally {
                            session.lock.unlock();
                        }
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification saveBook (McpJsonMapper mapper,
                                                   OmrService service)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("book_id", Schemas.string("Book session identifier."));
        properties.put("path", Schemas.string("Optional target .omr path."));

        return spec(
                "save_book",
                "Save book",
                "Store the current book state as an .omr file.",
                Schemas.object(properties, "book_id"),
                annotations("Save book", false, false, true),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String bookId = requiredString(args, "book_id");
                        final String path = optionalString(args, "path");
                        final BookSession session = service.get(bookId);
                        final var target = service.save(session, path);

                        final Map<String, Object> data = new LinkedHashMap<>();
                        data.put("book_id", bookId);
                        data.put("book_path", target.toString());
                        data.put("saved", true);
                        return ToolResults.success(mapper, data);
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification setSheetSelection (McpJsonMapper mapper,
                                                            OmrService service)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("book_id", Schemas.string("Book session identifier."));
        properties.put("sheets", Schemas.string(
                "Sheet selection, for example \"1,3-5\"."));

        return spec(
                "set_sheet_selection",
                "Set sheet selection",
                "Remember a sheet selection used by later processing tools.",
                Schemas.object(properties, "book_id", "sheets"),
                annotations("Set sheet selection", false, false, true),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String bookId = requiredString(args, "book_id");
                        final String sheets = sheetSpec(args.get("sheets"));
                        final BookSession session = service.get(bookId);
                        session.lock.lock();

                        try {
                            session.book.setSheetsSelection(sheets);
                        } finally {
                            session.lock.unlock();
                        }

                        final Map<String, Object> data = new LinkedHashMap<>();
                        data.put("book_id", bookId);
                        data.put("selection", session.book.getSheetsSelection());
                        return ToolResults.success(mapper, data);
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification setSheetValidity (McpJsonMapper mapper,
                                                           OmrService service)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("book_id", Schemas.string("Book session identifier."));
        properties.put("sheet", Schemas.integer("1-based sheet number."));
        properties.put("valid", Schemas.bool("New validity flag."));

        return spec(
                "set_sheet_validity",
                "Set sheet validity",
                "Mark a sheet as valid or invalid.",
                Schemas.object(properties, "book_id", "sheet", "valid"),
                annotations("Set sheet validity", false, true, false),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String bookId = requiredString(args, "book_id");
                        final int sheetNumber = requiredInteger(args, "sheet");
                        final boolean valid = requiredBoolean(args, "valid");
                        final BookSession session = service.get(bookId);
                        final SheetStub stub = session.book.getStub(sheetNumber);

                        if (stub == null) {
                            throw new NoSuchElementException(
                                    "No sheet " + sheetNumber + " in book " + bookId);
                        }

                        session.lock.lock();

                        try {
                            if (valid) {
                                if (stub.isDone(OmrStep.BINARY)) {
                                    stub.validate();
                                } else {
                                    stub.reset();
                                }
                            } else {
                                stub.invalidate();
                            }
                        } finally {
                            session.lock.unlock();
                        }

                        final List<Map<String, Object>> warnings = new ArrayList<>();

                        if (valid && !stub.isValid()) {
                            warnings.add(Map.of(
                                    "code", "VALIDATION_FAILED",
                                    "message", "The sheet could not be marked valid."));
                        }

                        return ToolResults.success(
                                mapper,
                                basicSheetSummary(stub),
                                warnings);
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static Map<String, Object> bookSummary (BookSession session,
                                                    boolean reused)
    {
        final Book book = session.book;
        long validSheets = 0;

        for (SheetStub stub : book.getStubs()) {
            if (stub.isValid()) {
                validSheets++;
            }
        }

        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("book_id", session.id);
        data.put("radix", book.getRadix());
        data.put("input_path", book.getInputPath() == null
                ? null
                : book.getInputPath().toString());
        data.put("book_path", session.getBookPath() == null
                ? null
                : session.getBookPath().toString());
        data.put("version", book.getVersionValue());
        data.put("sheet_count", book.getStubs().size());
        data.put("valid_sheet_count", validSheets);
        data.put("score_count", book.getScores().size());
        data.put("modified", book.isModified());
        data.put("dirty", book.isDirty());
        data.put("multi_sheet", book.isMultiSheet());
        data.put("multi_movement", book.isMultiMovement());
        data.put("reused", reused);
        data.put("created_at", session.createdAt.toString());
        data.put("last_accessed_at", session.getLastAccessedAt().toString());
        return data;
    }

    private static Map<String, Object> basicSheetSummary (SheetStub stub)
    {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("sheet", stub.getNumber());
        data.put("valid", stub.isValid());
        data.put("modified", stub.isModified());
        data.put("latest_step", stub.getLatestStep() == null
                ? null
                : stub.getLatestStep().name());
        data.put("steps_done", stepsDone(stub));

        final SheetStub.SheetInput input = stub.getSheetInput();
        data.put("input_path", input.path == null ? null : input.path.toString());
        return data;
    }

    private static Map<String, Object> detailedSheetSummary (SheetStub stub,
                                                             boolean includeSystems,
                                                             boolean includeStaves)
    {
        final Map<String, Object> data = basicSheetSummary(stub);
        data.put("current_step", stub.getCurrentStep() == null
                ? null
                : stub.getCurrentStep().name());

        // 'steps_done' and 'latest_step' are already populated by basicSheetSummary.
        data.put("steps_done", stepsDone(stub));

        final Sheet sheet = stub.getSheet();

        if (sheet == null) {
            data.put("width", null);
            data.put("height", null);
            data.put("interline", null);
            data.put("pages", List.of());
            if (includeSystems) {
                data.put("systems", List.of());
            }
            return data;
        }

        Integer width = null;
        Integer height = null;
        Integer interline = null;

        if (stub.isDone(OmrStep.LOAD)) {
            try {
                final var picture = sheet.getPicture();

                if (picture != null) {
                    width = picture.getWidth();
                    height = picture.getHeight();
                }
            } catch (Exception ignored) {
                // Dimensions remain unavailable.
            }
        }

        if (stub.isDone(OmrStep.SCALE)) {
            try {
                interline = sheet.getInterline();
            } catch (Exception ignored) {
                // Interline remains unavailable.
            }
        }

        data.put("width", width);
        data.put("height", height);
        data.put("interline", interline);

        final List<Map<String, Object>> pages = new ArrayList<>();
        for (Page page : sheet.getPages()) {
            final Map<String, Object> pageData = new LinkedHashMap<>();
            pageData.put("page_id", page.getId());
            pageData.put("movement_start", page.isMovementStart());
            pageData.put("measure_count", measureCount(page));
            pages.add(pageData);
        }
        data.put("pages", pages);

        if (includeSystems) {
            final List<Map<String, Object>> systems = new ArrayList<>();

            for (SystemInfo system : sheet.getSystems()) {
                final Map<String, Object> systemData = new LinkedHashMap<>();
                systemData.put("system_id", system.getId());
                systemData.put(
                        "page_id",
                        system.getPage() == null ? null : system.getPage().getId());
                systemData.put("bounds", rectangle(system.getBounds()));
                systemData.put(
                        "measure_count",
                        system.getStacks() == null ? 0 : system.getStacks().size());

                final List<Integer> staffIds = new ArrayList<>();
                final List<Map<String, Object>> staves = new ArrayList<>();

                for (Staff staff : system.getStaves()) {
                    staffIds.add(staff.getId());

                    if (includeStaves) {
                        final Map<String, Object> staffData = new LinkedHashMap<>();
                        staffData.put("staff_id", staff.getId());
                        staffData.put("part_id", staff.getPart() == null
                                ? null
                                : staff.getPart().getId());
                        staffData.put("interline", staff.getSpecificInterline());

                        final var bounds = staff.getAreaBounds();
                        staffData.put("bounds", bounds == null
                                ? null
                                : List.of(
                                        bounds.getX(),
                                        bounds.getY(),
                                        bounds.getWidth(),
                                        bounds.getHeight()));
                        staves.add(staffData);
                    }
                }

                systemData.put("staff_ids", staffIds);

                if (includeStaves) {
                    systemData.put("staves", staves);
                }

                systems.add(systemData);
            }

            data.put("systems", systems);
        }

        return data;
    }

    private static Integer measureCount (Page page)
    {
        try {
            return page.getMeasureCount();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static List<String> stepsDone (SheetStub stub)
    {
        final List<String> steps = new ArrayList<>();

        for (OmrStep step : OmrStep.values()) {
            if (stub.isDone(step)) {
                steps.add(step.name());
            }
        }

        return steps;
    }

    private static List<Number> rectangle (java.awt.Rectangle rectangle)
    {
        return List.of(rectangle.x, rectangle.y, rectangle.width, rectangle.height);
    }

    private static String sheetSpec (Object value)
    {
        if (value instanceof String string) {
            return string;
        }

        if (value instanceof Number number) {
            return String.valueOf(number.intValue());
        }

        if (value instanceof List<?> list) {
            final StringBuilder sb = new StringBuilder();

            for (Object item : list) {
                if (!(item instanceof Number number)) {
                    throw new IllegalArgumentException("'sheets' must contain integers");
                }

                if (sb.length() > 0) {
                    sb.append(',');
                }

                sb.append(number.intValue());
            }

            return sb.toString();
        }

        throw new IllegalArgumentException("'sheets' must be a string, integer or integer array");
    }

    private static SyncToolSpecification spec (String name,
                                               String title,
                                               String description,
                                               Map<String, Object> inputSchema,
                                               ToolAnnotations annotations,
                                               java.util.function.BiFunction<
                                                       McpSyncServerExchange,
                                                       CallToolRequest,
                                                       CallToolResult> handler)
    {
        final Tool tool = Tool.builder(name, inputSchema)
                .title(title)
                .description(description)
                .annotations(annotations)
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(handler)
                .build();
    }

    private static ToolAnnotations readOnly (String title)
    {
        return annotations(title, true, false, true);
    }

    private static ToolAnnotations annotations (String title,
                                                boolean readOnly,
                                                boolean destructive,
                                                boolean idempotent)
    {
        return ToolAnnotations.builder()
                .title(title)
                .readOnlyHint(readOnly)
                .destructiveHint(destructive)
                .idempotentHint(idempotent)
                .openWorldHint(false)
                .build();
    }

    private static Map<String, Object> arguments (CallToolRequest request)
    {
        return request.arguments() != null ? request.arguments() : Map.of();
    }

    private static String requiredString (Map<String, Object> args,
                                          String key)
    {
        final String value = optionalString(args, key);

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("'" + key + "' is required");
        }

        return value;
    }

    private static String optionalString (Map<String, Object> args,
                                          String key)
    {
        final Object value = args.get(key);

        if (value == null) {
            return null;
        }

        if (!(value instanceof String string)) {
            throw new IllegalArgumentException("'" + key + "' must be a string");
        }

        return string;
    }

    private static int requiredInteger (Map<String, Object> args,
                                        String key)
    {
        final Object value = args.get(key);

        if (value == null) {
            throw new IllegalArgumentException("'" + key + "' is required");
        }

        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("'" + key + "' must be an integer");
        }

        return number.intValue();
    }

    private static boolean requiredBoolean (Map<String, Object> args,
                                            String key)
    {
        final Object value = args.get(key);

        if (value == null) {
            throw new IllegalArgumentException("'" + key + "' is required");
        }

        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException("'" + key + "' must be a boolean");
        }

        return bool;
    }

    private static boolean optionalBoolean (Map<String, Object> args,
                                            String key,
                                            boolean defaultValue)
    {
        final Object value = args.get(key);

        if (value == null) {
            return defaultValue;
        }

        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException("'" + key + "' must be a boolean");
        }

        return bool;
    }

    private static CallToolResult error (McpJsonMapper mapper,
                                         Throwable ex)
    {
        if (ex instanceof NoSuchElementException) {
            return ToolResults.error(mapper, "NOT_FOUND", ex.getMessage());
        }

        if (ex instanceof IllegalArgumentException) {
            return ToolResults.error(mapper, "INVALID_ARGUMENT", ex.getMessage());
        }

        if (ex instanceof IOException) {
            return ToolResults.error(mapper, "IO_ERROR", ex.getMessage());
        }

        return ToolResults.error(mapper, "INTERNAL", ex.toString());
    }
}
