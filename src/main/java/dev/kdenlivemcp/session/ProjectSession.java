package dev.kdenlivemcp.session;

import dev.kdenlivemcp.edit.EditResult;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Project;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.function.Function;

/**
 * The one open project of this server, with undo history and autosave.
 * <p>
 * Every mutation is saved before it becomes current; if saving fails the edit is rejected and the
 * previous state stays. Undo restores the exact previous {@link Project} instance and saves it.
 */
public class ProjectSession {

    /** Persists a project to its file. */
    @FunctionalInterface
    public interface Saver {
        void save(Project project);
    }

    private record Step(String label, Project before) {
    }

    private final Saver saver;
    private final int maxUndo;
    private final Deque<Step> history = new ArrayDeque<>();
    private Project current;

    public ProjectSession(Saver saver, int maxUndo) {
        this.saver = saver;
        this.maxUndo = Math.max(1, maxUndo);
    }

    public synchronized Optional<Project> peek() {
        return Optional.ofNullable(current);
    }

    public synchronized Project current() {
        if (current == null) {
            throw new EditException("no project is open: call project_new or project_open first");
        }
        return current;
    }

    /** Replaces the open project (new/open). Clears undo history. Does not save. */
    public synchronized void open(Project project) {
        current = project;
        history.clear();
    }

    /** Applies an edit, autosaves, and records it for undo. */
    public synchronized EditResult apply(String label, Function<Project, EditResult> edit) {
        Project before = current();
        EditResult result = edit.apply(before);
        if (result.project() == before) {
            return result; // read-only or no-op
        }
        saver.save(result.project());
        history.push(new Step(label, before));
        while (history.size() > maxUndo) {
            history.removeLast();
        }
        current = result.project();
        return result;
    }

    /** Saves the current project, optionally to a new path ("save as"). Not an undoable step. */
    public synchronized Project save(java.nio.file.Path newFile) {
        Project p = current();
        Project target = newFile == null ? p : p.withFile(newFile.toAbsolutePath().normalize());
        saver.save(target);
        current = target;
        return target;
    }

    /** Undoes the last edit and saves. Returns the label of what was undone. */
    public synchronized String undo() {
        current();
        Step step = history.peek();
        if (step == null) {
            throw new EditException("nothing to undo");
        }
        Project restored = step.before().withFile(current.file());
        saver.save(restored);
        history.pop();
        current = restored;
        return step.label();
    }

    public synchronized int undoDepth() {
        return history.size();
    }
}
