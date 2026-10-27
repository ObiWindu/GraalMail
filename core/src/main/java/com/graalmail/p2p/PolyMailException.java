package com.graalmail.p2p;

import com.graalmail.core.error.ErrorCode;

/** Typed error used by the PolyMail protocol implementation. */
public class PolyMailException extends RuntimeException {
    private final ErrorCode code;
    private final boolean retryable;
    private final boolean userActionRequired;
    private final boolean remainsQueued;

    public PolyMailException(ErrorCode code, String message) {
        this(code, message, true, false, true);
    }

    public PolyMailException(ErrorCode code, String message, boolean retryable, boolean userActionRequired, boolean remainsQueued) {
        super(message);
        this.code = code;
        this.retryable = retryable;
        this.userActionRequired = userActionRequired;
        this.remainsQueued = remainsQueued;
    }

    public PolyMailException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = true;
        this.userActionRequired = false;
        this.remainsQueued = true;
    }

    public ErrorCode code() { return code; }
    public boolean retryable() { return retryable; }
    public boolean userActionRequired() { return userActionRequired; }
    public boolean remainsQueued() { return remainsQueued; }
}