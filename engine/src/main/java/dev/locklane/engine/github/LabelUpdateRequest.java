package dev.locklane.engine.github;

import java.util.List;

/** Body of a labels PATCH: label names to add and to remove from one issue — either list may be empty or absent. */
public record LabelUpdateRequest(List<String> add, List<String> remove) {
    public LabelUpdateRequest {
        add = add == null ? List.of() : add;
        remove = remove == null ? List.of() : remove;
    }
}
