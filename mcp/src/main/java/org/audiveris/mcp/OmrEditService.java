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
import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.glyph.ShapeSet;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.SheetStub;
import org.audiveris.omr.sheet.Staff;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.sheet.symbol.InterFactory;
import org.audiveris.omr.sig.SIGraph;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.sig.relation.Link;
import org.audiveris.omr.sig.relation.Relation;
import org.audiveris.omr.sig.ui.AdditionTask;
import org.audiveris.omr.sig.ui.InterTask;
import org.audiveris.omr.sig.ui.LinkTask;
import org.audiveris.omr.sig.ui.RelationTask;
import org.audiveris.omr.sig.ui.RemovalScenario;
import org.audiveris.omr.sig.ui.UITask;
import org.audiveris.omr.sig.ui.UITask.OpKind;
import org.audiveris.omr.sig.ui.UITaskList;
import org.audiveris.omr.sig.ui.UnlinkTask;
import org.audiveris.omr.step.OmrStep;
import org.audiveris.omr.ui.symbol.MusicFamily;
import org.audiveris.omr.ui.symbol.MusicFont;
import org.audiveris.omr.ui.symbol.ShapeSymbol;
import org.audiveris.omr.util.WrappedBoolean;
import org.audiveris.omr.util.Wrapper;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
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
        if (opKind == OpKind.UNDO) {
            seq.performUndo();
        } else {
            seq.performDo();
        }

        stub.setModified(true);
        session.book.setModified(true);

        final OmrStep first = firstImpactedStep(seq);
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
}
