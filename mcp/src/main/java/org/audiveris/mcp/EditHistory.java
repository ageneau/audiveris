//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                        E d i t H i s t o r y                                  //
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

import org.audiveris.omr.sig.ui.UITaskList;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Undo/redo history for one sheet.
 */
public class EditHistory
{
    public record Entry(
            UITaskList tasks,
            String op,
            String summary,
            List<Integer> interIds,
            Instant at)
    {
    }

    private final List<Entry> done = new ArrayList<>();

    private final List<Entry> undone = new ArrayList<>();

    public synchronized void add (UITaskList tasks,
                                  String op,
                                  String summary,
                                  List<Integer> interIds)
    {
        done.add(new Entry(
                tasks,
                op,
                summary,
                List.copyOf(interIds),
                Instant.now()));
        undone.clear();
    }

    public synchronized Entry undo ()
    {
        if (done.isEmpty()) {
            return null;
        }

        final Entry entry = done.remove(done.size() - 1);
        undone.add(entry);
        return entry;
    }

    public synchronized Entry redo ()
    {
        if (undone.isEmpty()) {
            return null;
        }

        final Entry entry = undone.remove(undone.size() - 1);
        done.add(entry);
        return entry;
    }

    public synchronized boolean canUndo ()
    {
        return !done.isEmpty();
    }

    public synchronized boolean canRedo ()
    {
        return !undone.isEmpty();
    }

    public synchronized int position ()
    {
        return done.size() - 1;
    }

    public synchronized List<Entry> entries ()
    {
        return Collections.unmodifiableList(new ArrayList<>(done));
    }
}
