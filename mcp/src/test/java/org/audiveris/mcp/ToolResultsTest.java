//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                     T o o l R e s u l t s T e s t                             //
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import org.junit.Test;

import java.util.List;
import java.util.Map;

public class ToolResultsTest
{
    @Test
    public void success ()
    {
        final CallToolResult result = ToolResults.success(
                McpJsonDefaults.getMapper(),
                Map.of("answer", 42));

        assertFalse(result.isError());
        assertEquals(1, result.content().size());

        final Map<?, ?> structured = (Map<?, ?>) result.structuredContent();
        assertEquals(Boolean.TRUE, structured.get("ok"));
        assertEquals(List.of(), structured.get("warnings"));
    }

    @Test
    public void error ()
    {
        final CallToolResult result = ToolResults.error(
                McpJsonDefaults.getMapper(),
                "NOT_FOUND",
                "missing");

        assertTrue(result.isError());

        final Map<?, ?> structured = (Map<?, ?>) result.structuredContent();
        assertEquals(Boolean.FALSE, structured.get("ok"));

        final Map<?, ?> error = (Map<?, ?>) structured.get("error");
        assertEquals("NOT_FOUND", error.get("code"));
        assertEquals("missing", error.get("message"));
    }
}
