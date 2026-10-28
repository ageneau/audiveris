//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                      P r o c e s s i n g T o o l s                            //
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

import org.audiveris.omr.OMR;
import org.audiveris.omr.score.Score;
import org.audiveris.omr.score.ScoreExporter;
import org.audiveris.omr.sheet.Book;
import org.audiveris.omr.sheet.BookManager;
import org.audiveris.omr.sheet.ExportPattern;
import org.audiveris.omr.sheet.SheetStub;
import org.audiveris.omr.step.OmrStep;
import org.audiveris.omr.util.NaturalSpec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * MCP tools that run Audiveris processing steps and exports.
 */
public abstract class ProcessingTools
{
    private static final List<String> STEP_NAMES = Arrays.stream(OmrStep.values())
            .map(Enum::name)
            .toList();

    private ProcessingTools ()
    {
    }

    public static List<SyncToolSpecification> specs (McpJsonMapper mapper,
                                                     OmrService service,
                                                     JobManager jobs)
    {
        return List.of(
                runStep(mapper, service, jobs),
                transcribe(mapper, service, jobs),
                exportMusicXml(mapper, service, jobs),
                printBook(mapper, service, jobs));
    }

    private static SyncToolSpecification runStep (McpJsonMapper mapper,
                                                  OmrService service,
                                                  JobManager jobs)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("book_id", Schemas.string("Book session identifier."));
        properties.put("step", Schemas.stringEnum(STEP_NAMES, "Target OMR step."));
        properties.put("sheets", Schemas.sheetSpec(
                "Optional sheet selection: integer, array, or \"1,3-5\"."));
        properties.put("force", Schemas.bool(
                "Force reprocessing when the step is already done (default: false)."));
        properties.put("save", Schemas.bool(
                "Store the book after the step (default: false)."));
        properties.put("swap", Schemas.bool(
                "Store and dispose processed sheets (default: true)."));

