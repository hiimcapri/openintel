package dev.openintel.api;

public record ActionResult(Status status, String message) {
    public enum Status {
        COMPLETED, SUBMITTED, NOT_READY, NOT_IN_MULTIPLAYER, WRONG_SERVER,
        NOT_AUTHENTICATED, INVALID_ARGUMENT, SESSION_CHANGED, FAILED
    }
    public boolean accepted() { return status == Status.COMPLETED || status == Status.SUBMITTED; }
}
