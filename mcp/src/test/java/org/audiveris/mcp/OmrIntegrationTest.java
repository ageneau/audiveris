//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                   O m r I n t e g r a t i o n T e s t                         //
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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.score.Score;
import org.audiveris.omr.score.ScoreExporter;
import org.audiveris.omr.sheet.Book;
import org.audiveris.omr.sheet.SheetStub;
import org.audiveris.omr.sig.SIGraph;
import org.audiveris.omr.sig.inter.AbstractPitchedInter;
import org.audiveris.omr.sig.inter.HeadChordInter;
import org.audiveris.omr.sig.inter.HeadInter;
import org.audiveris.omr.sig.inter.SlurInter;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.sig.relation.Relation;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;

public class OmrIntegrationTest
{
    private static Path workspace;

    private static Path bookFile;

    private static OmrService service;

    @BeforeClass
    public static void setup ()
        throws Exception
    {
        Path cwd = Path.of("").toAbsolutePath();
        Path repo = Files.exists(cwd.resolve("data/examples/cucaracha.png"))
                ? cwd
                : cwd.getParent();
        final Path input = repo.resolve("data/examples/cucaracha.png");

        Assume.assumeTrue("Missing sample " + input, Files.exists(input));

        HeadlessBootstrap.initialize(null);
        workspace = Files.createTempDirectory("audiveris-mcp-it-");
        service = new OmrService(workspace);

        final OmrService.OpenResult open = service.openInput(input.toString(), null, false);
        final BookSession session = open.session();
        final List<SheetStub> stubs = Book.getValidStubs(session.book.getStubs());
        final List<Score> scores = new ArrayList<>();

        assertTrue(session.book.transcribe(stubs, scores, true));
        assertFalse(scores.isEmpty());

        bookFile = workspace.resolve("books").resolve("cucaracha.omr");
        Files.createDirectories(bookFile.getParent());
        service.save(session, bookFile.toString());
        service.close(session.id, true);
    }

