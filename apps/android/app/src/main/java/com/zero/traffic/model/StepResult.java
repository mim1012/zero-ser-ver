package com.zero.traffic.model;

/**
 * Action 실행 결과
 */
public class StepResult {

    public enum Status {
        SUCCESS,
        FAILED,
        CAPTCHA,
        BLOCKED,
        ABORT
    }

    private final Status status;
    private final String message;

    private StepResult(Status status, String message) {
        this.status = status;
        this.message = message;
    }

    public static StepResult success() {
        return new StepResult(Status.SUCCESS, "");
    }

    public static StepResult fail(String message) {
        return new StepResult(Status.FAILED, message);
    }

    public static StepResult captcha() {
        return new StepResult(Status.CAPTCHA, "CAPTCHA detected");
    }

    public static StepResult blocked() {
        return new StepResult(Status.BLOCKED, "Page blocked");
    }

    public static StepResult abort(String message) {
        return new StepResult(Status.ABORT, message);
    }

    public boolean isSuccess() { return status == Status.SUCCESS; }
    public boolean isFailed() { return status != Status.SUCCESS; }
    public boolean isCaptcha() { return status == Status.CAPTCHA; }
    public boolean isBlocked() { return status == Status.BLOCKED; }
    public boolean isAbort() { return status == Status.ABORT; }
    public Status getStatus() { return status; }
    public String getMessage() { return message; }

    @Override
    public String toString() {
        return status + (message.isEmpty() ? "" : ": " + message);
    }
}
