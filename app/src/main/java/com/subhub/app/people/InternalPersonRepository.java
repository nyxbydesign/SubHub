package com.subhub.app.people;

import android.content.Context;
import java.util.List;

/** The reserved internal identity has its own backend and can never fall through to a peer. */
final class InternalPersonRepository implements PersonRepository {
    private final Context physical;
    InternalPersonRepository(Context physical){this.physical=physical.getApplicationContext();}
    @Override public List<PersonId> people(){
        InternalSubData.loadIfProvisioned(physical);
        return DummySubPreview.isEnabled(physical)?List.of(DummySubPreview.ID):List.of();
    }
    private void require(PersonId id){
        if(!DummySubPreview.ID.equals(id))throw new IllegalArgumentException("Not the internal Sub");
        InternalSubData.loadIfProvisioned(physical);
        if(!DummySubPreview.isEnabled(physical))throw new SecurityException("Internal Sub unavailable");
    }
    @Override public PersonSnapshot load(PersonId id){require(id);return DummySubPreview.snapshot(physical);}
    @Override public PersonSnapshot loadSummary(PersonId id){
        require(id);return InternalPersonPresentation.summary(physical,InternalSubData.get(physical).acknowledged());
    }
}
