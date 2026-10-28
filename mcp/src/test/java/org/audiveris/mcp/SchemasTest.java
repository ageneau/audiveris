//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                       S c h e m a s T e s t                                   //
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

import org.junit.Test;

import java.util.Map;

public class SchemasTest
{
    @Test
    public void empty ()
    {
        final Map<String, Object> schema = Schemas.empty();
        assertEquals("object", schema.get("type"));
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
        assertFalse(schema.containsKey("properties"));
    }

    @Test
    public void object ()
    {
        final Map<String, Object> schema = Schemas.object(
                Map.of("path", Schemas.string("Path")),
                "path");

        assertEquals("object", schema.get("type"));
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
        assertEquals(java.util.List.of("path"), schema.get("required"));
    }
}
