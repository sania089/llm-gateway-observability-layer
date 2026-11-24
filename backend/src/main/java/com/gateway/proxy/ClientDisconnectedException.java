package com.gateway.proxy;

/** Thrown from the chunk sink when the client is gone, so the upstream call is aborted. */
class ClientDisconnectedException extends RuntimeException {

    ClientDisconnectedException(Throwable cause) {
        super("Client disconnected", cause);
    }
}
