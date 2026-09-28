package com.ecomdemo.assistant.store;

/** Catalog-service or the application did not answer usefully: a 5xx, a timeout, no connection. */
public class StoreUnavailableException extends RuntimeException {

    public StoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
