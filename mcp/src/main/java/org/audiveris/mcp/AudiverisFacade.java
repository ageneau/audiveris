//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                      A u d i v e r i s F a c a d e                             //
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

import org.audiveris.omr.WellKnowns;
import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.glyph.ShapeSet;
import org.audiveris.omr.sheet.symbol.InterFactory;
import org.audiveris.omr.sig.relation.Relation;
import org.audiveris.omr.step.OmrStep;
import org.audiveris.omr.text.tesseract.TesseractOCR;

import org.reflections.Reflections;

import org.slf4j.LoggerFactory;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only access to Audiveris metadata used by MCP diagnostic tools.
 */
public class AudiverisFacade
{
    private static final String INTERFACE_VERSION = "1.0";

    private static final String SERVER_VERSION = "1.0.0";

    private static final String[] CATEGORIES = {
            "heads",
            "rests",
            "clefs",
            "keys",
            "times",
            "notes",
            "text",
            "dynamics",
            "ornaments",
            "articulations",
            "barlines",
            "beams",
            "curves",
            "other"
    };

    private static final Map<Shape, Boolean> MANUAL_SUPPORT = new ConcurrentHashMap<>();

    public Map<String, Object> version ()
    {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("interface_version", INTERFACE_VERSION);
        data.put("audiveris", WellKnowns.TOOL_REF);
        data.put("audiveris_build", WellKnowns.TOOL_BUILD);
        data.put("mcp_server", SERVER_VERSION);
        data.put("java", System.getProperty("java.version"));
        data.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        data.put("architecture", System.getProperty("os.arch"));
        data.put("ocr_engine", ocrIdentity());
        return data;
    }

    public List<Map<String, Object>> steps ()
    {
        final List<Map<String, Object>> steps = new ArrayList<>();
        final OmrStep first = OmrStep.first();
        final OmrStep last = OmrStep.last();

        for (OmrStep step : OmrStep.values()) {
            final Map<String, Object> data = new LinkedHashMap<>();
            final String tip = step.getTip() != null ? step.getTip() : "";
            String description;

            try {
                description = step.getDescription();
            } catch (java.util.MissingResourceException ex) {
                // Audiveris only ships '.toolTipText' entries for some steps.
                description = tip.isBlank() ? step.name() : tip;
            }

            data.put("name", step.name());
            data.put("description", description);
            data.put("tip", tip);
            data.put("parallel", step.isParallel());
            data.put("first", step == first);
            data.put("last", step == last);
            steps.add(data);
        }

        return steps;
    }

    public Map<String, Object> ocrLanguages ()
    {
        final Map<String, Object> data = new LinkedHashMap<>();
        final SortedSet<String> installed = new TreeSet<>();

        try {
            installed.addAll(TesseractOCR.getInstance().getSupportedLanguages());
        } catch (Throwable ex) {
            // The caller reports the OCR failure as a warning.
        }

        String defaultLanguage = null;
        if (installed.contains("eng")) {
            defaultLanguage = "eng";
        } else if (!installed.isEmpty()) {
            defaultLanguage = installed.first();
        }

        data.put("installed", new ArrayList<>(installed));
        data.put("default", defaultLanguage);
        return data;
    }

    public List<Map<String, Object>> shapes (String category,
                                             boolean manualOnly)
    {
        final String normalized = normalizeCategory(category);
        final List<Map<String, Object>> shapes = new ArrayList<>();

        for (Shape shape : Shape.values()) {
            final boolean manual = isManual(shape);

            if (manualOnly && !manual) {
                continue;
            }

            final List<String> categories = categoriesOf(shape);

            if ((normalized != null) && !categories.contains(normalized)) {
                continue;
            }

            final Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", shape.name());
            data.put("categories", categories);
            data.put("manual_add", manual);
            shapes.add(data);
        }

        return shapes;
    }

    public List<Map<String, Object>> relationTypes ()
    {
        final Reflections reflections = new Reflections("org.audiveris.omr.sig.relation");
        final Set<Class<? extends Relation>> classes = reflections.getSubTypesOf(Relation.class);
        final Set<String> names = new TreeSet<>();

        for (Class<? extends Relation> clazz : classes) {
            if (Modifier.isAbstract(clazz.getModifiers()) || clazz.isInterface()) {
                continue;
            }

            names.add(clazz.getSimpleName());
        }

        final List<Map<String, Object>> relations = new ArrayList<>();
        for (String name : names) {
            final Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", name);
            data.put("class", "org.audiveris.omr.sig.relation." + name);
            data.put("directed", true);
            relations.add(data);
        }

        return relations;
    }

    private static String normalizeCategory (String category)
    {
        if (category == null || category.isBlank()) {
            return null;
        }

        final String value = category.trim().toLowerCase();
        for (String known : CATEGORIES) {
            if (known.equals(value)) {
                return value;
            }
        }

        throw new IllegalArgumentException("Unknown shape category: " + category);
    }

    private static List<String> categoriesOf (Shape shape)
    {
        final List<String> categories = new ArrayList<>();

        if (ShapeSet.Heads.contains(shape)) {
            categories.add("heads");
        }
        if (ShapeSet.Rests.contains(shape)) {
            categories.add("rests");
        }
        if (ShapeSet.ClefsAndShifts.contains(shape)) {
            categories.add("clefs");
        }
        if (ShapeSet.Keys.contains(shape)) {
            categories.add("keys");
        }
        if (ShapeSet.Times.contains(shape)) {
            categories.add("times");
        }
        if (ShapeSet.Heads.contains(shape) || ShapeSet.CompoundNotes.contains(shape)) {
            categories.add("notes");
        }
        if (ShapeSet.Texts.contains(shape)) {
            categories.add("text");
        }
        if (ShapeSet.Dynamics.contains(shape)) {
            categories.add("dynamics");
        }
        if (ShapeSet.GraceAndOrnaments.contains(shape)) {
            categories.add("ornaments");
        }
        if (ShapeSet.Articulations.contains(shape)) {
            categories.add("articulations");
        }
        if (ShapeSet.Barlines.contains(shape)) {
            categories.add("barlines");
        }
        if (ShapeSet.BeamsEtc.contains(shape)) {
            categories.add("beams");
        }

        // Curves are intentionally not a ShapeSet in Audiveris.
        if (shape == Shape.SLUR_ABOVE
                || shape == Shape.SLUR_BELOW
                || shape == Shape.ENDING
                || shape == Shape.ENDING_WRL) {
            categories.add("curves");
        }

        if (categories.isEmpty()) {
            categories.add("other");
        }

        return categories;
    }

    private static boolean isManual (Shape shape)
    {
        return MANUAL_SUPPORT.computeIfAbsent(shape, AudiverisFacade::computeManual);
    }

    private static boolean computeManual (Shape shape)
    {
        // InterFactory logs a warning for unsupported shapes; keep the tool quiet.
        final ch.qos.logback.classic.Logger factoryLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
                        "org.audiveris.omr.sheet.symbol.InterFactory");
        final ch.qos.logback.classic.Level oldLevel = factoryLogger.getLevel();
        factoryLogger.setLevel(ch.qos.logback.classic.Level.OFF);

        try {
            // InterFactory ignores the sheet for manual creation.
            return InterFactory.createManual(shape, null) != null;
        } catch (Throwable ex) {
            return false;
        } finally {
            factoryLogger.setLevel(oldLevel);
        }
    }

    private static String ocrIdentity ()
    {
        try {
            return TesseractOCR.getInstance().identify();
        } catch (Throwable ex) {
            return "unavailable";
        }
    }
}
