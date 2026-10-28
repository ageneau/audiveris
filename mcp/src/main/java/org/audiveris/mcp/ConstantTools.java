//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                       C o n s t a n t T o o l s                               //
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

import org.audiveris.omr.constant.Constant;
import org.audiveris.omr.constant.ConstantSet;
import org.audiveris.omr.constant.PackageNode;
import org.audiveris.omr.constant.UnitManager;
import org.audiveris.omr.constant.UnitNode;

import org.reflections.Reflections;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;

/**
 * MCP tools that inspect and change Audiveris constants.
 */
public abstract class ConstantTools
{
    private static final Object LOAD_LOCK = new Object();

    private static volatile Map<String, Constant<?>> constants;

    /** Constants that expose personal file history; keep them out of MCP. */
    private static final Set<String> SENSITIVE = Set.of(
            "org.audiveris.omr.sheet.BookManager.bookHistory",
            "org.audiveris.omr.sheet.BookManager.imageHistory");

    private ConstantTools ()
    {
    }

    public static List<SyncToolSpecification> specs (McpJsonMapper mapper)
    {
        return List.of(
                getConstants(mapper),
                setConstant(mapper),
                resetConstant(mapper));
    }

    private static SyncToolSpecification getConstants (McpJsonMapper mapper)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("prefix", Schemas.string(
                "Optional qualified-name prefix filter."));
        properties.put("limit", Schemas.integer("Page size (default: 100)."));
        properties.put("offset", Schemas.integer("Offset (default: 0)."));

        return spec(
                "get_constants",
                "Get constants",
                "List Audiveris constants and their current values.",
                Schemas.object(properties),
                readOnly("Get constants"),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String prefix = optionalString(args, "prefix");
                        final int limit = clamp(optionalInteger(args, "limit", 100), 1, 1000);
                        final int offset = Math.max(0, optionalInteger(args, "offset", 0));
                        final List<Map<String, Object>> matching = new ArrayList<>();

                        for (Constant<?> constant : all().values()) {
                            final String qname = constant.getQualifiedName();

                            if ((prefix == null) || qname.startsWith(prefix)) {
                                matching.add(constantSummary(constant));
                            }
                        }

                        final int total = matching.size();
                        final int from = Math.min(offset, total);
                        final int to = Math.min(from + limit, total);
                        final Map<String, Object> data = new LinkedHashMap<>();
                        data.put("total", total);
                        data.put("offset", offset);
                        data.put("limit", limit);
                        data.put("has_more", to < total);
                        data.put("constants", new ArrayList<>(matching.subList(from, to)));
                        return ToolResults.success(mapper, data);
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification setConstant (McpJsonMapper mapper)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("qname", Schemas.string("Constant qualified name."));
        properties.put("value", Schemas.string("New string value."));

        return spec(
                "set_constant",
                "Set constant",
                "Set a process-global Audiveris constant value.",
                Schemas.object(properties, "qname", "value"),
                annotations("Set constant", false, true, true),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String qname = requiredString(args, "qname");
                        final String value = requiredString(args, "value");
                        final Constant<?> constant = find(qname);
                        final String oldValue = constant.getStringValue();
                        constant.setStringValue(value);

                        final Map<String, Object> data = new LinkedHashMap<>();
                        data.put("constant", constantSummary(constant));
                        data.put("old_value", oldValue);
                        return ToolResults.success(mapper, data);
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static SyncToolSpecification resetConstant (McpJsonMapper mapper)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("qname", Schemas.string("Constant qualified name."));

        return spec(
                "reset_constant",
                "Reset constant",
                "Reset a constant to its source/default value.",
                Schemas.object(properties, "qname"),
                annotations("Reset constant", false, true, true),
                (exchange, request) -> {
                    final Map<String, Object> args = arguments(request);

                    try {
                        final String qname = requiredString(args, "qname");
                        final Constant<?> constant = find(qname);
                        constant.resetToSource();
                        return ToolResults.success(mapper, constantSummary(constant));
                    } catch (Throwable ex) {
                        return error(mapper, ex);
                    }
                });
    }

    private static Constant<?> find (String qname)
    {
        final Constant<?> constant = all().get(qname);

        if (constant == null) {
            throw new NoSuchElementException("Unknown constant: " + qname);
        }

        return constant;
    }

    private static Map<String, Object> constantSummary (Constant<?> constant)
    {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("qname", constant.getQualifiedName());
        data.put("type", constant.getClass().getSimpleName());
        data.put("value", constant.getStringValue());
        data.put("source_value", constant.getSourceString());
        data.put("unit", constant.getQuantityUnit());
        data.put("description", constant.getDescription());
        return data;
    }

    private static Map<String, Constant<?>> all ()
    {
        if (constants == null) {
            synchronized (LOAD_LOCK) {
                if (constants == null) {
                    constants = load();
                }
            }
        }

        return constants;
    }

    private static Map<String, Constant<?>> load ()
    {
        final Reflections reflections = new Reflections("org.audiveris.omr");
        final Set<Class<? extends ConstantSet>> classes = reflections.getSubTypesOf(
                ConstantSet.class);

        for (Class<? extends ConstantSet> clazz : classes) {
            if (Modifier.isAbstract(clazz.getModifiers()) || clazz.isInterface()) {
                continue;
            }

            final Class<?> enclosing = clazz.getEnclosingClass();

            if (enclosing == null) {
                continue;
            }

            // Loading UI classes headlessly would abort on BSAF initialization.
            if (enclosing.getPackageName().contains(".ui")) {
                continue;
            }

            try {
                Class.forName(enclosing.getName());
            } catch (Throwable ignored) {
                // Keep constants from other classes.
            }
        }

        final List<Constant<?>> found = new ArrayList<>();
        collect(UnitManager.getInstance().getRoot(), found);

        final Map<String, Constant<?>> map = new TreeMap<>();
        for (Constant<?> constant : found) {
            final String qname = constant.getQualifiedName();

            if ((qname != null) && !SENSITIVE.contains(qname)) {
                map.put(qname, constant);
            }
        }

        return map;
    }

    private static void collect (PackageNode node,
                                 List<Constant<?>> found)
    {
        for (int i = 0; i < node.getChildCount(); i++) {
            final Object child = node.getChild(i);

            if (child instanceof PackageNode packageNode) {
                collect(packageNode, found);
            } else if (child instanceof UnitNode unitNode) {
                final ConstantSet set = unitNode.getConstantSet();

                if (set != null) {
                    for (int j = 0; j < set.size(); j++) {
                        final Constant<?> constant = set.getConstant(j);

                        if (constant != null) {
                            found.add(constant);
                        }
                    }
                }
            }
        }
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
