//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                           O m r J o b                                         //
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

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One asynchronous MCP operation.
 */
public class OmrJob
{
    public enum State
    {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    private static final int MAX_LOG_ENTRIES = 500;

    public final String id;

    public final String type;

    public final String bookId;

    public final Instant createdAt = Instant.now();

    private final Map<String, Object> progress = new LinkedHashMap<>();

    private final Deque<Map<String, Object>> logs = new ArrayDeque<>();

    private State state = State.QUEUED;

    private Instant startedAt;

    private Instant endedAt;

    private boolean cancelRequested;

    private Map<String, Object> result;

    private Map<String, Object> error;

    OmrJob (String id,
            String type,
            String bookId,
            Map<String, Object> initialProgress)
    {
        this.id = id;
        this.type = type;
        this.bookId = bookId;

        progress.put("current_step", null);
        progress.put("current_sheet", null);
        progress.put("sheets_done", 0);
        progress.put("sheets_total", 0);
        progress.put("percent", 0);

        if (initialProgress != null) {
            progress.putAll(initialProgress);
        }
    }

    public synchronized State getState ()
    {
        return state;
    }

    public synchronized boolean isTerminal ()
    {
        return state == State.SUCCEEDED
                || state == State.FAILED
                || state == State.CANCELLED;
    }

    public synchronized boolean isCancelRequested ()
    {
        return cancelRequested;
    }

    public synchronized void requestCancel ()
    {
        cancelRequested = true;
    }

    synchronized void start ()
    {
        state = State.RUNNING;
        startedAt = Instant.now();
    }

    synchronized void succeed (Map<String, Object> result)
    {
        this.result = result;
        state = State.SUCCEEDED;
        progress.put("sheets_done", progress.get("sheets_total"));
        progress.put("percent", 100);
    }

    synchronized void fail (String code,
                            String message,
                            Map<String, Object> details)
    {
        final Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("code", code);
        failure.put("message", message);

        if (details != null) {
            failure.put("details", details);
        }

        error = failure;
        state = State.FAILED;
    }

    synchronized void cancelled ()
    {
        state = State.CANCELLED;
    }

    synchronized void finish ()
    {
        endedAt = Instant.now();
    }

    synchronized void setCurrentStep (String step)
    {
        progress.put("current_step", step);
    }

    synchronized void setCurrentSheet (Integer sheet)
    {
        progress.put("current_sheet", sheet);
    }

    synchronized void addLog (Map<String, Object> entry)
    {
        logs.addLast(entry);

        while (logs.size() > MAX_LOG_ENTRIES) {
            logs.removeFirst();
        }
    }

    public synchronized Map<String, Object> toMap (int logLines)
    {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("job_id", id);
        data.put("type", type);
        data.put("state", state.name());
        data.put("book_id", bookId);
        data.put("created_at", createdAt.toString());
        data.put("started_at", startedAt == null ? null : startedAt.toString());
        data.put("ended_at", endedAt == null ? null : endedAt.toString());
        data.put("cancel_requested", cancelRequested);
        data.put("progress", new LinkedHashMap<>(progress));
        data.put("result", result);
        data.put("error", error);
        data.put("log_tail", lastLogs(logLines));
        return data;
    }

    public synchronized List<Map<String, Object>> lastLogs (int max)
    {
        final int count = Math.max(0, Math.min(max, logs.size()));
        final List<Map<String, Object>> list = new ArrayList<>(count);

        if (count > 0) {
            final int skip = logs.size() - count;
            int index = 0;

            for (Map<String, Object> entry : logs) {
                if (index++ >= skip) {
                    list.add(entry);
                }
            }
        }

        return list;
    }
}
