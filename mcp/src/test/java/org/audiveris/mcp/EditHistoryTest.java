//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                     E d i t H i s t o r y T e s t                             //
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.audiveris.omr.sig.ui.UITaskList;

import org.junit.Test;

import java.util.List;

public class EditHistoryTest
{
    @Test
    public void undoRedo ()
    {
        final EditHistory history = new EditHistory();
        final UITaskList tasks = new UITaskList();

        assertFalse(history.canUndo());
        assertFalse(history.canRedo());
        assertNull(history.undo());

        history.add(tasks, "add", "add #1", List.of(1));
        assertTrue(history.canUndo());
        assertEquals(0, history.position());

        final EditHistory.Entry entry = history.undo();
        assertEquals("add", entry.op());
        assertFalse(history.canUndo());
        assertTrue(history.canRedo());

        history.redo();
        assertTrue(history.canUndo());
        assertFalse(history.canRedo());
        assertEquals(1, history.entries().size());
    }

    @Test
    public void newEditClearsRedo ()
    {
        final EditHistory history = new EditHistory();
        history.add(new UITaskList(), "add", "add #1", List.of(1));
        history.undo();
        assertTrue(history.canRedo());

        history.add(new UITaskList(), "remove", "remove #1", List.of(1));
        assertFalse(history.canRedo());
    }
}
