//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                      O m r E d i t S e r v i c e                               //
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

import org.audiveris.omr.glyph.Glyph;
import org.audiveris.omr.glyph.GlyphFactory;
import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.glyph.ShapeSet;
import org.audiveris.omr.math.AreaUtil;
import org.audiveris.omr.math.LineUtil;
import org.audiveris.omr.math.PointUtil;
import org.audiveris.omr.score.Page;
import org.audiveris.omr.score.TimeRational;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.SheetStub;
import org.audiveris.omr.sheet.Staff;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.sheet.rhythm.MeasureStack;
import org.audiveris.omr.sheet.symbol.InterFactory;
import org.audiveris.omr.sig.SIGraph;
import org.audiveris.omr.sig.inter.AbstractChordInter;
import org.audiveris.omr.sig.inter.AbstractNumberInter;
import org.audiveris.omr.sig.inter.AbstractPitchedInter;
import org.audiveris.omr.sig.inter.BarConnectorInter;
import org.audiveris.omr.sig.inter.BarlineInter;
import org.audiveris.omr.sig.inter.HeadChordInter;
import org.audiveris.omr.sig.inter.HeadInter;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.sig.inter.Inters;
import org.audiveris.omr.sig.inter.LyricItemInter;
import org.audiveris.omr.sig.inter.MetronomeInter;
import org.audiveris.omr.sig.inter.SentenceInter;
import org.audiveris.omr.sig.inter.SlurInter;
import org.audiveris.omr.sig.inter.StemInter;
import org.audiveris.omr.sig.inter.TimeCustomInter;
import org.audiveris.omr.sig.inter.WordInter;
import org.audiveris.omr.sig.relation.BarConnectionRelation;
import org.audiveris.omr.sig.relation.BeamStemRelation;
import org.audiveris.omr.sig.relation.Containment;
import org.audiveris.omr.sig.relation.FlagStemRelation;
import org.audiveris.omr.sig.relation.HeadStemRelation;
import org.audiveris.omr.sig.relation.Link;
import org.audiveris.omr.sig.relation.Relation;
import org.audiveris.omr.sig.relation.Support;
import org.audiveris.omr.sig.ui.AdditionTask;
import org.audiveris.omr.sig.ui.ChordVoiceIdTask;
import org.audiveris.omr.sig.ui.InterTask;
import org.audiveris.omr.sig.ui.LinkTask;
import org.audiveris.omr.sig.ui.NumberValueTask;
import org.audiveris.omr.sig.ui.PageTask;
import org.audiveris.omr.sig.ui.RelationTask;
import org.audiveris.omr.sig.ui.SentenceRoleTask;
import org.audiveris.omr.sig.ui.StackTask;
import org.audiveris.omr.sig.ui.SystemMergeTask;
import org.audiveris.omr.sig.ui.TimeValueTask;
import org.audiveris.omr.sig.ui.TieTask;
import org.audiveris.omr.sig.ui.RemovalScenario;
import org.audiveris.omr.sig.ui.RemovalTask;
import org.audiveris.omr.sig.ui.UITask;
import org.audiveris.omr.sig.ui.UITask.OpKind;
import org.audiveris.omr.sig.ui.UITaskList;
import org.audiveris.omr.sig.ui.UnlinkTask;
import org.audiveris.omr.sig.ui.WordValueTask;
import org.audiveris.omr.step.OmrStep;
import org.audiveris.omr.text.TextRole;
import org.audiveris.omr.ui.symbol.MusicFamily;
import org.audiveris.omr.ui.symbol.MusicFont;
import org.audiveris.omr.ui.symbol.ShapeSymbol;
import org.audiveris.omr.util.Entities;
import org.audiveris.omr.util.HorizontalSide;
import org.audiveris.omr.util.WrappedBoolean;
import org.audiveris.omr.util.Wrapper;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Headless symbol edits, mirroring the safe core of {@code InterController}.
 */
public class OmrEditService
{
    public record RemovalResult(List<Inter> removed, boolean glyphLeft)
    {
    }

    private final BookSession session;

    private final SheetStub stub;

    private final Sheet sheet;

    private final EditHistory history;

    public OmrEditService (BookSession session,
                           int sheetNumber)
    {
        this.session = session;
        stub = session.book.getStub(sheetNumber);

        if (stub == null) {
            throw new NoSuchElementException(
                    "No sheet " + sheetNumber + " in book " + session.id);
        }

        sheet = stub.getSheet();

        if (sheet == null) {
            throw new NoSuchElementException("Sheet " + sheetNumber + " is not loaded");
        }

        if (stub.getLatestStep() == null) {
            throw new ToolException(
                    "INVALID_ARGUMENT",
                    "Sheet " + sheetNumber + " has not been processed yet");
        }

        if (!stub.isDone(OmrStep.GRID)) {
            throw new ToolException(
                    "INVALID_ARGUMENT",
                    "Sheet " + sheetNumber + " must reach GRID before editing");
        }

        history = session.getEditHistory(sheetNumber);
    }

