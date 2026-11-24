package dev.kdenlivemcp.edit;

import dev.kdenlivemcp.model.Project;

import java.util.List;

/**
 * Outcome of an edit.
 *
 * @param summary one line for the AI, e.g. "inserted c4 on V1 0.000–6.000"
 * @param notes side effects worth knowing ("removed dissolve x7: c4 and c6 no longer touch")
 * @param ids ids created or changed, first the primary one
 */
public record EditResult(Project project, String summary, List<String> notes, List<String> ids) {

    public EditResult {
        notes = List.copyOf(notes);
        ids = List.copyOf(ids);
    }

    public String message() {
        if (notes.isEmpty()) {
            return summary;
        }
        return summary + "\n" + String.join("\n", notes.stream().map(n -> "note: " + n).toList());
    }
}
