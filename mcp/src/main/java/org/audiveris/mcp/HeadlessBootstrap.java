//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                  H e a d l e s s B o o t s t r a p                             //
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

import org.audiveris.omr.OMR;
import org.audiveris.omr.WellKnowns;
import org.audiveris.omr.sheet.BookManager;
import org.audiveris.omr.text.tesseract.Languages;
import org.audiveris.omr.util.OmrExecutors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Initializes the Audiveris engine without starting the GUI.
 */
public abstract class HeadlessBootstrap
{
    private static final Logger logger = LoggerFactory.getLogger(HeadlessBootstrap.class);

    private static boolean initialized;

    private static volatile Throwable ocrFailure;

    private HeadlessBootstrap ()
    {
    }

    public static synchronized void initialize (Path workspace)
    {
        if (initialized) {
            return;
        }

        // We need WellKnowns to be elaborated before anything else.
        WellKnowns.ensureLoaded();

        if (workspace != null) {
            try {
                Files.createDirectories(workspace);
            } catch (Exception ex) {
                logger.warn("Could not create workspace {}: {}", workspace, ex.toString());
            }
        }

        OmrExecutors.restart();
        OMR.engine = BookManager.getInstance();

        try {
            Languages.getInstance().checkSupport();
        } catch (Throwable ex) {
            ocrFailure = ex;
            logger.warn("OCR is not available: {}", ex.toString());
        }

        initialized = true;
        logger.info("Audiveris MCP headless bootstrap complete");
    }

    public static Throwable getOcrFailure ()
    {
        return ocrFailure;
    }
}
