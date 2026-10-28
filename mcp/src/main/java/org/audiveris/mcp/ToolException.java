//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                       T o o l E x c e p t i o n                                //
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

import java.util.Map;

/**
 * A failure with an MCP error code.
 */
public class ToolException
        extends RuntimeException
{
    public final String code;

    public final Map<String, Object> details;

    public ToolException (String code,
                          String message)
    {
        this(code, message, null);
    }

    public ToolException (String code,
                          String message,
                          Map<String, Object> details)
    {
        super(message);
        this.code = code;
        this.details = details;
    }
}
