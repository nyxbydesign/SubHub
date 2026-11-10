package com.subhub.app.people;

import java.util.List;

/** Reads may perform disk/network work and must run off the UI thread. */
public interface PersonRepository {
    List<PersonId> people() throws Exception;
    PersonSnapshot load(PersonId id) throws Exception;
}
