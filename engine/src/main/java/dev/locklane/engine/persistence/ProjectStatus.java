package dev.locklane.engine.persistence;

/** Where a project's checkout is: cloning now, cloned and usable, or failed to clone. */
public enum ProjectStatus {
    CLONING, READY, FAILED
}
