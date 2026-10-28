//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                        T o o l R e s u l t s                                   //
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
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the Audiveris MCP result envelope.
 */
public abstract class ToolResults
{
    private ToolResults ()
    {
    }

    public static CallToolResult success (McpJsonMapper mapper,
                                          Object data)
    {
        return success(mapper, data, List.of());
    }

    public static CallToolResult success (McpJsonMapper mapper,
                                          Object data,
                                          List<?> warnings)
    {
        final Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ok", true);
        envelope.put("data", data);
        envelope.put("warnings", warnings);

        return result(mapper, envelope, false);
    }

    public static CallToolResult error (McpJsonMapper mapper,
                                        String code,
                                        String message)
    {
        return error(mapper, code, message, null);
    }

    public static CallToolResult error (McpJsonMapper mapper,
                                        String code,
                                        String message,
                                        Map<String, Object> details)
    {
        final Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);

        if (details != null) {
            error.put("details", details);
        }

        final Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ok", false);
        envelope.put("error", error);

        return result(mapper, envelope, true);
    }

    private static CallToolResult result (McpJsonMapper mapper,
                                          Map<String, Object> envelope,
                                          boolean isError)
    {
        try {
            final String text = mapper.writeValueAsString(envelope);

            return CallToolResult.builder()
                    .content(List.of(TextContent.builder(text).build()))
                    .structuredContent(envelope)
                    .isError(isError)
                    .build();
        } catch (Exception ex) {
            final String fallback = "{\"ok\":false,\"error\":{\"code\":\"INTERNAL\","
                    + "\"message\":\"Could not serialize tool result\"}}";

            return CallToolResult.builder()
                    .content(List.of(TextContent.builder(fallback).build()))
                    .structuredContent(Map.of("ok", false))
                    .isError(true)
                    .build();
        }
    }
}
