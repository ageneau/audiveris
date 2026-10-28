//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                     J o b L o g A p p e n d e r                               //
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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import org.slf4j.LoggerFactory;

import org.audiveris.omr.step.OmrStep;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Captures the logs produced while one job is running.
 */
public class JobLogAppender
        extends AppenderBase<ILoggingEvent>
{
    private static final Set<String> STEP_NAMES = Arrays.stream(OmrStep.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    private static final Pattern REACHING = Pattern.compile("Book reaching ([A-Z_]+)");

    private final OmrJob job;

    private final Logger root = (Logger) LoggerFactory.getLogger(
            ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);

    public JobLogAppender (OmrJob job)
    {
        this.job = job;
        setName("job-" + job.id);
    }

    public void attach ()
    {
        start();
        root.addAppender(this);
    }

    public void detach ()
    {
        root.detachAppender(this);
        stop();
    }

    @Override
    protected void append (ILoggingEvent event)
    {
        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", java.time.Instant.ofEpochMilli(event.getTimeStamp()).toString());
        entry.put("level", event.getLevel().toString());
        entry.put("logger", event.getLoggerName());
        entry.put("message", event.getFormattedMessage());
        job.addLog(entry);

        final String message = event.getFormattedMessage().trim();

        if (STEP_NAMES.contains(message)) {
            job.setCurrentStep(message);
        } else {
            final Matcher matcher = REACHING.matcher(message);

            if (matcher.find() && STEP_NAMES.contains(matcher.group(1))) {
                job.setCurrentStep(matcher.group(1));
            }
        }

        final Map<String, String> mdc = event.getMDCPropertyMap();

        if (mdc != null) {
            final String sheet = mdc.get("SHEET");

            if (sheet != null) {
                final String digits = sheet.replaceAll("[^0-9]", "");

                if (!digits.isEmpty()) {
                    try {
                        job.setCurrentSheet(Integer.parseInt(digits));
                    } catch (NumberFormatException ignored) {
                        // Ignore malformed context.
                    }
                }
            }
        }
    }
}
