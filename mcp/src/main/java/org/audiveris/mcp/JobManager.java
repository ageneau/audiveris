//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                        J o b M a n a g e r                                    //
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

/**
 * Runs engine operations on one background thread.
 */
public class JobManager
        implements AutoCloseable
{
    @FunctionalInterface
    public interface JobWork
    {
        Map<String, Object> run (OmrJob job)
            throws Exception;
    }

    private final OmrService service;

    private final Map<String, OmrJob> jobs = new ConcurrentHashMap<>();

    private final Map<String, String> activeByBook = new ConcurrentHashMap<>();

    private final ExecutorService executor;

    public JobManager (OmrService service)
    {
        this.service = service;
        final ThreadFactory factory = runnable -> {
            final Thread thread = new Thread(runnable, "audiveris-mcp-job");
            thread.setDaemon(true);
            return thread;
        };
        executor = Executors.newSingleThreadExecutor(factory);
    }

    public OmrJob submit (String type,
                          BookSession session,
                          Map<String, Object> initialProgress,
                          JobWork work)
    {
        final OmrJob job = new OmrJob(
                UUID.randomUUID().toString(),
                type,
                session.id,
                initialProgress);

        final String existing = activeByBook.putIfAbsent(session.id, job.id);

        if (existing != null) {
            throw new IllegalStateException("Book already has a running job: " + existing);
        }

        jobs.put(job.id, job);

        try {
            executor.submit(() -> runJob(job, session, work));
        } catch (RejectedExecutionException ex) {
            activeByBook.remove(session.id, job.id);
            job.fail("INTERNAL", "Job executor is closed", null);
            job.finish();
            throw ex;
        }

        return job;
    }

    public OmrJob get (String id)
    {
        final OmrJob job = jobs.get(id);

        if (job == null) {
            throw new NoSuchElementException("Unknown job_id: " + id);
        }

        return job;
    }

    public List<OmrJob> list (String bookId,
                              String state,
                              int limit)
    {
        final List<OmrJob> list = new ArrayList<>();

        for (OmrJob job : jobs.values()) {
            if (bookId != null && !bookId.equals(job.bookId)) {
                continue;
            }

            if (state != null && !state.equals(job.getState().name())) {
                continue;
            }

            list.add(job);
        }

        list.sort(Comparator.comparing((OmrJob job) -> job.createdAt).reversed());

        if (limit > 0 && list.size() > limit) {
            return new ArrayList<>(list.subList(0, limit));
        }

        return list;
    }

    public OmrJob latestForBook (String bookId)
    {
        final List<OmrJob> list = list(bookId, null, 1);
        return list.isEmpty() ? null : list.get(0);
    }

    public OmrJob cancel (String id)
    {
        final OmrJob job = get(id);
        job.requestCancel();

        try {
            service.get(job.bookId).book.setPauseRequired(true);
        } catch (NoSuchElementException ignored) {
            // Book already closed.
        }

        return job;
    }

    private void runJob (OmrJob job,
                         BookSession session,
                         JobWork work)
    {
        final JobLogAppender appender = new JobLogAppender(job);

        job.start();

        if (job.isCancelRequested()) {
            job.cancelled();
            job.finish();
            activeByBook.remove(session.id, job.id);
            return;
        }

        session.book.setPauseRequired(false);
        session.lock.lock();

        try {
            appender.attach();
            final Map<String, Object> result = work.run(job);

            if (job.isCancelRequested()) {
                job.cancelled();
            } else {
                job.succeed(result);
            }
        } catch (ToolException ex) {
            if ("CANCELLED".equals(ex.code) || job.isCancelRequested()) {
                job.cancelled();
            } else {
                job.fail(ex.code, ex.getMessage(), ex.details);
            }
        } catch (Throwable ex) {
            if (job.isCancelRequested()) {
                job.cancelled();
            } else {
                job.fail("INTERNAL", ex.toString(), null);
            }
        } finally {
            appender.detach();
            session.book.setPauseRequired(false);
            session.lock.unlock();
            job.finish();
            activeByBook.remove(session.id, job.id);
        }
    }

    @Override
    public void close ()
    {
        for (String jobId : new ArrayList<>(activeByBook.values())) {
            try {
                cancel(jobId);
            } catch (Exception ignored) {
                // Best effort.
            }
        }

        executor.shutdownNow();
    }
}
