package dev.locklane.engine.github;

/** One stage of the open → plan → work → review → ship strip. */
public record FlowStep(String name, boolean done) {
}
