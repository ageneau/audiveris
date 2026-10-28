//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                          S c h e m a s                                         //
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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small helpers to build MCP JSON schemas without an extra dependency.
 */
public abstract class Schemas
{
    private Schemas ()
    {
    }

    public static Map<String, Object> empty ()
    {
        final Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        return schema;
    }

    public static Map<String, Object> object (Map<String, Object> properties,
                                              String... required)
    {
        final Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);

        if (required.length > 0) {
            schema.put("required", Arrays.asList(required));
        }

        return schema;
    }

    public static Map<String, Object> string (String description)
    {
        return typed("string", description);
    }

    public static Map<String, Object> integer (String description)
    {
        return typed("integer", description);
    }

    public static Map<String, Object> bool (String description)
    {
        return typed("boolean", description);
    }

    public static Map<String, Object> number (String description)
    {
        return typed("number", description);
    }

    public static Map<String, Object> array (Map<String, Object> items,
                                             String description)
    {
        final Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", items);
        schema.put("description", description);
        return schema;
    }

    public static Map<String, Object> sheetSpec (String description)
    {
        final Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", java.util.List.of("string", "integer", "array"));
        schema.put("items", Map.of("type", "integer"));
        schema.put("description", description);
        return schema;
    }

    public static Map<String, Object> stringEnum (java.util.List<String> values,
                                                  String description)
    {
        final Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("enum", values);
        schema.put("description", description);
        return schema;
    }

    private static Map<String, Object> typed (String type,
                                              String description)
    {
        final Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        schema.put("description", description);
        return schema;
    }
}
