package com.subhub.app.people;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Routes by identity; a missing remote person can never resolve to local configuration. */
public final class PersonDirectory implements PersonRepository {
    private final PersonRepository local, remote;

    public PersonDirectory(PersonRepository local, PersonRepository remote) {
        this.local = java.util.Objects.requireNonNull(local);
        this.remote = remote;
    }

    @Override public List<PersonId> people() throws Exception {
        LinkedHashSet<PersonId> ids = new LinkedHashSet<>(local.people());
        if (remote != null) {
            for (PersonId id : remote.people()) {
                if (id.isLocal() || !ids.add(id)) {
                    throw new IllegalStateException("Duplicate person identity");
                }
            }
        }
        return new ArrayList<>(ids);
    }

    @Override public PersonSnapshot load(PersonId id) throws Exception {
        if (id == null) throw new IllegalArgumentException("Missing person identity");
        if (!id.isLocal() && remote == null) throw new IllegalArgumentException("Person unavailable");
        PersonSnapshot result = (id.isLocal() ? local : remote).load(id);
        if (result == null || !id.equals(result.id)) {
            throw new IllegalStateException("Person identity mismatch");
        }
        return result;
    }
}
