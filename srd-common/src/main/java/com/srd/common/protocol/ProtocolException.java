package com.srd.common.protocol;

/** Malformed or unexpected data from the peer; the connection should be closed. */
public class ProtocolException extends RuntimeException {
    public ProtocolException(String message) {
        super(message);
    }
}
