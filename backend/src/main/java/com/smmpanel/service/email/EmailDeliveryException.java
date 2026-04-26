package com.smmpanel.service.email;

/**
 * Raised when an outbound email cannot be delivered. Callers should treat this as non-fatal —
 * security flows (verify/forgot) must NOT leak failure to the end user, since that allows email
 * enumeration.
 */
public class EmailDeliveryException extends RuntimeException {
    public EmailDeliveryException(String message) {
        super(message);
    }

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
