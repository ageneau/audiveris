//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                      I n s p e c t i o n T o o l s                             //
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
import org.audiveris.omr.score.LogicalPart;
import org.audiveris.omr.score.Page;
import org.audiveris.omr.score.Score;
import org.audiveris.omr.sheet.Book;
import org.audiveris.omr.sheet.Part;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.SheetStub;
import org.audiveris.omr.sheet.Staff;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.sheet.rhythm.Measure;
import org.audiveris.omr.sheet.rhythm.MeasureStack;
import org.audiveris.omr.sig.SIGraph;
import org.audiveris.omr.sig.inter.AbstractChordInter;
import org.audiveris.omr.sig.inter.AbstractNumberInter;
import org.audiveris.omr.sig.inter.AbstractPitchedInter;
import org.audiveris.omr.sig.inter.AbstractTimeInter;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.sig.inter.KeyInter;
import org.audiveris.omr.sig.inter.LyricItemInter;
import org.audiveris.omr.sig.inter.MetronomeInter;
import org.audiveris.omr.sig.inter.SentenceInter;
import org.audiveris.omr.sig.inter.SlurInter;
import org.audiveris.omr.sig.inter.WordInter;
import org.audiveris.omr.sig.relation.Relation;
import org.audiveris.omr.sig.relation.Support;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * MCP tools that inspect scores and symbols.
 */
public abstract class InspectionTools
{
    private InspectionTools ()
    {
    }

    public static List<SyncToolSpecification> specs (McpJsonMapper mapper,
                                                     OmrService service)
    {
        return List.of(
                getScore(mapper, service),
                listSymbols(mapper, service),
                getSymbol(mapper, service));
    }

    private static SyncToolSpecification getScore (McpJsonMapper mapper,
                                                   OmrService service)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("book_id", Schemas.string("Book session identifier."));
        properties.put("score_index", Schemas.integer(
                "Optional 0-based score index; omit for all scores."));
        properties.put("include_measures", Schemas.bool(
                "Include measure-level rows (default: false)."));
        properties.put("measure_limit", Schemas.integer(
                "Maximum measures per score (default: 500)."));

