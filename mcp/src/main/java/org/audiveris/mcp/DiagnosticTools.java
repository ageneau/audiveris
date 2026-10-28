//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                     D i a g n o s t i c T o o l s                              //
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tools that only read Audiveris metadata.
 */
public abstract class DiagnosticTools
{
    private DiagnosticTools ()
    {
    }

    public static List<SyncToolSpecification> specs (McpJsonMapper mapper,
                                                     AudiverisFacade facade)
    {
        return List.of(
                version(mapper, facade),
                listSteps(mapper, facade),
                listOcrLanguages(mapper, facade),
                listShapes(mapper, facade),
                listRelationTypes(mapper, facade));
    }

    private static SyncToolSpecification version (McpJsonMapper mapper,
                                                  AudiverisFacade facade)
    {
        return spec(
                "version",
                "Audiveris version",
                "Report Audiveris, MCP server, Java and OCR versions.",
                Schemas.empty(),
                readOnly("Audiveris version"),
                (exchange, request) -> {
                    try {
                        return ToolResults.success(mapper, facade.version());
                    } catch (Throwable ex) {
                        return internalError(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification listSteps (McpJsonMapper mapper,
                                                    AudiverisFacade facade)
    {
        return spec(
                "list_steps",
                "List OMR steps",
                "List the Audiveris OMR pipeline steps in processing order.",
                Schemas.empty(),
                readOnly("List OMR steps"),
                (exchange, request) -> {
                    try {
                        return ToolResults.success(mapper, Map.of("steps", facade.steps()));
                    } catch (Throwable ex) {
                        return internalError(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification listOcrLanguages (McpJsonMapper mapper,
                                                           AudiverisFacade facade)
    {
        return spec(
                "list_ocr_languages",
                "List OCR languages",
                "List the Tesseract languages installed for Audiveris.",
                Schemas.empty(),
                readOnly("List OCR languages"),
                (exchange, request) -> {
                    try {
                        final List<?> warnings = HeadlessBootstrap.getOcrFailure() == null
                                ? List.of()
                                : List.of(Map.of(
                                        "code", "OCR_UNAVAILABLE",
                                        "message", HeadlessBootstrap.getOcrFailure().toString()));

                        return ToolResults.success(mapper, facade.ocrLanguages(), warnings);
                    } catch (Throwable ex) {
                        return internalError(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification listShapes (McpJsonMapper mapper,
                                                     AudiverisFacade facade)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("category", Schemas.string(
                "Optional shape category: heads, rests, clefs, keys, times, notes, "
                        + "text, dynamics, ornaments, articulations, barlines, beams, "
                        + "curves, other."));
        properties.put("manual_only", Schemas.bool(
                "Only return shapes that can be added manually."));

        return spec(
                "list_shapes",
                "List shapes",
                "List Audiveris symbol shapes and whether they can be added manually.",
                Schemas.object(properties),
                readOnly("List shapes"),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String category = optionalString(args, "category");
                        final boolean manualOnly = optionalBoolean(args, "manual_only", false);

                        return ToolResults.success(
                                mapper,
                                Map.of("shapes", facade.shapes(category, manualOnly)));
                    } catch (IllegalArgumentException ex) {
                        return ToolResults.error(mapper, "INVALID_ARGUMENT", ex.getMessage());
                    } catch (Throwable ex) {
                        return internalError(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification listRelationTypes (McpJsonMapper mapper,
                                                            AudiverisFacade facade)
    {
        return spec(
                "list_relation_types",
                "List relation types",
                "List relation classes accepted by link_symbols.",
                Schemas.empty(),
                readOnly("List relation types"),
                (exchange, request) -> {
                    try {
                        return ToolResults.success(
                                mapper,
                                Map.of("relations", facade.relationTypes()));
                    } catch (Throwable ex) {
                        return internalError(mapper, ex);
                    }
                });
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

    private static CallToolResult internalError (McpJsonMapper mapper,
                                                 Throwable ex)
    {
        return ToolResults.error(mapper, "INTERNAL", ex.toString());
    }
}
