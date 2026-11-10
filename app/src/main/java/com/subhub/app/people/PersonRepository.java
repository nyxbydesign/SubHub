package com.subhub.app.people;

import java.util.List;

/** Reads may perform disk/network work and must run off the UI thread. */
public interface PersonRepository {
    List<PersonId> people() throws Exception;
    PersonSnapshot load(PersonId id) throws Exception;

    /**
     * Identity, presence and recorded session/stat totals for read-only roster cards.
     * Callers must use load() before showing contracts or exposing commands. Implementations may
     * omit contract sections and allowances, mark them unknown, and grant no edit capabilities.
     * Compatibility repositories keep their existing full projection by default.
     */
    default PersonSnapshot loadSummary(PersonId id) throws Exception { return load(id); }
}