        return spec(
                "get_score",
                "Get score",
                "Report movements, parts, pages, systems, key/time and measures.",
                Schemas.object(properties, "book_id"),
                readOnly("Get score"),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String bookId = requiredString(args, "book_id");
                        final Integer scoreIndex = optionalInteger(args, "score_index");
                        final boolean includeMeasures = optionalBoolean(
                                args,
                                "include_measures",
                                false);
                        final int measureLimit = clamp(optionalInteger(
                                args,
                                "measure_limit",
                                500), 0, 5000);
                        final BookSession session = service.get(bookId);
                        session.lock.lock();

                        try {
                            final List<Score> scores = session.book.getScores();
                            final List<Map<String, Object>> result = new ArrayList<>();

                            if (scoreIndex != null) {
                                if ((scoreIndex < 0) || (scoreIndex >= scores.size())) {
                                    throw new NoSuchElementException(
                                            "No score_index " + scoreIndex);
                                }

                                result.add(scoreSummary(
                                        scores.get(scoreIndex),
                                        scoreIndex,
                                        includeMeasures,
                                        measureLimit));
                            } else {
                                for (int i = 0; i < scores.size(); i++) {
                                    result.add(scoreSummary(
                                            scores.get(i),
                                            i,
                                            includeMeasures,
                                            measureLimit));
                                }
                            }

                            return ToolResults.success(mapper, Map.of("scores", result));
                        } finally {
                            session.lock.unlock();
                        }
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification listSymbols (McpJsonMapper mapper,
                                                      OmrService service)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("book_id", Schemas.string("Book session identifier."));
        properties.put("sheet", Schemas.integer("1-based sheet number."));
        properties.put("system_id", Schemas.integer("Optional system ID."));
        properties.put("staff_id", Schemas.integer("Optional staff ID."));
        properties.put("shape", Schemas.string("Optional Shape enum name."));
        properties.put("inter_class", Schemas.string(
                "Optional simple or fully-qualified inter class name."));
        properties.put("bbox", Schemas.object(new LinkedHashMap<>(Map.of(
                "x", Schemas.integer("Left coordinate."),
                "y", Schemas.integer("Top coordinate."),
                "width", Schemas.integer("Width."),
                "height", Schemas.integer("Height."))), "x", "y", "width", "height"));
        properties.put("include_relations", Schemas.bool(
                "Include relation summaries (default: false)."));
        properties.put("include_attributes", Schemas.bool(
                "Include class-specific attributes (default: false)."));
        properties.put("limit", Schemas.integer("Page size (default: 200)."));
        properties.put("offset", Schemas.integer("Offset (default: 0)."));

        return spec(
                "list_symbols",
                "List symbols",
                "List inters on a sheet with optional filters and pagination.",
                Schemas.object(properties, "book_id", "sheet"),
                readOnly("List symbols"),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String bookId = requiredString(args, "book_id");
                        final int sheetNumber = requiredInteger(args, "sheet");
                        final Integer systemId = optionalInteger(args, "system_id");
                        final Integer staffId = optionalInteger(args, "staff_id");
                        final String shapeName = optionalString(args, "shape");
                        final String interClass = optionalString(args, "inter_class");
                        final Rectangle bbox = bbox(args.get("bbox"));
                        final boolean includeRelations = optionalBoolean(
                                args,
                                "include_relations",
                                false);
                        final boolean includeAttributes = optionalBoolean(
                                args,
                                "include_attributes",
                                false);
                        final int limit = clamp(optionalInteger(args, "limit", 200), 1, 1000);
                        final int offset = Math.max(0, optionalInteger(args, "offset", 0));
                        final Shape shape = shapeName == null ? null : Shape.valueOf(shapeName);
                        final BookSession session = service.get(bookId);
                        session.lock.lock();

                        try {
                            final SheetStub stub = session.book.getStub(sheetNumber);

                            if (stub == null) {
                                throw new NoSuchElementException(
                                        "No sheet " + sheetNumber + " in book " + bookId);
                            }

                            final Sheet sheet = stub.getSheet();

                            if (sheet == null) {
                                return ToolResults.success(
                                        mapper,
                                        Map.of(
                                                "total", 0,
                                                "offset", offset,
                                                "limit", limit,
                                                "has_more", false,
                                                "symbols", List.of()));
                            }

                            final List<Inter> matching = new ArrayList<>();
                            final List<SystemInfo> systems = sheet.getSystems();

                            for (SystemInfo system : systems) {
                                if ((systemId != null) && (system.getId() != systemId)) {
                                    continue;
                                }

                                final SIGraph sig = system.getSig();

                                if (sig == null) {
                                    continue;
                                }

                                for (Inter inter : sig.vertexSet()) {
                                    if (matches(
                                            inter,
                                            staffId,
                                            shape,
                                            interClass,
                                            bbox)) {
                                        matching.add(inter);
                                    }
                                }
                            }

                            matching.sort(Comparator.comparingInt(Inter::getId));
                            final int total = matching.size();
                            final int from = Math.min(offset, total);
                            final int to = Math.min(from + limit, total);
                            final List<Map<String, Object>> symbols = new ArrayList<>();

                            for (int i = from; i < to; i++) {
                                symbols.add(symbolSummary(
                                        matching.get(i),
                                        includeRelations,
                                        includeAttributes,
                                        false));
                            }

                            final Map<String, Object> data = new LinkedHashMap<>();
                            data.put("total", total);
                            data.put("offset", offset);
                            data.put("limit", limit);
                            data.put("has_more", to < total);
                            data.put("symbols", symbols);
                            return ToolResults.success(mapper, data);
                        } finally {
                            session.lock.unlock();
                        }
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification getSymbol (McpJsonMapper mapper,
                                                    OmrService service)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("book_id", Schemas.string("Book session identifier."));
        properties.put("sheet", Schemas.integer("1-based sheet number."));
        properties.put("inter_id", Schemas.integer("Inter ID."));
        properties.put("include_relations", Schemas.bool(
                "Include relations (default: true)."));
        properties.put("include_attributes", Schemas.bool(
                "Include class-specific attributes (default: true)."));
        properties.put("include_glyph", Schemas.bool(
                "Include glyph details (default: false)."));

        return spec(
                "get_symbol",
                "Get symbol",
                "Report one inter with optional relations, attributes and glyph.",
                Schemas.object(properties, "book_id", "sheet", "inter_id"),
                readOnly("Get symbol"),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String bookId = requiredString(args, "book_id");
                        final int sheetNumber = requiredInteger(args, "sheet");
                        final int interId = requiredInteger(args, "inter_id");
                        final boolean includeRelations = optionalBoolean(
                                args,
                                "include_relations",
                                true);
                        final boolean includeAttributes = optionalBoolean(
                                args,
                                "include_attributes",
                                true);
                        final boolean includeGlyph = optionalBoolean(
                                args,
                                "include_glyph",
                                false);
                        final BookSession session = service.get(bookId);
                        session.lock.lock();

                        try {
                            final SheetStub stub = session.book.getStub(sheetNumber);

                            if (stub == null) {
                                throw new NoSuchElementException(
                                        "No sheet " + sheetNumber + " in book " + bookId);
                            }

                            final Sheet sheet = stub.getSheet();

                            if (sheet == null) {
                                throw new NoSuchElementException(
                                        "Sheet " + sheetNumber + " is not loaded");
                            }

                            final Inter inter = sheet.getInterIndex().getEntity(interId);

                            if ((inter == null) || inter.isRemoved()) {
                                throw new NoSuchElementException(
                                        "No inter " + interId + " on sheet " + sheetNumber);
                            }

                            return ToolResults.success(
                                    mapper,
                                    Map.of(
                                            "symbol",
                                            symbolSummary(
                                                    inter,
                                                    includeRelations,
                                                    includeAttributes,
                                                    includeGlyph)));
                        } finally {
                            session.lock.unlock();
                        }
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static boolean matches (Inter inter,
                                    Integer staffId,
                                    Shape shape,
                                    String interClass,
                                    Rectangle bbox)
    {
        if ((staffId != null)
                && ((inter.getStaff() == null) || (inter.getStaff().getId() != staffId))) {
            return false;
        }

        if ((shape != null) && (inter.getShape() != shape)) {
            return false;
        }

        if (interClass != null) {
            final String simple = inter.getClass().getSimpleName();
            final String qualified = inter.getClass().getName();

            if (!interClass.equals(simple) && !interClass.equals(qualified)) {
                return false;
            }
        }

        if (bbox != null) {
            final Rectangle bounds = inter.getBounds();
            return bounds != null && bounds.intersects(bbox);
        }

        return true;
    }

    static Map<String, Object> symbolSummary (Inter inter,
                                                      boolean includeRelations,
                                                      boolean includeAttributes,
                                                      boolean includeGlyph)
    {
        final SIGraph sig = inter.getSig();
        final SystemInfo system = sig == null ? null : sig.getSystem();
        final Rectangle bounds = inter.getBounds();
        final Point center = inter.getCenter();

        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("inter_id", inter.getId());
        data.put("system_id", system == null ? null : system.getId());
        data.put("staff_id", inter.getStaff() == null ? null : inter.getStaff().getId());
        data.put("part_id", inter.getPart() == null ? null : inter.getPart().getId());
        data.put("class", inter.getClass().getSimpleName());
        data.put("shape", inter.getShape() == null ? null : inter.getShape().name());
        data.put("bounds", bounds == null
                ? null
                : List.of(bounds.x, bounds.y, bounds.width, bounds.height));
        data.put("center", center == null ? null : List.of(center.x, center.y));
        data.put("grade", inter.getGrade());
        data.put("contextual_grade", inter.getContextualGrade());
        data.put("manual", inter.isManual());
        data.put("removed", inter.isRemoved());
        data.put("frozen", inter.isFrozen());
        data.put("glyph_id", inter.getGlyph() == null ? null : inter.getGlyph().getId());
        data.put("attributes", includeAttributes ? attributesOf(inter) : Map.of());
        data.put("relations", includeRelations ? relationsOf(inter) : List.of());

        if (includeGlyph && inter.getGlyph() != null) {
            final var glyph = inter.getGlyph();
            final Rectangle gb = glyph.getBounds();
            final Map<String, Object> glyphData = new LinkedHashMap<>();
            glyphData.put("glyph_id", glyph.getId());
            glyphData.put("bounds", gb == null
                    ? null
                    : List.of(gb.x, gb.y, gb.width, gb.height));
            glyphData.put("weight", glyph.getWeight());
            data.put("glyph", glyphData);
        }

        return data;
    }

    private static Map<String, Object> attributesOf (Inter inter)
    {
        final Map<String, Object> data = new LinkedHashMap<>();

        if (inter instanceof AbstractTimeInter time) {
            data.put("numerator", time.getNumerator());
            data.put("denominator", time.getDenominator());
        }

        if (inter instanceof KeyInter key) {
            data.put("fifths", key.getFifths());
        }

        if (inter instanceof AbstractPitchedInter pitched) {
            data.put("pitch", pitched.getPitch());
        }

        if (inter instanceof AbstractChordInter chord) {
            data.put("voice_id", chord.getVoice() == null ? null : chord.getVoice().getId());
            final List<Integer> members = new ArrayList<>();

            for (Inter member : chord.getMembers()) {
                members.add(member.getId());
            }

            data.put("member_ids", members);
        }

        if (inter instanceof LyricItemInter lyric) {
            data.put("text", lyric.getValue());
            data.put("syllable", String.valueOf(lyric.getSyllabicType()));
        } else if (inter instanceof WordInter word) {
            data.put("text", word.getValue());
        }

        if (inter instanceof SentenceInter sentence) {
            data.put("text", sentence.getValue());
            data.put("role", String.valueOf(sentence.getRole()));
        }

        if (inter instanceof MetronomeInter metronome) {
            data.put("text", metronome.getValue());
        }

        if (inter instanceof SlurInter slur) {
            data.put("tie", slur.isTie());
        }

        if (inter instanceof AbstractNumberInter number) {
            data.put("value", number.getValue());
        }

        return data;
    }

    private static List<Map<String, Object>> relationsOf (Inter inter)
    {
        final SIGraph sig = inter.getSig();

        if (sig == null) {
            return List.of();
        }

        final List<Map<String, Object>> relations = new ArrayList<>();
        final Map<String, Integer> seen = new LinkedHashMap<>();

        for (Relation relation : sig.edgesOf(inter)) {
            final Inter source = sig.getEdgeSource(relation);
            final Inter target = sig.getEdgeTarget(relation);
            final String type = relation.getClass().getSimpleName();
            final String base = "r:" + sig.getSystem().getId()
                    + ":" + source.getId()
                    + ":" + type
                    + ":" + target.getId();
            final int count = seen.merge(base, 1, Integer::sum);
            final String relationId = count == 1 ? base : base + "#" + count;

            final Map<String, Object> data = new LinkedHashMap<>();
            data.put("relation_id", relationId);
            data.put("source_id", source.getId());
            data.put("target_id", target.getId());
            data.put("type", type);
            data.put("class", relation.getClass().getName());
            data.put("grade", relation instanceof Support support ? support.getGrade() : null);
            relations.add(data);
        }

        return relations;
    }

    private static Map<String, Object> scoreSummary (Score score,
                                                     int index,
                                                     boolean includeMeasures,
                                                     int measureLimit)
    {
        final Set<Integer> logicalIds = new LinkedHashSet<>();
        final Map<Integer, Map<String, Object>> parts = new LinkedHashMap<>();
        final Map<Integer, Set<Integer>> staffIds = new LinkedHashMap<>();
        final List<Map<String, Object>> pages = new ArrayList<>();
        final List<Map<String, Object>> systems = new ArrayList<>();
        final List<Integer> stackIds = new ArrayList<>();

        for (Page page : score.getPages()) {
            final Map<String, Object> pageData = new LinkedHashMap<>();
            pageData.put("page_id", page.getId());
            pageData.put("sheet", page.getRef() == null ? null : page.getRef().getSheetNumber());
            pageData.put("movement_start", page.isMovementStart());
            pageData.put("measure_count", measureCount(page));
            pages.add(pageData);

            for (SystemInfo system : page.getSystems()) {
                final Map<String, Object> systemData = new LinkedHashMap<>();
                systemData.put("system_id", system.getId());
                systemData.put("page_id", page.getId());

                final List<Integer> staffList = new ArrayList<>();
                for (Staff staff : system.getStaves()) {
                    staffList.add(staff.getId());
                }
                systemData.put("staff_ids", staffList);

                final List<MeasureStack> stacks = system.getStacks();
                systemData.put("measure_count", stacks.size());

                if (!stacks.isEmpty()) {
                    final MeasureStack stack = stacks.get(0);
                    final AbstractTimeInter time = stack.getTimeSignature();

                    if (time != null) {
                        systemData.put("time", Map.of(
                                "numerator", time.getNumerator(),
                                "denominator", time.getDenominator()));
                    }

                    final Measure first = stack.getFirstMeasure();
                    final Staff firstStaff = system.getStaves().isEmpty()
                            ? null
                            : system.getStaves().get(0);

                    if ((first != null) && (firstStaff != null)) {
                        final KeyInter key = first.getKey(firstStaff);

                        if (key != null) {
                            final Map<String, Object> keyData = new LinkedHashMap<>();
                            keyData.put("fifths", key.getFifths());
                            systemData.put("key", keyData);
                        }
                    }

                    for (MeasureStack s : stacks) {
                        stackIds.add(s.getIdValue());
                    }
                }

                systems.add(systemData);

                for (Part part : system.getParts()) {
                    final LogicalPart logical = part.getLogicalPart();

                    if (logical == null) {
                        continue;
                    }

                    logicalIds.add(logical.getId());
                    staffIds.computeIfAbsent(logical.getId(), id -> new LinkedHashSet<>());
                    final Map<String, Object> partData = parts.computeIfAbsent(
                            logical.getId(),
                            id -> {
                                final Map<String, Object> data = new LinkedHashMap<>();
                                data.put("part_id", part.getId());
                                data.put("logical_part_id", logical.getId());
                                data.put("name", logical.getName());
                                data.put("abbreviation", logical.getAbbreviation());
                                data.put("staff_ids", new ArrayList<Integer>());
                                return data;
                            });

                    @SuppressWarnings("unchecked")
                    final List<Integer> partStaffIds = (List<Integer>) partData.get("staff_ids");

                    for (Staff staff : part.getStaves()) {
                        if (!partStaffIds.contains(staff.getId())) {
                            partStaffIds.add(staff.getId());
                        }
                    }
                }
            }
        }

        // Add logical parts that have no physical part in a system.
        for (LogicalPart logical : score.getLogicalParts()) {
            parts.computeIfAbsent(logical.getId(), id -> {
                final Map<String, Object> data = new LinkedHashMap<>();
                data.put("part_id", null);
                data.put("logical_part_id", logical.getId());
                data.put("name", logical.getName());
                data.put("abbreviation", logical.getAbbreviation());
                data.put("staff_ids", new ArrayList<Integer>());
                return data;
            });
        }

        final List<Map<String, Object>> measures = new ArrayList<>();
        if (includeMeasures) {
            final int limit = measureLimit == 0 ? Integer.MAX_VALUE : measureLimit;
            int count = 0;

            outer:
            for (Page page : score.getPages()) {
                for (SystemInfo system : page.getSystems()) {
                    for (MeasureStack stack : system.getStacks()) {
                        final Map<String, Object> measureData = new LinkedHashMap<>();
                        measureData.put("measure_id", stack.getIdValue());
                        measureData.put("page_id", page.getId());
                        measureData.put("system_id", system.getId());
                        measureData.put("stack_id", stack.getIdValue());
                        measureData.put("number", String.valueOf(stack.getIdValue()));
                        final boolean movementStart = page.isMovementStart()
                                && !page.getSystems().isEmpty()
                                && !page.getSystems().get(0).getStacks().isEmpty()
                                && stack == page.getSystems().get(0).getStacks().get(0);
                        measureData.put("start", movementStart);

                        final AbstractTimeInter time = stack.getTimeSignature();

                        if (time != null) {
                            measureData.put("time", Map.of(
                                    "numerator", time.getNumerator(),
                                    "denominator", time.getDenominator()));
                        }

                        measures.add(measureData);
                        count++;

                        if (count >= limit) {
                            break outer;
                        }
                    }
                }
            }
        }

        Integer firstMeasure = null;
        Integer lastMeasure = null;

        for (Integer id : stackIds) {
            if (firstMeasure == null || id < firstMeasure) {
                firstMeasure = id;
            }
            if (lastMeasure == null || id > lastMeasure) {
                lastMeasure = id;
            }
        }

        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("score_index", index);
        data.put("score_id", score.getId());
        data.put("page_count", score.getPageCount());
        data.put("parts", new ArrayList<>(parts.values()));
        data.put("pages", pages);
        data.put("systems", systems);
        data.put("measures", measures);
        final Map<String, Object> range = new LinkedHashMap<>();
        range.put("first", firstMeasure);
        range.put("last", lastMeasure);
        data.put("measure_range", range);
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

    private static Rectangle bbox (Object value)
    {
        if (value == null) {
            return null;
        }

        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("'bbox' must be an object");
        }

        final int x = bboxInt(map, "x");
        final int y = bboxInt(map, "y");
        final int width = bboxInt(map, "width");
        final int height = bboxInt(map, "height");

        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("'bbox' width and height must be positive");
        }

        return new Rectangle(x, y, width, height);
    }

    private static int bboxInt (Map<?, ?> map,
                                String key)
    {
        final Object value = map.get(key);

        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("'bbox." + key + "' must be an integer");
        }

        return number.intValue();
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
        return ToolAnnotations.builder()
                .title(title)
                .readOnlyHint(true)
                .destructiveHint(false)
                .idempotentHint(true)
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

    private static int optionalInteger (Map<String, Object> args,
                                        String key,
                                        int defaultValue)
    {
        final Integer value = optionalInteger(args, key);
        return value == null ? defaultValue : value;
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

        if (ex instanceof IllegalArgumentException) {
            return ToolResults.error(mapper, "INVALID_ARGUMENT", ex.getMessage());
        }

        return ToolResults.error(mapper, "INTERNAL", ex.toString());
    }
}