    @AfterClass
    public static void cleanup ()
        throws Exception
    {
        if (service != null) {
            service.closeAll();
        }

        if (workspace != null) {
            try (var paths = Files.walk(workspace)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    public void transcribeAndExport ()
        throws Exception
    {
        final BookSession session = service.openBook(bookFile.toString()).session();

        try {
            final List<Score> scores = session.book.getScores();
            assertFalse(scores.isEmpty());

            final Path output = workspace.resolve("cucaracha.mxl");
            new ScoreExporter(scores.get(0)).export(
                    output,
                    "cucaracha",
                    false,
                    true);

            assertTrue(Files.size(output) > 0);

            try (ZipFile zip = new ZipFile(output.toFile())) {
                assertNotNull(zip.getEntry("META-INF/container.xml"));

                final ZipEntry root = zip.stream()
                        .filter(entry -> entry.getName().endsWith(".xml"))
                        .filter(entry -> !entry.getName().startsWith("META-INF/"))
                        .findFirst()
                        .orElseThrow();

                try (InputStream in = zip.getInputStream(root)) {
                    final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    factory.setFeature(
                            "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                            false);
                    factory.setFeature("http://xml.org/sax/features/validation", false);

                    final var document = factory.newDocumentBuilder().parse(in);
                    assertEquals("score-partwise", document.getDocumentElement().getNodeName());
                    assertTrue(document.getElementsByTagName("measure").getLength() > 0);
                    assertTrue(document.getElementsByTagName("note").getLength() > 0);
                }
            }
        } finally {
            service.close(session.id, false);
        }
    }

    @Test
    public void mergeSplitAndMergeSystems ()
        throws Exception
    {
        final BookSession session = service.openBook(bookFile.toString()).session();

        try {
            final OmrEditService edit = new OmrEditService(session, 1);
            final SIGraph sig = session.book.getStub(1).getSheet().getSystems().get(0).getSig();
            final List<HeadChordInter> chords = sig.vertexSet().stream()
                    .filter(inter -> inter instanceof HeadChordInter)
                    .map(inter -> (HeadChordInter) inter)
                    .limit(2)
                    .toList();

            assertEquals(2, chords.size());

            final HeadChordInter merged = edit.mergeChords(chords, true);
            assertNotNull(merged);

            final List<HeadChordInter> split = edit.splitChord(merged);
            assertEquals(2, split.size());

            final var sheet = session.book.getStub(1).getSheet();
            final int systemCount = sheet.getSystems().size();
            edit.mergeSystem(sheet.getSystems().get(0));
            assertTrue(sheet.getSystems().size() < systemCount);

            edit.undo(1);
            assertEquals(systemCount, sheet.getSystems().size());
        } finally {
            service.close(session.id, false);
        }
    }

    @Test
    public void updateAndToggleTie ()
        throws Exception
    {
        final BookSession session = service.openBook(bookFile.toString()).session();

        try {
            final OmrEditService edit = new OmrEditService(session, 1);
            final SIGraph sig = session.book.getStub(1).getSheet().getSystems().get(0).getSig();
            final AbstractPitchedInter head = sig.vertexSet().stream()
                    .filter(inter -> inter instanceof HeadInter)
                    .map(inter -> (AbstractPitchedInter) inter)
                    .findFirst()
                    .orElseThrow();

            edit.updateSymbol(head, Map.of("pitch", 1.23));
            assertEquals(1.23, head.getPitch(), 0.0001);

            final SlurInter slur = session.book.getStub(1).getSheet().getSystems().stream()
                    .flatMap(system -> system.getSig().vertexSet().stream())
                    .filter(inter -> inter instanceof SlurInter)
                    .map(inter -> (SlurInter) inter)
                    .findFirst()
                    .orElseThrow();

            final boolean before = slur.isTie();
            edit.toggleTie(slur);
            assertEquals(!before, slur.isTie());
            edit.toggleTie(slur);
            assertEquals(before, slur.isTie());

            final Map<String, Object> rhythm = edit.reprocessRhythm(1, null);
            assertEquals(Boolean.TRUE, rhythm.get("ok"));
        } finally {
            service.close(session.id, false);
        }
    }

    @Test
    public void editLinkUndoRedo ()
        throws Exception
    {
        final BookSession session = service.openBook(bookFile.toString()).session();

        try {
            final OmrEditService edit = new OmrEditService(session, 1);
            final Inter added = edit.addSymbol(
                    Shape.NOTEHEAD_BLACK,
                    new Point(1500, 400),
                    1,
                    true);
            assertNotNull(added);

            final Rectangle initialBounds = added.getBounds();
            edit.moveSymbol(added, new Point(1540, 410), true, true);
            assertNotEquals(initialBounds, added.getBounds());

            final OmrEditService.RemovalResult removal = edit.removeSymbols(
                    List.of(added),
                    false);
            assertTrue(removal.removed().contains(added));
            assertNull(session.book.getStub(1).getSheet().getInterIndex().getEntity(added.getId()));

            edit.undo(2);
            assertNotNull(session.book.getStub(1).getSheet().getInterIndex().getEntity(added.getId()));
            assertEquals(0, edit.history().position());

            edit.redo(1);
            assertEquals(1, edit.history().position());

            final SIGraph sig = session.book.getStub(1).getSheet().getSystems().get(0).getSig();
            final Inter other = sig.vertexSet().stream()
                    .filter(inter -> inter instanceof HeadInter)
                    .filter(inter -> inter.getId() != added.getId())
                    .findFirst()
                    .orElseThrow();

            final Relation relation = edit.linkSymbols(
                    added,
                    other,
                    "NoExclusion");
            assertNotNull(relation);

            final List<Relation> removed = edit.unlinkSymbols(
                    added,
                    other,
                    "NoExclusion",
                    false);
            assertFalse(removed.isEmpty());
        } finally {
            service.close(session.id, false);
        }
    }
}