    public EditHistory history ()
    {
        return history;
    }

    public SheetStub stub ()
    {
        return stub;
    }

    public Sheet sheet ()
    {
        return sheet;
    }

    public Inter findInter (int id)
    {
        final Inter inter = sheet.getInterIndex().getEntity(id);

        if ((inter == null) || inter.isRemoved()) {
            throw new NoSuchElementException("No inter " + id + " on sheet " + stub.getNumber());
        }

        return inter;
    }

    public Inter addSymbol (Shape shape,
                            Point point,
                            Integer staffId,
                            boolean link)
    {
        final Inter ghost = createGhost(shape, point, staffId);

        final UITaskList seq = new UITaskList();

        if (link) {
            final WrappedBoolean cancel = new WrappedBoolean(false);
            final Wrapper<Inter> toPublish = new Wrapper<>(null);
            final List<? extends UITask> tasks = ghost.preAdd(cancel, toPublish);

            if (cancel.isSet()) {
                throw new ToolException("INVALID_ARGUMENT", "Symbol addition was cancelled");
            }

            seq.addAll(tasks);
        } else {
            final SystemInfo system = ghost.getStaff().getSystem();
            seq.add(new AdditionTask(
                    system.getSig(),
                    ghost,
                    ghost.getBounds(),
                    List.of()));
        }

        apply(seq, OpKind.DO);
        history.add(
                seq,
                "add",
                "add " + shape + " #" + ghost.getId(),
                List.of(ghost.getId()));
        return ghost;
    }

    public RemovalResult removeSymbols (List<Inter> inters,
                                        boolean rebuildGlyphs)
    {
        if (rebuildGlyphs) {
            throw new ToolException(
                    "UNSUPPORTED",
                    "Glyph pixel rebuilding is not implemented in v1");
        }

        final UITaskList seq = new UITaskList();
        new RemovalScenario().populate(inters, seq);

        if (seq.isCancelled()) {
            throw new ToolException("INVALID_ARGUMENT", "Removal was cancelled");
        }

        final List<Inter> removed = new ArrayList<>(seq.getInters());
        boolean glyphLeft = false;

        for (Inter inter : removed) {
            if (inter.getGlyph() != null) {
                glyphLeft = true;
                break;
            }
        }

        apply(seq, OpKind.DO);
        history.add(
                seq,
                "remove",
                "remove " + ids(removed),
                ids(removed));
        return new RemovalResult(removed, glyphLeft);
    }

    public Inter moveSymbol (Inter inter,
                             Point point,
                             boolean snap,
                             boolean relink)
    {
        final SIGraph sig = inter.getSig();

        if (sig == null) {
            throw new ToolException("INVALID_ARGUMENT", "Inter is not in a SIG");
        }

        final Rectangle oldBounds = inter.getBounds();
        final Glyph oldGlyph = inter.getGlyph();
        final Rectangle newBounds = boundsFor(inter, point, snap);

        inter.setBounds(newBounds);

        if (inter.getGlyph() != null) {
            inter.setGlyph(null);
        }

        final Collection<Link> links;
        final Collection<Link> unlinks;

        if (relink) {
            links = inter.searchLinks(sig.getSystem());
            unlinks = inter.searchUnlinks(sig.getSystem(), links);
        } else {
            links = List.of();
            unlinks = List.of();
        }

        final UITaskList seq = new UITaskList();
        seq.add(new MoveTask(inter, oldBounds, newBounds, oldGlyph, links, unlinks));
        apply(seq, OpKind.DO);
        history.add(
                seq,
                "move",
                "move #" + inter.getId(),
                List.of(inter.getId()));
        return inter;
    }

    public Relation linkSymbols (Inter source,
                                 Inter target,
                                 String relationType)
    {
        final SIGraph sig = source.getSig();

        if ((sig == null) || (sig != target.getSig())) {
            throw new ToolException(
                    "INVALID_ARGUMENT",
                    "Both inters must belong to the same SIG");
        }

        final Relation relation = newRelation(relationType);

        if (sig.getRelation(source, target, relation.getClass()) != null) {
            throw new ToolException(
                    "CONFLICT",
                    "Relation already exists: " + relationType);
        }

        final UITaskList seq = new UITaskList();
        seq.add(new LinkTask(sig, source, target, relation));
        apply(seq, OpKind.DO);
        history.add(
                seq,
                "link",
                "link #" + source.getId() + " " + relationType + " #" + target.getId(),
                List.of(source.getId(), target.getId()));
        return relation;
    }