        return spec(
                "run_step",
                "Run OMR step",
                "Run the pipeline up to the requested step on selected sheets.",
                Schemas.object(properties, "book_id", "step"),
                annotations("Run OMR step", false, true, false),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String bookId = requiredString(args, "book_id");
                        final OmrStep step = OmrStep.valueOf(
                                requiredString(args, "step").toUpperCase());
                        final Object sheetsValue = args.get("sheets");
                        final boolean force = optionalBoolean(args, "force", false);
                        final boolean save = optionalBoolean(args, "save", false);
                        final boolean swap = optionalBoolean(args, "swap", true);
                        final BookSession session = service.get(bookId);
                        final List<SheetStub> stubs = selectStubs(
                                session.book,
                                sheetsValue);

                        final Map<String, Object> progress = new LinkedHashMap<>();
                        progress.put("sheets_total", stubs.size());

                        final OmrJob job = jobs.submit(
                                "run_step",
                                session,
                                progress,
                                currentJob -> {
                                    final boolean ok = session.book.reachBookStep(
                                            step,
                                            force,
                                            stubs,
                                            swap);

                                    if (currentJob.isCancelRequested()) {
                                        throw new ToolException("CANCELLED", "Job cancelled");
                                    }

                                    if (!ok) {
                                        throw new ToolException(
                                                "ENGINE_ERROR",
                                                "Could not reach step " + step);
                                    }

                                    if (save) {
                                        service.save(session, null);
                                    }

                                    final Map<String, Object> data = new LinkedHashMap<>();
                                    data.put("book_id", bookId);
                                    data.put("step", step.name());
                                    data.put("sheets", ids(stubs));
                                    data.put("ok", true);
                                    return data;
                                });

                        return started(mapper, job);
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification transcribe (McpJsonMapper mapper,
                                                     OmrService service,
                                                     JobManager jobs)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("book_id", Schemas.string("Book session identifier."));
        properties.put("sheets", Schemas.sheetSpec(
                "Optional sheet selection: integer, array, or \"1,3-5\"."));
        properties.put("save", Schemas.bool(
                "Store the book after transcription (default: true)."));
        properties.put("swap", Schemas.bool(
                "Store and dispose processed sheets (default: true)."));

        return spec(
                "transcribe",
                "Transcribe book",
                "Run all OMR steps and build the book score(s).",
                Schemas.object(properties, "book_id"),
                annotations("Transcribe book", false, true, false),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String bookId = requiredString(args, "book_id");
                        final Object sheetsValue = args.get("sheets");
                        final boolean save = optionalBoolean(args, "save", true);
                        final boolean swap = optionalBoolean(args, "swap", true);
                        final BookSession session = service.get(bookId);
                        final List<SheetStub> stubs = selectStubs(
                                session.book,
                                sheetsValue);
                        final List<Score> scores = scoreList(
                                session.book,
                                sheetsValue);

                        final Map<String, Object> progress = new LinkedHashMap<>();
                        progress.put("sheets_total", stubs.size());

                        final OmrJob job = jobs.submit(
                                "transcribe",
                                session,
                                progress,
                                currentJob -> {
                                    final boolean ok = session.book.transcribe(
                                            stubs,
                                            scores,
                                            swap);

                                    if (currentJob.isCancelRequested()) {
                                        throw new ToolException("CANCELLED", "Job cancelled");
                                    }

                                    if (!ok) {
                                        throw new ToolException(
                                                "ENGINE_ERROR",
                                                "Transcription failed");
                                    }

                                    if (save) {
                                        service.save(session, null);
                                    }

                                    final Map<String, Object> data = new LinkedHashMap<>();
                                    data.put("book_id", bookId);
                                    data.put("scores", scores.size());
                                    data.put("pages", pageCount(scores));
                                    data.put("ok", true);
                                    return data;
                                });

                        return started(mapper, job);
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification exportMusicXml (McpJsonMapper mapper,
                                                         OmrService service,
                                                         JobManager jobs)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("book_id", Schemas.string("Book session identifier."));
        properties.put("output_path", Schemas.string(
                "Optional output path, with or without extension."));
        properties.put("sheets", Schemas.sheetSpec(
                "Optional sheet selection: integer, array, or \"1,3-5\"."));
        properties.put("format", Schemas.stringEnum(
                List.of("mxl", "xml"),
                "MusicXML format (default: mxl)."));
        properties.put("ensure_transcribed", Schemas.bool(
                "Transcribe before exporting if needed (default: true)."));

        return spec(
                "export_musicxml",
                "Export MusicXML",
                "Export the book score(s) to MusicXML.",
                Schemas.object(properties, "book_id"),
                annotations("Export MusicXML", false, false, false),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String bookId = requiredString(args, "book_id");
                        final String outputPath = optionalString(args, "output_path");
                        final Object sheetsValue = args.get("sheets");
                        final String format = optionalString(args, "format");
                        final String normalizedFormat = format == null
                                ? "mxl"
                                : format.toLowerCase();
                        final boolean ensureTranscribed = optionalBoolean(
                                args,
                                "ensure_transcribed",
                                true);

                        if (!normalizedFormat.equals("mxl") && !normalizedFormat.equals("xml")) {
                            throw new IllegalArgumentException(
                                    "'format' must be 'mxl' or 'xml'");
                        }

                        final BookSession session = service.get(bookId);
                        final List<SheetStub> stubs = selectStubs(
                                session.book,
                                sheetsValue);
                        final List<Score> scores = scoreList(
                                session.book,
                                sheetsValue);

                        final OmrJob job = jobs.submit(
                                "export_musicxml",
                                session,
                                Map.of("sheets_total", stubs.size()),
                                currentJob -> {
                                    if (ensureTranscribed
                                            && (scores.isEmpty() || !allDone(
                                                    stubs,
                                                    OmrStep.PAGE))) {
                                        final boolean ok = session.book.transcribe(
                                                stubs,
                                                scores,
                                                true);

                                        if (currentJob.isCancelRequested()) {
                                            throw new ToolException(
                                                    "CANCELLED",
                                                    "Job cancelled");
                                        }

                                        if (!ok) {
                                            throw new ToolException(
                                                    "ENGINE_ERROR",
                                                    "Transcription failed");
                                        }
                                    }

                                    if (currentJob.isCancelRequested()) {
                                        throw new ToolException("CANCELLED", "Job cancelled");
                                    }

                                    if (scores.isEmpty()) {
                                        throw new ToolException(
                                                "ENGINE_ERROR",
                                                "No score to export");
                                    }

                                    return Map.of(
                                            "outputs",
                                            exportScores(
                                                    service,
                                                    session,
                                                    scores,
                                                    outputPath,
                                                    normalizedFormat));
                                });

                        return started(mapper, job);
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification printBook (McpJsonMapper mapper,
                                                    OmrService service,
                                                    JobManager jobs)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("book_id", Schemas.string("Book session identifier."));
        properties.put("output_path", Schemas.string("Optional target PDF path."));
        properties.put("sheets", Schemas.sheetSpec(
                "Optional sheet selection: integer, array, or \"1,3-5\"."));

        return spec(
                "print_book",
                "Print book",
                "Render the book to a PDF file.",
                Schemas.object(properties, "book_id"),
                annotations("Print book", false, false, false),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String bookId = requiredString(args, "book_id");
                        final String outputPath = optionalString(args, "output_path");
                        final Object sheetsValue = args.get("sheets");
                        final BookSession session = service.get(bookId);
                        final List<SheetStub> stubs = selectStubs(
                                session.book,
                                sheetsValue);

                        final OmrJob job = jobs.submit(
                                "print_book",
                                session,
                                Map.of("sheets_total", stubs.size()),
                                currentJob -> {
                                    final Path target;

                                    if (outputPath != null) {
                                        target = service.resolve(outputPath);
                                    } else {
                                        target = session.getBookPath().resolveSibling(
                                                session.book.getRadix() + OMR.PRINT_EXTENSION);
                                    }

                                    if (target.getParent() != null) {
                                        Files.createDirectories(target.getParent());
                                    }

                                    session.book.setPrintPath(target);
                                    session.book.print(stubs);

                                    if (currentJob.isCancelRequested()) {
                                        throw new ToolException("CANCELLED", "Job cancelled");
                                    }

                                    if (!isPdf(target)) {
                                        throw new ToolException(
                                                "IO_ERROR",
                                                "Print produced no valid PDF: " + target);
                                    }

                                    final Map<String, Object> output = new LinkedHashMap<>();
                                    output.put("kind", "pdf");
                                    output.put("path", target.toString());
                                    return Map.of("outputs", List.of(output));
                                });

                        return started(mapper, job);
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static boolean isPdf (Path path)
    {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) < 5) {
                return false;
            }

            final byte[] header = new byte[5];

            try (InputStream in = Files.newInputStream(path)) {
                if (in.read(header) != header.length) {
                    return false;
                }
            }

            return header[0] == '%'
                    && header[1] == 'P'
                    && header[2] == 'D'
                    && header[3] == 'F'
                    && header[4] == '-';
        } catch (IOException ex) {
            return false;
        }
    }

    private static List<Map<String, Object>> exportScores (OmrService service,
                                                           BookSession session,
                                                           List<Score> scores,
                                                           String outputPath,
                                                           String format)
        throws Exception
    {
        final Path base;

        if (outputPath != null) {
            base = ExportPattern.getPathSansExt(service.resolve(outputPath));
        } else {
            base = session.getBookPath().resolveSibling(session.book.getRadix());
        }

        if (base.getParent() != null) {
            Files.createDirectories(base.getParent());
        }

        final boolean compressed = "mxl".equals(format);
        final String extension = compressed
                ? OMR.COMPRESSED_SCORE_EXTENSION
                : OMR.SCORE_EXTENSION;
        final String baseName = base.getFileName().toString();
        final List<Map<String, Object>> outputs = new ArrayList<>();

        for (int i = 0; i < scores.size(); i++) {
            final Score score = scores.get(i);
            final String scoreName = (scores.size() > 1)
                    ? (baseName + OMR.MOVEMENT_EXTENSION + (i + 1))
                    : baseName;
            final Path path = base.resolveSibling(scoreName + extension);

            try {
                new ScoreExporter(score).export(
                        path,
                        scoreName,
                        BookManager.useSignature(),
                        compressed);
            } catch (Exception ex) {
                throw new ToolException("IO_ERROR", "Could not export " + path, Map.of(
                        "path", path.toString(),
                        "error", ex.toString()));
            }

            final Map<String, Object> output = new LinkedHashMap<>();
            output.put("kind", "musicxml");
            output.put("path", path.toString());
            output.put("format", format);
            output.put("score_index", i);
            outputs.add(output);
        }

        return outputs;
    }

    private static List<SheetStub> selectStubs (Book book,
                                                Object sheetsValue)
    {
        final List<SheetStub> stubs;

        if (sheetsValue == null) {
            stubs = Book.getValidStubs(book.getValidSelectedStubs());
        } else {
            final List<SheetStub> selected = book.getStubs(parseSheetIds(book, sheetsValue));
            stubs = Book.getValidStubs(selected);
        }

        if (stubs.isEmpty()) {
            throw new IllegalArgumentException("No valid sheet selected");
        }

        return stubs;
    }

    private static List<Integer> parseSheetIds (Book book,
                                                Object value)
    {
        final List<Integer> ids;

        if (value instanceof String string) {
            ids = NaturalSpec.decode(string, true, book.getStubs().size());
        } else if (value instanceof Number number) {
            ids = List.of(number.intValue());
        } else if (value instanceof List<?> list) {
            ids = new ArrayList<>();

            for (Object item : list) {
                if (!(item instanceof Number number)) {
                    throw new IllegalArgumentException(
                            "'sheets' must contain integers");
                }

                ids.add(number.intValue());
            }
        } else {
            throw new IllegalArgumentException(
                    "'sheets' must be a string, integer or integer array");
        }

        for (Integer id : ids) {
            if (book.getStub(id) == null) {
                throw new NoSuchElementException("No sheet " + id + " in book");
            }
        }

        return ids;
    }

    private static List<Score> scoreList (Book book,
                                          Object sheetsValue)
    {
        if (sheetsValue == null && !hasSelection(book)) {
            return book.getScores();
        }

        return new ArrayList<>();
    }

    private static boolean hasSelection (Book book)
    {
        final String selection = book.getSheetsSelection();
        return selection != null && !selection.isBlank();
    }

    private static boolean allDone (List<SheetStub> stubs,
                                    OmrStep step)
    {
        for (SheetStub stub : stubs) {
            if (!stub.isDone(step)) {
                return false;
            }
        }

        return true;
    }

    private static int pageCount (List<Score> scores)
    {
        int pages = 0;

        for (Score score : scores) {
            pages += score.getPageCount();
        }

        return pages;
    }

    private static List<Integer> ids (List<SheetStub> stubs)
    {
        final List<Integer> ids = new ArrayList<>();

        for (SheetStub stub : stubs) {
            ids.add(stub.getNumber());
        }

        return ids;
    }

    private static CallToolResult started (McpJsonMapper mapper,
                                           OmrJob job)
    {
        return ToolResults.success(mapper, Map.of("job_id", job.id));
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

        if (ex instanceof IllegalStateException) {
            return ToolResults.error(mapper, "JOB_BUSY", ex.getMessage());
        }

        if (ex instanceof ToolException toolException) {
            return ToolResults.error(
                    mapper,
                    toolException.code,
                    toolException.getMessage(),
                    toolException.details);
        }

        return ToolResults.error(mapper, "INTERNAL", ex.toString());
    }
}
