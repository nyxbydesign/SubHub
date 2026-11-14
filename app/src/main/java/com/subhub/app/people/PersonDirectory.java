package com.subhub.app.people;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Routes by identity; a missing remote person can never resolve to local configuration. */
public final class PersonDirectory implements PersonRepository {
    private final PersonRepository local, remote, internal;

    public PersonDirectory(PersonRepository local, PersonRepository remote) { this(local, remote, null); }
    public PersonDirectory(PersonRepository local, PersonRepository remote, PersonRepository internal) {
        this.internal = internal;
        this.local = java.util.Objects.requireNonNull(local);
        this.remote = remote;
    }

    @Override public List<PersonId> people() throws Exception {
        LinkedHashSet<PersonId> ids = new LinkedHashSet<>(local.people());
        if (ids.contains(PersonId.INTERNAL)) throw new IllegalStateException("Local repository cannot supply internal identity");
        if (remote != null) {
            for (PersonId id : remote.people()) {
                if (id.isLocal() || PersonId.INTERNAL.equals(id) || !ids.add(id)) {
                    throw new IllegalStateException("Duplicate person identity");
                }
            }
        }
        if (internal != null) for (PersonId id : internal.people()) {
            if (!PersonId.INTERNAL.equals(id) || !ids.add(id)) throw new IllegalStateException("Invalid internal identity");
        }
        return new ArrayList<>(ids);
    }

    @Override public PersonSnapshot load(PersonId id) throws Exception { return load(id, PersonRepository::load); }

    @Override public PersonSnapshot loadSummary(PersonId id) throws Exception { return load(id, PersonRepository::loadSummary); }

    @Override public PersonSnapshot loadForPage(PersonId id, boolean includeContract) throws Exception {
        return load(id, (repository, target) -> repository.loadForPage(target, includeContract));
    }

    private PersonSnapshot load(PersonId id, PageRead read) throws Exception {
        if (id == null) throw new IllegalArgumentException("Missing person identity");
        PersonRepository selected = PersonId.INTERNAL.equals(id) ? internal : id.isLocal() ? local : remote;
        if (selected == null) throw new IllegalArgumentException("Person unavailable");
        PersonSnapshot result = read.load(selected, id);
        if (result == null || !id.equals(result.id)) {
            throw new IllegalStateException("Person identity mismatch");
        }
        return result;
    }

    private interface PageRead {
        PersonSnapshot load(PersonRepository repository, PersonId id) throws Exception;
    }
}