    public List<Relation> unlinkSymbols (Inter source,
                                         Inter target,
                                         String relationType,
                                         boolean all)
    {
        final SIGraph sig = source.getSig();

        if ((sig == null) || (sig != target.getSig())) {
            throw new ToolException(
                    "INVALID_ARGUMENT",
                    "Both inters must belong to the same SIG");
        }

        final List<Relation> relations = new ArrayList<>();
        final String simpleType = relationType.contains(".")
                ? relationType.substring(relationType.lastIndexOf('.') + 1)
                : relationType;

        for (Relation relation : sig.edgesOf(source)) {
            if (sig.getEdgeTarget(relation) == target
                    && (relation.getClass().getSimpleName().equals(simpleType)
                            || relation.getClass().getName().equals(relationType))) {
                relations.add(relation);

                if (!all) {
                    break;
                }
            }
        }

        if (relations.isEmpty()) {
            throw new NoSuchElementException(
                    "No relation " + relationType + " between #" + source.getId()
                            + " and #" + target.getId());
        }

        final UITaskList seq = new UITaskList();

        for (Relation relation : relations) {
            seq.add(new UnlinkTask(sig, relation));
        }

        apply(seq, OpKind.DO);
        history.add(
                seq,
                "unlink",
                "unlink #" + source.getId() + " " + relationType + " #" + target.getId(),
                List.of(source.getId(), target.getId()));
        return relations;
    }

    public HeadChordInter mergeChords (List<HeadChordInter> chords,
                                       boolean withStem)
    {
        if (chords.size() < 2) {
            throw new ToolException("INVALID_ARGUMENT", "At least two chords are required");
        }

        final SIGraph sig = chords.get(0).getSig();

        for (HeadChordInter chord : chords) {
            if (chord.getSig() != sig) {
                throw new ToolException(
                        "INVALID_ARGUMENT",
                        "All chords must belong to the same SIG");
            }
        }

        final UITaskList seq = new UITaskList();
        final HeadChordInter newChord = new HeadChordInter(1.0);
        final Rectangle newChordBounds = Entities.getBounds(chords);
        final List<HeadInter> heads = new ArrayList<>();

        for (HeadChordInter chord : chords) {
            for (Inter head : chord.getNotes()) {
                heads.add((HeadInter) head);
            }
        }

        Collections.sort(heads, Inters.byReverseCenterOrdinate);

        final List<Link> newChordLinks = new ArrayList<>();

        for (HeadInter head : heads) {
            newChordLinks.add(new Link(head, new Containment(), true));
        }

        for (HeadChordInter chord : chords) {
            for (Relation rel : sig.getRelations(chord, Support.class)) {
                final Inter target = sig.getEdgeTarget(rel);
                final Inter other = sig.getOppositeInter(chord, rel);
                newChordLinks.add(new Link(other, rel.duplicate(), other == target));
                seq.add(new UnlinkTask(sig, rel));
            }
        }

        newChord.setManual(true);
        seq.add(new AdditionTask(sig, newChord, newChordBounds, newChordLinks));

        for (HeadChordInter chord : chords) {
            for (Relation rel : sig.getRelations(chord, Containment.class)) {
                seq.add(new UnlinkTask(sig, rel));
            }
        }

        if (withStem) {
            final List<StemInter> stems = new ArrayList<>();
            final StemInter newStem = buildStem(chords, stems);
            final Rectangle newStemBounds = Entities.getBounds(stems);
            final List<Link> newStemLinks = new ArrayList<>();

            for (HeadInter head : heads) {
                newStemLinks.add(new Link(head, new HeadStemRelation(), false));
            }

            for (StemInter stem : stems) {
                for (Relation rel : sig.getRelations(
                        stem,
                        BeamStemRelation.class,
                        FlagStemRelation.class)) {
                    final Inter target = sig.getEdgeTarget(rel);
                    final Inter other = sig.getOppositeInter(stem, rel);
                    newStemLinks.add(new Link(other, rel.duplicate(), other == target));
                }
            }

            seq.add(new AdditionTask(sig, newStem, newStemBounds, newStemLinks));

            for (StemInter stem : stems) {
                seq.add(new RemovalTask(stem));
            }
        }

        for (HeadChordInter chord : chords) {
            seq.add(new RemovalTask(chord));
        }

        apply(seq, OpKind.DO);
        newChord.countDots();

        final List<Integer> ids = new ArrayList<>();
        for (HeadChordInter chord : chords) {
            ids.add(chord.getId());
        }

        history.add(seq, "merge", "merge chords " + ids, ids);
        return newChord;
    }

