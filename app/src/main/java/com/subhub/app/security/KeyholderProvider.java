package com.subhub.app.security;

/** Relationship authority is independent of legacy authenticator setup and UI mode. */
public interface KeyholderProvider {
    enum State { NONE, PENDING, READY, UNAVAILABLE }
    State state();
    String assignedDomName();
    ControllerAuthenticator.Result verifyCode(String code);
    /** Admits local credential changes atomically with relationship authority transitions. */
    default boolean withLocalAuthority(java.util.function.BooleanSupplier action) {
        return state() == State.NONE && action.getAsBoolean();
    }
}
