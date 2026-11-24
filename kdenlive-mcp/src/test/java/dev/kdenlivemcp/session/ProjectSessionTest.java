package dev.kdenlivemcp.session;

import dev.kdenlivemcp.edit.Fixtures;
import dev.kdenlivemcp.edit.TimelineOps;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Project;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.kdenlivemcp.edit.Fixtures.f;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectSessionTest {

    private final List<Project> saved = new ArrayList<>();

    @Test
    void requiresOpenProject() {
        ProjectSession s = new ProjectSession(saved::add, 10);
        assertThatThrownBy(s::current).isInstanceOf(EditException.class)
                .hasMessage("no project is open: call project_new or project_open first");
    }

    @Test
    void autosavesEveryEditAndUndoRestoresExactState() {
        ProjectSession s = new ProjectSession(saved::add, 10);
        Project start = Fixtures.withMedia();
        s.open(start);
        s.apply("insert", p -> TimelineOps.insert(p, "m1", "V1", 0, null, null, true));
        Project afterInsert = s.current();
        s.apply("split", p -> TimelineOps.split(p, "c4", f(2)));
        s.apply("move", p -> TimelineOps.move(p, "c6", null, f(10)));
        assertThat(saved).hasSize(3);

        assertThat(s.undo()).isEqualTo("move");
        assertThat(s.undo()).isEqualTo("split");
        assertThat(s.current()).isEqualTo(afterInsert);
        assertThat(s.undo()).isEqualTo("insert");
        assertThat(s.current()).isEqualTo(start);
        assertThat(saved).hasSize(6); // each undo saves too
        assertThatThrownBy(s::undo).hasMessage("nothing to undo");
    }

    @Test
    void failedEditChangesNothing() {
        ProjectSession s = new ProjectSession(saved::add, 10);
        s.open(Fixtures.withMedia());
        Project before = s.current();
        assertThatThrownBy(() -> s.apply("bad", p -> TimelineOps.insert(p, "m1", "V9", 0, null, null, true)))
                .isInstanceOf(EditException.class);
        assertThat(s.current()).isSameAs(before);
        assertThat(s.undoDepth()).isZero();
        assertThat(saved).isEmpty();
    }

    @Test
    void failedSaveRejectsEdit() {
        ProjectSession s = new ProjectSession(p -> {
            throw new IllegalStateException("disk full");
        }, 10);
        s.open(Fixtures.withMedia());
        Project before = s.current();
        assertThatThrownBy(() -> s.apply("insert", p -> TimelineOps.insert(p, "m1", "V1", 0, null, null, true)))
                .hasMessage("disk full");
        assertThat(s.current()).isSameAs(before);
    }

    @Test
    void historyIsCapped() {
        ProjectSession s = new ProjectSession(saved::add, 2);
        s.open(Fixtures.withMedia());
        s.apply("1", p -> TimelineOps.insert(p, "m1", "V1", 0, null, null, true));
        s.apply("2", p -> TimelineOps.move(p, "c4", null, f(1)));
        s.apply("3", p -> TimelineOps.move(p, "c4", null, f(2)));
        assertThat(s.undoDepth()).isEqualTo(2);
    }
}
