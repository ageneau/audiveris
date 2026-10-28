//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                         E d i t T o o l s                                     //
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

import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.sig.relation.Relation;
import org.audiveris.omr.sig.relation.Support;

import java.awt.Point;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP tools for headless symbol and relation editing.
 */
public abstract class EditTools
{
    private static final Pattern RELATION_ID = Pattern.compile(
            "^r:(\\d+):(\\d+):([^:]+):(\\d+)(?:#\\d+)?$");

    private EditTools ()
    {
    }

    public static List<SyncToolSpecification> specs (McpJsonMapper mapper,
                                                     OmrService service)
    {
        return List.of(
                addSymbol(mapper, service),
                removeSymbols(mapper, service),
                moveSymbol(mapper, service),
                linkSymbols(mapper, service),
                unlinkSymbols(mapper, service),
                undoEdit(mapper, service),
                redoEdit(mapper, service),
                getEditHistory(mapper, service));
    }

    private static SyncToolSpecification addSymbol (McpJsonMapper mapper,
                                                    OmrService service)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("book_id", Schemas.string("Book session identifier."));
        properties.put("sheet", Schemas.integer("1-based sheet number."));
        properties.put("shape", Schemas.string("Shape enum name."));
        properties.put("x", Schemas.integer("Sheet abscissa."));
        properties.put("y", Schemas.integer("Sheet ordinate."));
        properties.put("staff_id", Schemas.integer("Optional staff ID."));
        properties.put("link", Schemas.bool(
                "Include automatic links from preAdd (default: true)."));

