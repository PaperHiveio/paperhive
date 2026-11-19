package org.abdullahcxd.paperhive.api.message;

/** A request's target has no handler for that service (or it went away, e.g. the server stopped). */
public final class NoResponderException extends RuntimeException {

    public NoResponderException(String message) {
        super(message);
    }

}