    public List<HeadChordInter> splitChord (HeadChordInter chord)
    {
        final SIGraph sig = chord.getSig();

        if (sig == null) {
            throw new ToolException("INVALID_ARGUMENT", "Chord is not in a SIG");
        }

        final List<List<HeadInter>> partitions = partitionHeads(chord);

        if (partitions.get(0).isEmpty() || partitions.get(1).isEmpty()) {
            throw new ToolException(
                    "INVALID_ARGUMENT",
                    "Chord must contain at least two heads");
        }

        final UITaskList seq = new UITaskList();
        final List<HeadChordInter> newChords = new ArrayList<>();

        for (List<HeadInter> partition : partitions) {
            final List<Link> newChordLinks = new ArrayList<>();

            for (HeadInter head : partition) {
                newChordLinks.add(new Link(head, new Containment(), true));
            }

            final Rectangle bounds = Entities.getBounds(partition);
            final HeadChordInter newChord = new HeadChordInter(1.0);
            newChord.setManual(true);
            newChord.setStaff(partition.get(0).getStaff());
            newChords.add(newChord);
            seq.add(new AdditionTask(sig, newChord, bounds, newChordLinks));
        }

        for (Relation rel : sig.getRelations(chord, Containment.class)) {
            seq.add(new UnlinkTask(sig, rel));
        }

        final StemInter stem = chord.getStem();
        final Point tail = chord.getTailLocation();
        final int yDir = Integer.compare(tail.y, chord.getCenter().y);

        seq.add(new RemovalTask(chord));

        if (stem != null) {
            final Rectangle[] boxes = getSubStemsBounds(stem, tail, yDir, partitions);

            for (int i = 0; i < 2; i++) {
                final List<HeadInter> partition = partitions.get(i);
                final StemInter newStem = new StemInter(null, 1.0);
                newStem.setManual(true);
                final List<Link> newStemLinks = new ArrayList<>();

                for (HeadInter head : partition) {
                    newStemLinks.add(new Link(head, new HeadStemRelation(), false));
                }

                if (((yDir == -1) && (i == 1)) || ((yDir == 1) && (i == 0))) {
                    for (Relation rel : sig.getRelations(
                            stem,
                            BeamStemRelation.class,
                            FlagStemRelation.class)) {
                        final Inter target = sig.getEdgeTarget(rel);
                        final Inter other = sig.getOppositeInter(stem, rel);
                        newStemLinks.add(new Link(other, rel.duplicate(), other == target));
                    }
                }

                seq.add(new AdditionTask(sig, newStem, boxes[i], newStemLinks));
            }

            seq.add(new RemovalTask(stem));
        }

        apply(seq, OpKind.DO);

        for (HeadChordInter newChord : newChords) {
            newChord.countDots();
        }

        final List<Integer> ids = new ArrayList<>();
        for (HeadChordInter newChord : newChords) {
            ids.add(newChord.getId());
        }

        history.add(seq, "split", "split chord #" + chord.getId(), ids);
        return newChords;
    }

    public void mergeSystem (SystemInfo system)
    {
        final List<SystemInfo> systems = sheet.getSystems();
        final int index = systems.indexOf(system);

        if ((index < 0) || (index >= (systems.size() - 1))) {
            throw new ToolException(
                    "INVALID_ARGUMENT",
                    "No system below system #" + system.getId());
        }

        final SystemInfo systemBelow = systems.get(index + 1);
        final Staff upStaff = system.getLastStaff();
        final Staff downStaff = systemBelow.getFirstStaff();
        final BarlineInter upBar = upStaff == null
                ? null
                : upStaff.getSideBarline(HorizontalSide.LEFT);
        final BarlineInter downBar = downStaff == null
                ? null
                : downStaff.getSideBarline(HorizontalSide.LEFT);

        final UITaskList seq = new UITaskList();
        seq.add(new SystemMergeTask(system));

        if ((upBar != null) && (downBar != null)) {
            final Shape shape = (upBar.getShape() == Shape.THICK_BARLINE)
                    ? Shape.THICK_CONNECTOR
                    : Shape.THIN_CONNECTOR;
            final Point2D p1 = upBar.getMedian().getP1();
            final Point2D p2 = downBar.getMedian().getP2();
            final Line2D median = new Line2D.Double(p1, p2);
            final double width = (upBar.getWidth() + downBar.getWidth()) * 0.5;
            final BarConnectorInter connector = new BarConnectorInter(
                    shape,
                    1.0,
                    median,
                    width);
            final SIGraph sig = system.getSig();
            seq.add(new AdditionTask(
                    sig,
                    connector,
                    connector.getBounds(),
                    Collections.emptySet()));
            seq.add(new LinkTask(sig, upBar, downBar, new BarConnectionRelation()));
        }

        apply(seq, OpKind.DO, OmrStep.PAGE);
        history.add(
                seq,
                "merge_system",
                "merge system #" + system.getId(),
                List.of(system.getId()));
    }