        return spec(
                "add_symbol",
                "Add symbol",
                "Add a manual symbol at a sheet location.",
                Schemas.object(properties, "book_id", "sheet", "shape", "x", "y"),
                annotations("Add symbol", false, false, false),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String bookId = requiredString(args, "book_id");
                        final int sheetNumber = requiredInteger(args, "sheet");
                        final Shape shape = Shape.valueOf(requiredString(args, "shape"));
                        final int x = requiredInteger(args, "x");
                        final int y = requiredInteger(args, "y");
                        final Integer staffId = optionalInteger(args, "staff_id");
                        final boolean link = optionalBoolean(args, "link", true);
                        final BookSession session = service.get(bookId);
                        session.lock.lock();

                        try {
                            final OmrEditService edit = new OmrEditService(session, sheetNumber);
                            final Inter inter = edit.addSymbol(
                                    shape,
                                    new Point(x, y),
                                    staffId,
                                    link);
                            final Map<String, Object> symbol = InspectionTools.symbolSummary(
                                    inter,
                                    true,
                                    true,
                                    false);
                            final Map<String, Object> data = new LinkedHashMap<>();
                            data.put("symbol", symbol);
                            data.put("links", symbol.get("relations"));
                            return ToolResults.success(mapper, data);
                        } finally {
                            session.lock.unlock();
                        }
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification removeSymbols (McpJsonMapper mapper,
                                                        OmrService service)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("book_id", Schemas.string("Book session identifier."));
        properties.put("sheet", Schemas.integer("1-based sheet number."));
        properties.put("inter_ids", Schemas.array(
                Schemas.integer("Inter ID"),
                "Inters to remove."));
        properties.put("rebuild_glyphs", Schemas.bool(
                "Rebuild pixels after removal (not implemented; default: false)."));

        return spec(
                "remove_symbols",
                "Remove symbols",
                "Remove inters and their relations.",
                Schemas.object(properties, "book_id", "sheet", "inter_ids"),
                annotations("Remove symbols", false, true, false),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String bookId = requiredString(args, "book_id");
                        final int sheetNumber = requiredInteger(args, "sheet");
                        final List<Integer> interIds = intList(args.get("inter_ids"), "inter_ids");
                        final boolean rebuildGlyphs = optionalBoolean(
                                args,
                                "rebuild_glyphs",
                                false);
                        final BookSession session = service.get(bookId);
                        session.lock.lock();

                        try {
                            final OmrEditService edit = new OmrEditService(session, sheetNumber);
                            final List<Inter> found = new ArrayList<>();
                            final List<Integer> notFound = new ArrayList<>();

                            for (Integer id : interIds) {
                                try {
                                    found.add(edit.findInter(id));
                                } catch (NoSuchElementException ex) {
                                    notFound.add(id);
                                }
                            }

                            final List<Map<String, Object>> warnings = new ArrayList<>();
                            final List<Integer> removed = new ArrayList<>();

                            if (!found.isEmpty()) {
                                final OmrEditService.RemovalResult result = edit.removeSymbols(
                                        found,
                                        rebuildGlyphs);

                                for (Inter inter : result.removed()) {
                                    removed.add(inter.getId());
                                }

                                if (result.glyphLeft()) {
                                    warnings.add(Map.of(
                                            "code", "GLYPH_LEFT_AS_FREE",
                                            "message", "Removed symbols leave free glyphs."));
                                }
                            }

                            final Map<String, Object> data = new LinkedHashMap<>();
                            data.put("removed", removed);
                            data.put("not_found", notFound);
                            return ToolResults.success(mapper, data, warnings);
                        } finally {
                            session.lock.unlock();
                        }
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification moveSymbol (McpJsonMapper mapper,
                                                     OmrService service)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("book_id", Schemas.string("Book session identifier."));
        properties.put("sheet", Schemas.integer("1-based sheet number."));
        properties.put("inter_id", Schemas.integer("Inter ID."));
        properties.put("x", Schemas.integer("New sheet abscissa."));
        properties.put("y", Schemas.integer("New sheet ordinate."));
        properties.put("snap", Schemas.bool("Apply shape snapping (default: true)."));
        properties.put("relink", Schemas.bool("Recompute links (default: true)."));

        return spec(
                "move_symbol",
                "Move symbol",
                "Move one inter to a new sheet location.",
                Schemas.object(properties, "book_id", "sheet", "inter_id", "x", "y"),
                annotations("Move symbol", false, true, false),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String bookId = requiredString(args, "book_id");
                        final int sheetNumber = requiredInteger(args, "sheet");
                        final int interId = requiredInteger(args, "inter_id");
                        final int x = requiredInteger(args, "x");
                        final int y = requiredInteger(args, "y");
                        final boolean snap = optionalBoolean(args, "snap", true);
                        final boolean relink = optionalBoolean(args, "relink", true);
                        final BookSession session = service.get(bookId);
                        session.lock.lock();

                        try {
                            final OmrEditService edit = new OmrEditService(session, sheetNumber);
                            final Inter inter = edit.findInter(interId);
                            edit.moveSymbol(inter, new Point(x, y), snap, relink);
                            return ToolResults.success(
                                    mapper,
                                    Map.of("symbol", InspectionTools.symbolSummary(
                                            inter,
                                            true,
                                            true,
                                            false)));
                        } finally {
                            session.lock.unlock();
                        }
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification linkSymbols (McpJsonMapper mapper,
                                                      OmrService service)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("book_id", Schemas.string("Book session identifier."));
        properties.put("sheet", Schemas.integer("1-based sheet number."));
        properties.put("source_id", Schemas.integer("Source inter ID."));
        properties.put("target_id", Schemas.integer("Target inter ID."));
        properties.put("relation_type", Schemas.string(
                "Relation simple name or fully-qualified class name."));

        return spec(
                "link_symbols",
                "Link symbols",
                "Create a relation between two inters in the same SIG.",
                Schemas.object(
                        properties,
                        "book_id",
                        "sheet",
                        "source_id",
                        "target_id",
                        "relation_type"),
                annotations("Link symbols", false, true, false),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String bookId = requiredString(args, "book_id");
                        final int sheetNumber = requiredInteger(args, "sheet");
                        final int sourceId = requiredInteger(args, "source_id");
                        final int targetId = requiredInteger(args, "target_id");
                        final String relationType = requiredString(args, "relation_type");
                        final BookSession session = service.get(bookId);
                        session.lock.lock();

                        try {
                            final OmrEditService edit = new OmrEditService(session, sheetNumber);
                            final Inter source = edit.findInter(sourceId);
                            final Inter target = edit.findInter(targetId);
                            final Relation relation = edit.linkSymbols(
                                    source,
                                    target,
                                    relationType);
                            return ToolResults.success(
                                    mapper,
                                    Map.of("relation", relationSummary(
                                            relation,
                                            source,
                                            target)));
                        } finally {
                            session.lock.unlock();
                        }
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification unlinkSymbols (McpJsonMapper mapper,
                                                        OmrService service)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("book_id", Schemas.string("Book session identifier."));
        properties.put("sheet", Schemas.integer("1-based sheet number."));
        properties.put("relation_id", Schemas.string("Optional relation handle."));
        properties.put("source_id", Schemas.integer("Optional source inter ID."));
        properties.put("target_id", Schemas.integer("Optional target inter ID."));
        properties.put("relation_type", Schemas.string(
                "Optional relation simple name or class name."));
        properties.put("all", Schemas.bool(
                "Remove all matching relations (default: false)."));

        return spec(
                "unlink_symbols",
                "Unlink symbols",
                "Remove one or more relations between two inters.",
                Schemas.object(properties, "book_id", "sheet"),
                annotations("Unlink symbols", false, true, false),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String bookId = requiredString(args, "book_id");
                        final int sheetNumber = requiredInteger(args, "sheet");
                        final boolean all = optionalBoolean(args, "all", false);
                        Integer sourceId = optionalInteger(args, "source_id");
                        Integer targetId = optionalInteger(args, "target_id");
                        String relationType = optionalString(args, "relation_type");
                        final String relationId = optionalString(args, "relation_id");

                        if (relationId != null) {
                            final Matcher matcher = RELATION_ID.matcher(relationId);

                            if (!matcher.matches()) {
                                throw new IllegalArgumentException(
                                        "Invalid relation_id: " + relationId);
                            }

                            sourceId = Integer.parseInt(matcher.group(2));
                            targetId = Integer.parseInt(matcher.group(4));
                            relationType = matcher.group(3);
                        }

                        if ((sourceId == null) || (targetId == null)
                                || (relationType == null)) {
                            throw new IllegalArgumentException(
                                    "Provide relation_id or source_id/target_id/relation_type");
                        }

                        final BookSession session = service.get(bookId);
                        session.lock.lock();

                        try {
                            final OmrEditService edit = new OmrEditService(session, sheetNumber);
                            final Inter source = edit.findInter(sourceId);
                            final Inter target = edit.findInter(targetId);
                            final List<Relation> removed = edit.unlinkSymbols(
                                    source,
                                    target,
                                    relationType,
                                    all);
                            final List<Map<String, Object>> summaries = new ArrayList<>();

                            for (Relation relation : removed) {
                                summaries.add(relationSummary(
                                        relation,
                                        source,
                                        target));
                            }

                            return ToolResults.success(
                                    mapper,
                                    Map.of("removed", summaries));
                        } finally {
                            session.lock.unlock();
                        }
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification undoEdit (McpJsonMapper mapper,
                                                   OmrService service)
    {
        return undoRedo(mapper, service, true);
    }

    private static SyncToolSpecification redoEdit (McpJsonMapper mapper,
                                                   OmrService service)
    {
        return undoRedo(mapper, service, false);
    }

    private static SyncToolSpecification undoRedo (McpJsonMapper mapper,
                                                   OmrService service,
                                                   boolean undo)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("book_id", Schemas.string("Book session identifier."));
        properties.put("sheet", Schemas.integer("1-based sheet number."));
        properties.put("count", Schemas.integer("Number of actions (default: 1)."));

        return spec(
                undo ? "undo_edit" : "redo_edit",
                undo ? "Undo edit" : "Redo edit",
                undo ? "Undo one or more edits." : "Redo one or more edits.",
                Schemas.object(properties, "book_id", "sheet"),
                annotations(undo ? "Undo edit" : "Redo edit", false, true, false),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String bookId = requiredString(args, "book_id");
                        final int sheetNumber = requiredInteger(args, "sheet");
                        final int count = clamp(optionalInteger(args, "count", 1), 1, 100);
                        final BookSession session = service.get(bookId);
                        session.lock.lock();

                        try {
                            final OmrEditService edit = new OmrEditService(session, sheetNumber);
                            final List<EditHistory.Entry> entries = undo
                                    ? edit.undo(count)
                                    : edit.redo(count);
                            final Map<String, Object> data = new LinkedHashMap<>();
                            data.put(undo ? "undone" : "redone", entries.size());
                            data.put("history", historySummary(edit.history()));
                            return ToolResults.success(mapper, data);
                        } finally {
                            session.lock.unlock();
                        }
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification getEditHistory (McpJsonMapper mapper,
                                                         OmrService service)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("book_id", Schemas.string("Book session identifier."));
        properties.put("sheet", Schemas.integer("1-based sheet number."));

        return spec(
                "get_edit_history",
                "Get edit history",
                "Report the undo/redo history for one sheet.",
                Schemas.object(properties, "book_id", "sheet"),
                readOnly("Get edit history"),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String bookId = requiredString(args, "book_id");
                        final int sheetNumber = requiredInteger(args, "sheet");
                        final BookSession session = service.get(bookId);

                        if (session.book.getStub(sheetNumber) == null) {
                            throw new NoSuchElementException(
                                    "No sheet " + sheetNumber + " in book " + bookId);
                        }

                        final EditHistory history = session.getEditHistory(sheetNumber);
                        final Map<String, Object> data = historySummary(history);
                        final List<Map<String, Object>> entries = new ArrayList<>();
                        int index = 0;

                        for (EditHistory.Entry entry : history.entries()) {
                            final Map<String, Object> row = new LinkedHashMap<>();
                            row.put("index", index++);
                            row.put("op", entry.op());
                            row.put("summary", entry.summary());
                            row.put("inter_ids", entry.interIds());
                            row.put("at", entry.at().toString());
                            entries.add(row);
                        }

                        data.put("entries", entries);
                        return ToolResults.success(mapper, data);
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static Map<String, Object> historySummary (EditHistory history)
    {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("position", history.position());
        data.put("can_undo", history.canUndo());
        data.put("can_redo", history.canRedo());
        return data;
    }

    private static Map<String, Object> relationSummary (Relation relation,
                                                        Inter source,
                                                        Inter target)
    {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put(
                "relation_id",
                "r:" + source.getSig().getSystem().getId()
                        + ":" + source.getId()
                        + ":" + relation.getClass().getSimpleName()
                        + ":" + target.getId());
        data.put("source_id", source.getId());
        data.put("target_id", target.getId());
        data.put("type", relation.getClass().getSimpleName());
        data.put("class", relation.getClass().getName());
        data.put("grade", relation instanceof Support support ? support.getGrade() : null);
        return data;
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

    private static Integer optionalInteger (Map<String, Object> args,
                                            String key)
    {
        final Object value = args.get(key);

        if (value == null) {
            return null;
        }

        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("'" + key + "' must be an integer");
        }

        return number.intValue();
    }

    private static int requiredInteger (Map<String, Object> args,
                                        String key)
    {
        final Integer value = optionalInteger(args, key);

        if (value == null) {
            throw new IllegalArgumentException("'" + key + "' is required");
        }

        return value;
    }

    private static int optionalInteger (Map<String, Object> args,
                                        String key,
                                        int defaultValue)
    {
        final Integer value = optionalInteger(args, key);
        return value == null ? defaultValue : value;
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

    private static List<Integer> intList (Object value,
                                          String key)
    {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("'" + key + "' must be an integer array");
        }

        final List<Integer> values = new ArrayList<>();

        for (Object item : list) {
            if (!(item instanceof Number number)) {
                throw new IllegalArgumentException("'" + key + "' must contain integers");
            }

            values.add(number.intValue());
        }

        return values;
    }

    private static int clamp (int value,
                              int min,
                              int max)
    {
        return Math.max(min, Math.min(max, value));
    }

    private static CallToolResult error (McpJsonMapper mapper,
                                         Throwable ex)
    {
        if (ex instanceof NoSuchElementException) {
            return ToolResults.error(mapper, "NOT_FOUND", ex.getMessage());
        }

        if (ex instanceof ToolException toolException) {
            return ToolResults.error(
                    mapper,
                    toolException.code,
                    toolException.getMessage(),
                    toolException.details);
        }

        if (ex instanceof IllegalArgumentException) {
            return ToolResults.error(mapper, "INVALID_ARGUMENT", ex.getMessage());
        }

        return ToolResults.error(mapper, "INTERNAL", ex.toString());
    }
}
