//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                          J o b T o o l s                                      //
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * MCP tools that inspect and control asynchronous jobs.
 */
public abstract class JobTools
{
    private static final List<String> STATES = List.of(
            "QUEUED",
            "RUNNING",
            "SUCCEEDED",
            "FAILED",
            "CANCELLED");

    private static final List<String> LEVELS = List.of(
            "DEBUG",
            "INFO",
            "WARN",
            "ERROR");

    private JobTools ()
    {
    }

    public static List<SyncToolSpecification> specs (McpJsonMapper mapper,
                                                     OmrService service,
                                                     JobManager jobs)
    {
        return List.of(
                getJob(mapper, jobs),
                listJobs(mapper, jobs),
                cancelJob(mapper, jobs),
                getLog(mapper, service, jobs));
    }

    private static SyncToolSpecification getJob (McpJsonMapper mapper,
                                                 JobManager jobs)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("job_id", Schemas.string("Job identifier."));
        properties.put("log_lines", Schemas.integer(
                "Number of trailing log lines to include (default: 50)."));

        return spec(
                "get_job",
                "Get job",
                "Report the state, progress, result and recent logs of a job.",
                Schemas.object(properties, "job_id"),
                readOnly("Get job"),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String jobId = requiredString(args, "job_id");
                        final int logLines = clamp(optionalInteger(
                                args,
                                "log_lines",
                                50), 0, 500);
                        return ToolResults.success(
                                mapper,
                                jobs.get(jobId).toMap(logLines));
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification listJobs (McpJsonMapper mapper,
                                                   JobManager jobs)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("book_id", Schemas.string("Optional book identifier."));
        properties.put("state", Schemas.stringEnum(
                STATES,
                "Optional job state filter."));
        properties.put("limit", Schemas.integer(
                "Maximum number of jobs (default: 20)."));

        return spec(
                "list_jobs",
                "List jobs",
                "List asynchronous jobs, most recent first.",
                Schemas.object(properties),
                readOnly("List jobs"),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String bookId = optionalString(args, "book_id");
                        final String state = normalize(
                                optionalString(args, "state"),
                                STATES,
                                "state");
                        final int limit = clamp(optionalInteger(args, "limit", 20), 1, 100);
                        final List<Map<String, Object>> result = new ArrayList<>();

                        for (OmrJob job : jobs.list(bookId, state, limit)) {
                            result.add(job.toMap(0));
                        }

                        return ToolResults.success(mapper, Map.of("jobs", result));
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification cancelJob (McpJsonMapper mapper,
                                                   JobManager jobs)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("job_id", Schemas.string("Job identifier."));

        return spec(
                "cancel_job",
                "Cancel job",
                "Request cooperative cancellation of a running job.",
                Schemas.object(properties, "job_id"),
                annotations("Cancel job", false, true, false),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String jobId = requiredString(args, "job_id");
                        final OmrJob job = jobs.cancel(jobId);
                        return ToolResults.success(
                                mapper,
                                Map.of(
                                        "job_id",
                                        job.id,
                                        "cancel_requested",
                                        true));
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification getLog (McpJsonMapper mapper,
                                                 OmrService service,
                                                 JobManager jobs)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("book_id", Schemas.string("Optional book identifier."));
        properties.put("job_id", Schemas.string("Optional job identifier."));
        properties.put("lines", Schemas.integer(
                "Number of trailing lines (default: 200)."));
        properties.put("level", Schemas.stringEnum(
                LEVELS,
                "Optional minimum log level."));

        return spec(
                "get_log",
                "Get log",
                "Read recent logs for a book's latest job or for a specific job.",
                Schemas.object(properties),
                readOnly("Get log"),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String bookId = optionalString(args, "book_id");
                        final String jobId = optionalString(args, "job_id");

                        if ((bookId == null) == (jobId == null)) {
                            throw new IllegalArgumentException(
                                    "Exactly one of 'book_id' or 'job_id' is required");
                        }

                        final int lines = clamp(optionalInteger(args, "lines", 200), 0, 2000);
                        final String level = normalize(
                                optionalString(args, "level"),
                                LEVELS,
                                "level");
                        final OmrJob job;

                        if (jobId != null) {
                            job = jobs.get(jobId);
                        } else {
                            service.get(bookId);
                            job = jobs.latestForBook(bookId);
                        }

                        final List<Map<String, Object>> result = job == null
                                ? List.of()
                                : filter(job.lastLogs(lines), level);

                        final Map<String, Object> data = new LinkedHashMap<>();
                        data.put("lines", result);
                        data.put("job_id", job == null ? null : job.id);
                        data.put("book_id", bookId);
                        return ToolResults.success(mapper, data);
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static List<Map<String, Object>> filter (List<Map<String, Object>> lines,
                                                     String minimumLevel)
    {
        if (minimumLevel == null) {
            return lines;
        }

        final int minimum = LEVELS.indexOf(minimumLevel);
        final List<Map<String, Object>> filtered = new ArrayList<>();

        for (Map<String, Object> line : lines) {
            final int rank = LEVELS.indexOf(String.valueOf(line.get("level")));

            if (rank >= minimum) {
                filtered.add(line);
            }
        }

        return filtered;
    }

    private static String normalize (String value,
                                     List<String> allowed,
                                     String key)
    {
        if (value == null) {
            return null;
        }

        final String normalized = value.toUpperCase();

        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException("'" + key + "' must be one of " + allowed);
        }

        return normalized;
    }

    private static int clamp (int value,
                              int min,
                              int max)
    {
        return Math.max(min, Math.min(max, value));
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

    private static int optionalInteger (Map<String, Object> args,
                                        String key,
                                        int defaultValue)
    {
        final Object value = args.get(key);

        if (value == null) {
            return defaultValue;
        }

        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("'" + key + "' must be an integer");
        }

        return number.intValue();
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