    public Inter updateSymbol (Inter inter,
                               Map<String, Object> changes)
    {
        final UITaskList seq = new UITaskList();
        final Map<String, Object> pending = new LinkedHashMap<>(changes);
        final List<String> applied = new ArrayList<>();

        // Time signature numerator/denominator must be changed as a pair.
        final Integer numerator = integerOrNull(pending.remove("numerator"));
        final Integer denominator = integerOrNull(pending.remove("denominator"));

        if ((numerator != null) || (denominator != null)) {
            if (!(inter instanceof TimeCustomInter time)) {
                throw new ToolException(
                        "UNSUPPORTED",
                        "numerator/denominator only apply to TimeCustomInter");
            }

            final TimeRational old = time.getTimeRational();
            final int newNum = numerator != null ? numerator : old.num;
            final int newDen = denominator != null ? denominator : old.den;
            seq.add(new TimeValueTask(time, new TimeRational(newNum, newDen)));
            applied.add("time");
        }

        final Object shapeValue = pending.remove("shape");

        if (shapeValue != null) {
            final Shape newShape = Shape.valueOf(shapeValue.toString());

            if (newShape != inter.getShape()) {
                seq.add(new ShapeTask(inter, newShape));
                applied.add("shape");
            }
        }

        final Object staffValue = pending.remove("staff_id");

        if (staffValue != null) {
            seq.add(new StaffTask(inter, findStaffById(intValue(staffValue, "staff_id"))));
            applied.add("staff_id");
        }

        final Object pitchValue = pending.remove("pitch");

        if (pitchValue != null) {
            if (!(inter instanceof AbstractPitchedInter pitched)) {
                throw new ToolException("UNSUPPORTED", "pitch only applies to pitched inters");
            }

            seq.add(new PitchTask(pitched, doubleValue(pitchValue, "pitch")));
            applied.add("pitch");
        }

        final Object voiceValue = pending.remove("voice_id");

        if (voiceValue != null) {
            if (!(inter instanceof AbstractChordInter chord)) {
                throw new ToolException("UNSUPPORTED", "voice_id only applies to chords");
            }

            seq.add(new ChordVoiceIdTask(chord, intValue(voiceValue, "voice_id")));
            applied.add("voice_id");
        }

        final Object textValue = pending.remove("text");

        if (textValue != null) {
            final String text = textValue.toString();

            if (inter instanceof MetronomeInter metronome) {
                seq.add(new MetronomeTextTask(metronome, text));
            } else if (inter instanceof WordInter word) {
                seq.add(new WordValueTask(word, text));
            } else {
                throw new ToolException("UNSUPPORTED", "text does not apply to this inter");
            }

            applied.add("text");
        }

        final Object syllableValue = pending.remove("syllable");

        if (syllableValue != null) {
            if (!(inter instanceof LyricItemInter lyric)) {
                throw new ToolException("UNSUPPORTED", "syllable only applies to lyric items");
            }

            final LyricItemInter.SyllabicType type;

            try {
                type = LyricItemInter.SyllabicType.valueOf(
                        syllableValue.toString().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(
                        "syllable must be SINGLE, BEGIN, MIDDLE or END");
            }

            seq.add(new SyllableTask(lyric, type));
            applied.add("syllable");
        }

        final Object numberValue = pending.remove("value");

        if (numberValue != null) {
            if (!(inter instanceof AbstractNumberInter number)) {
                throw new ToolException("UNSUPPORTED", "value only applies to number inters");
            }

            seq.add(new NumberValueTask(number, intValue(numberValue, "value")));
            applied.add("value");
        }

        final Object roleValue = pending.remove("role");

        if (roleValue != null) {
            if (!(inter instanceof SentenceInter sentence)) {
                throw new ToolException("UNSUPPORTED", "role only applies to sentences");
            }

            final TextRole role;

            try {
                role = TextRole.valueOf(roleValue.toString());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Unknown text role: " + roleValue);
            }

            seq.add(new SentenceRoleTask(sentence, role));
            applied.add("role");
        }

        if (!pending.isEmpty()) {
            throw new ToolException(
                    "UNSUPPORTED",
                    "Unsupported changes: " + pending.keySet());
        }

        if (seq.getTasks().isEmpty()) {
            return inter;
        }

        apply(seq, OpKind.DO);
        history.add(
                seq,
                "update",
                "update #" + inter.getId() + " " + applied,
                List.of(inter.getId()));
        return inter;
    }

    public Map<String, Object> reprocessRhythm (Integer stackId,
                                                Integer pageId)
    {
        if (stub.getLatestStep().compareTo(OmrStep.RHYTHMS) < 0) {
            throw new ToolException(
                    "INVALID_ARGUMENT",
                    "Sheet must reach RHYTHMS before rhythm reprocessing");
        }

        final UITaskList seq = new UITaskList();
        final Integer usedPageId;

        if (stackId != null) {
            seq.add(new StackTask(findStack(stackId)));
            usedPageId = pageId;
        } else if (pageId != null) {
            seq.add(new PageTask(findPage(pageId)));
            usedPageId = pageId;
        } else {
            if (sheet.getPages().isEmpty()) {
                throw new NoSuchElementException("No page in sheet " + stub.getNumber());
            }

            final Page page = sheet.getPages().get(0);
            seq.add(new PageTask(page));
            usedPageId = page.getId();
        }

        apply(seq, OpKind.DO, OmrStep.RHYTHMS);

        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("stack_id", stackId);
        data.put("page_id", usedPageId);
        data.put("ok", true);
        return data;
    }

    public SlurInter toggleTie (Inter inter)
    {
        if (!(inter instanceof SlurInter slur)) {
            throw new ToolException("UNSUPPORTED", "toggle_tie requires a SlurInter");
        }

        final UITaskList seq = new UITaskList();
        seq.add(new TieTask(slur));
        apply(seq, OpKind.DO);
        history.add(
                seq,
                "tie",
                "toggle tie #" + slur.getId(),
                List.of(slur.getId()));
        return slur;
    }

    public List<EditHistory.Entry> undo (int count)
    {
        final List<EditHistory.Entry> entries = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            final EditHistory.Entry entry = history.undo();

            if (entry == null) {
                break;
            }

            apply(entry.tasks(), OpKind.UNDO);
            entries.add(entry);
        }

        return entries;
    }

    public List<EditHistory.Entry> redo (int count)
    {
        final List<EditHistory.Entry> entries = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            final EditHistory.Entry entry = history.redo();

            if (entry == null) {
                break;
            }

            apply(entry.tasks(), OpKind.REDO);
            entries.add(entry);
        }

        return entries;
    }

    private Inter createGhost (Shape shape,
                               Point point,
                               Integer staffId)
    {
        final Inter ghost = InterFactory.createManual(shape, sheet);

        if (ghost == null) {
            throw new ToolException(
                    "UNSUPPORTED",
                    "Shape cannot be added manually: " + shape);
        }

        final Staff staff = findStaff(point, staffId);

        if (staff == null) {
            throw new ToolException("INVALID_ARGUMENT", "No staff found at " + point);
        }

        ghost.setStaff(staff);

        final MusicFamily family = stub.getMusicFamily();
        final MusicFont font = ShapeSet.Heads.contains(shape)
                ? MusicFont.getHeadFont(family, sheet.getScale(), staff.getSpecificInterline())
                : MusicFont.getBaseFont(family, staff.getSpecificInterline());
        final ShapeSymbol symbol = font.getSymbol(shape);

        if (symbol == null) {
            throw new ToolException("UNSUPPORTED", "No symbol for shape: " + shape);
        }

        final Point location = new Point(point);

        if (!ghost.deriveFrom(symbol, sheet, font, location)) {
            throw new ToolException("INVALID_ARGUMENT", "Could not place symbol at " + point);
        }

        return ghost;
    }

    private Rectangle boundsFor (Inter inter,
                                 Point point,
                                 boolean snap)
    {
        if (snap && (inter.getShape() != null) && (inter.getStaff() != null)) {
            try {
                final Inter ghost = InterFactory.createManual(inter.getShape(), sheet);
                ghost.setStaff(inter.getStaff());

                final MusicFamily family = stub.getMusicFamily();
                final MusicFont font = ShapeSet.Heads.contains(inter.getShape())
                        ? MusicFont.getHeadFont(
                                family,
                                sheet.getScale(),
                                inter.getStaff().getSpecificInterline())
                        : MusicFont.getBaseFont(
                                family,
                                inter.getStaff().getSpecificInterline());
                final ShapeSymbol symbol = font.getSymbol(inter.getShape());

                if ((symbol != null)
                        && ghost.deriveFrom(symbol, sheet, font, new Point(point))) {
                    return ghost.getBounds();
                }
            } catch (Throwable ignored) {
                // Fall back to a plain centered box.
            }
        }

        final Rectangle old = inter.getBounds();

        if (old != null) {
            return new Rectangle(
                    point.x - (old.width / 2),
                    point.y - (old.height / 2),
                    old.width,
                    old.height);
        }

        final Dimension dimension = new Dimension(10, 10);
        return new Rectangle(
                point.x - (dimension.width / 2),
                point.y - (dimension.height / 2),
                dimension.width,
                dimension.height);
    }

    private StemInter buildStem (List<HeadChordInter> chords,
                                 List<StemInter> stems)
    {
        final List<Glyph> glyphs = new ArrayList<>();

        for (HeadChordInter chord : chords) {
            final StemInter stem = chord.getStem();

            if (stem == null) {
                throw new ToolException(
                        "INVALID_ARGUMENT",
                        "Chord #" + chord.getId() + " has no stem");
            }

            stems.add(stem);

            if (stem.getGlyph() != null) {
                glyphs.add(stem.getGlyph());
            }
        }

        Collections.sort(stems, Inters.byCenterOrdinate);

        final Glyph stemGlyph = glyphs.isEmpty()
                ? null
                : sheet.getGlyphIndex().registerOriginal(GlyphFactory.buildGlyph(glyphs));
        final StemInter stemInter = new StemInter(stemGlyph, 1.0);
        stemInter.setManual(true);
        return stemInter;
    }

    private List<List<HeadInter>> partitionHeads (HeadChordInter chord)
    {
        final List<? extends Inter> notes = chord.getNotes();
        Point prevCenter = null;
        Integer maxDy = null;
        int bestIndex = 0;

        for (int i = 0; i < notes.size(); i++) {
            final HeadInter head = (HeadInter) notes.get(i);
            final Point center = head.getCenter();

            if (prevCenter != null) {
                final int dy = prevCenter.y - center.y;

                if ((maxDy == null) || (maxDy < dy)) {
                    maxDy = dy;
                    bestIndex = i;
                }
            }

            prevCenter = center;
        }

        final List<List<HeadInter>> lists = new ArrayList<>();
        final List<HeadInter> one = new ArrayList<>();
        final List<HeadInter> two = new ArrayList<>();

        for (Inter inter : notes.subList(0, bestIndex)) {
            one.add((HeadInter) inter);
        }

        for (Inter inter : notes.subList(bestIndex, notes.size())) {
            two.add((HeadInter) inter);
        }

        lists.add(one);
        lists.add(two);
        return lists;
    }

    private Rectangle[] getSubStemsBounds (StemInter stem,
                                           Point tail,
                                           int yDir,
                                           List<List<HeadInter>> partitions)
    {
        final Rectangle[] boundsArray = new Rectangle[2];
        final Line2D median = stem.getMedian();
        final int width = sheet.getScale().getStemThickness();

        for (int i = 0; i < 2; i++) {
            final List<HeadInter> partition = partitions.get(i);
            final int stemTop;
            final int stemBottom;

            if (i == 0) {
                if (yDir < 0) {
                    final List<HeadInter> other = partitions.get(1);
                    stemTop = other.get(0).getCenter().y;
                    stemBottom = partition.get(0).getCenter().y;
                } else {
                    stemTop = partition.get(partition.size() - 1).getCenter().y;
                    stemBottom = tail.y;
                }
            } else {
                if (yDir < 0) {
                    stemTop = tail.y;
                    stemBottom = partition.get(0).getCenter().y;
                } else {
                    final List<HeadInter> other = partitions.get(0);
                    stemTop = partition.get(partition.size() - 1).getCenter().y;
                    stemBottom = other.get(other.size() - 1).getCenter().y;
                }
            }

            final Point top = PointUtil.rounded(LineUtil.intersectionAtY(median, stemTop));
            final Point bottom = PointUtil.rounded(LineUtil.intersectionAtY(median, stemBottom));
            final Area area = AreaUtil.verticalParallelogram(top, bottom, width);
            boundsArray[i] = area.getBounds();
        }

        return boundsArray;
    }

    private Staff findStaffById (int staffId)
    {
        return findStaff(null, staffId);
    }

    private Page findPage (int pageId)
    {
        for (Page page : sheet.getPages()) {
            if (page.getId() == pageId) {
                return page;
            }
        }

        throw new NoSuchElementException("No page " + pageId);
    }

    private MeasureStack findStack (int stackId)
    {
        for (SystemInfo system : sheet.getSystems()) {
            for (MeasureStack stack : system.getStacks()) {
                if (stack.getIdValue() == stackId) {
                    return stack;
                }
            }
        }

        throw new NoSuchElementException("No stack " + stackId);
    }

    private static Integer integerOrNull (Object value)
    {
        if (value == null) {
            return null;
        }

        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Expected an integer value");
        }

        return number.intValue();
    }

    private static int intValue (Object value,
                                 String key)
    {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("'" + key + "' must be an integer");
        }

        return number.intValue();
    }

    private static double doubleValue (Object value,
                                       String key)
    {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("'" + key + "' must be a number");
        }

        return number.doubleValue();
    }

    private Staff findStaff (Point point,
                             Integer staffId)
    {
        if (staffId != null) {
            for (Staff staff : sheet.getStaffManager().getStaves()) {
                if (staff.getId() == staffId) {
                    return staff;
                }
            }

            throw new NoSuchElementException("No staff " + staffId);
        }

        final List<Staff> staves = sheet.getStaffManager().getStavesOf(point);

        if (staves.isEmpty()) {
            return null;
        }

        if (staves.size() > 1) {
            throw new ToolException(
                    "INVALID_ARGUMENT",
                    "Several staves at " + point + "; specify staff_id");
        }

        return staves.get(0);
    }

    private void apply (UITaskList seq,
                        OpKind opKind)
    {
        apply(seq, opKind, firstImpactedStep(seq));
    }

    private void apply (UITaskList seq,
                        OpKind opKind,
                        OmrStep first)
    {
        if (opKind == OpKind.UNDO) {
            seq.performUndo();
        } else {
            seq.performDo();
        }

        stub.setModified(true);
        session.book.setModified(true);

        final OmrStep latest = stub.getLatestStep();

        if ((first != null) && (latest != null) && (first.compareTo(latest) <= 0)) {
            for (OmrStep step : EnumSet.range(first, latest)) {
                step.impact(seq, opKind);
            }
        }
    }

    private static OmrStep firstImpactedStep (UITaskList seq)
    {
        for (UITask task : seq.getTasks()) {
            final Class<?> classe;

            if (task instanceof InterTask interTask) {
                classe = interTask.getInter().getClass();
            } else if (task instanceof RelationTask relationTask) {
                classe = relationTask.getRelation().getClass();
            } else {
                continue;
            }

            for (OmrStep step : OmrStep.values()) {
                if (step.isImpactedBy(classe)) {
                    return step;
                }
            }
        }

        return null;
    }

    private static Relation newRelation (String type)
    {
        final String className = type.contains(".")
                ? type
                : "org.audiveris.omr.sig.relation." + type;

        if (!className.startsWith("org.audiveris.omr.sig.relation.")) {
            throw new ToolException("UNSUPPORTED", "Relation type is not allowed: " + type);
        }

        try {
            final Class<?> clazz = Class.forName(className);

            if (!Relation.class.isAssignableFrom(clazz)
                    || Modifier.isAbstract(clazz.getModifiers())
                    || clazz.isInterface()) {
                throw new ToolException("UNSUPPORTED", "Not a relation class: " + type);
            }

            return (Relation) clazz.getDeclaredConstructor().newInstance();
        } catch (ToolException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new ToolException(
                    "UNSUPPORTED",
                    "Cannot instantiate relation type: " + type,
                    java.util.Map.of("error", ex.toString()));
        }
    }

    private static List<Integer> ids (List<Inter> inters)
    {
        final List<Integer> ids = new ArrayList<>();

        for (Inter inter : inters) {
            ids.add(inter.getId());
        }

        return ids;
    }

    /**
     * Move one inter, including its links, without using the UI editor.
     */
    private static class MoveTask
            extends InterTask
    {
        private final Rectangle oldBounds;

        private final Rectangle newBounds;

        private final Glyph oldGlyph;

        private final Collection<Link> unlinks;

        MoveTask (Inter inter,
                  Rectangle oldBounds,
                  Rectangle newBounds,
                  Glyph oldGlyph,
                  Collection<Link> links,
                  Collection<Link> unlinks)
        {
            super(inter.getSig(), inter, oldBounds, links, "move");
            this.oldBounds = oldBounds;
            this.newBounds = newBounds;
            this.oldGlyph = oldGlyph;
            this.unlinks = unlinks;
        }

        @Override
        public void performDo ()
        {
            inter.setBounds(newBounds);
            inter.setGlyph(null);

            for (Link link : links) {
                link.applyTo(inter);
            }

            for (Link unlink : unlinks) {
                unlink.removeFrom(inter);
            }
        }

        @Override
        public void performUndo ()
        {
            inter.setBounds(oldBounds);

            if (oldGlyph != null) {
                inter.setGlyph(oldGlyph);
            }

            for (Link unlink : unlinks) {
                unlink.applyTo(inter);
            }

            for (Link link : links) {
                link.removeFrom(inter);
            }
        }
    }

    private static class PitchTask
            extends InterTask
    {
        private final Double oldPitch;

        private final Double newPitch;

        PitchTask (AbstractPitchedInter pitched,
                   double newPitch)
        {
            super(pitched.getSig(), pitched, pitched.getBounds(), null, "pitch");
            oldPitch = pitched.getPitch();
            this.newPitch = newPitch;
        }

        @Override
        public void performDo ()
        {
            ((AbstractPitchedInter) inter).setPitch(newPitch);
        }

        @Override
        public void performUndo ()
        {
            ((AbstractPitchedInter) inter).setPitch(oldPitch);
        }
    }

    private static class StaffTask
            extends InterTask
    {
        private final Staff oldStaff;

        private final Staff newStaff;

        StaffTask (Inter inter,
                   Staff newStaff)
        {
            super(inter.getSig(), inter, inter.getBounds(), null, "staff");
            oldStaff = inter.getStaff();
            this.newStaff = newStaff;
        }

        @Override
        public void performDo ()
        {
            inter.setStaff(newStaff);
        }

        @Override
        public void performUndo ()
        {
            inter.setStaff(oldStaff);
        }
    }

    private static class SyllableTask
            extends InterTask
    {
        private final LyricItemInter.SyllabicType oldType;

        private final LyricItemInter.SyllabicType newType;

        SyllableTask (LyricItemInter lyric,
                      LyricItemInter.SyllabicType newType)
        {
            super(lyric.getSig(), lyric, lyric.getBounds(), null, "syllable");
            oldType = lyric.getSyllabicType();
            this.newType = newType;
        }

        @Override
        public void performDo ()
        {
            ((LyricItemInter) inter).setSyllabicType(newType);
        }

        @Override
        public void performUndo ()
        {
            ((LyricItemInter) inter).setSyllabicType(oldType);
        }
    }

    private static class ShapeTask
            extends InterTask
    {
        private final Shape oldShape;

        private final Shape newShape;

        ShapeTask (Inter inter,
                   Shape newShape)
        {
            super(inter.getSig(), inter, inter.getBounds(), null, "shape");
            oldShape = inter.getShape();
            this.newShape = newShape;
        }

        @Override
        public void performDo ()
        {
            inter.renameShapeAs(newShape);
        }

        @Override
        public void performUndo ()
        {
            inter.renameShapeAs(oldShape);
        }
    }

    private static class MetronomeTextTask
            extends InterTask
    {
        private final String oldValue;

        private final String newValue;

        MetronomeTextTask (MetronomeInter metronome,
                           String newValue)
        {
            super(metronome.getSig(), metronome, metronome.getBounds(), null, "metronome");
            oldValue = metronome.getValue();
            this.newValue = newValue;
        }

        @Override
        public void performDo ()
        {
            ((MetronomeInter) inter).setValue(newValue);
        }

        @Override
        public void performUndo ()
        {
            ((MetronomeInter) inter).setValue(oldValue);
        }
    }
}
