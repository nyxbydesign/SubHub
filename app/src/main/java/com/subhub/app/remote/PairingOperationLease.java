package com.subhub.app.remote;

import java.util.concurrent.atomic.AtomicReference;

/** Cheap presentation handle. Closing releases only its exact operation's transport demand. */
final class PairingOperationLease implements AutoCloseable {
    private enum Closure { VOLUNTARY, EXPIRED, UNAVAILABLE }
    private final String capability;
    private final java.util.function.Consumer<PairingOperationLease> release;
    private final PairingLifecycleClock.Source clock;
    private final PairingLifecycleLedger.Operation original;
    private final AtomicReference<State> state;
    private volatile RemoteFeature.PairingOperation operation;
    private static final class State {
        final PairingLifecycleLedger.Operation bounds;final FormalRelationshipClock.Sample observed;
        final long drainDeadline;final Closure closure;
        State(PairingLifecycleLedger.Operation bounds,FormalRelationshipClock.Sample observed,long drainDeadline,Closure closure){
            this.bounds=bounds;this.observed=observed;this.drainDeadline=drainDeadline;this.closure=closure;
        }
        State closed(Closure reason){return new State(bounds,observed,drainDeadline,reason);}
    }
    PairingOperationLease(RemoteFeature.PairingOperation operation,String capability,
            PairingLifecycleLedger.Operation bounds,FormalRelationshipClock.Sample observed,PairingLifecycleClock.Source clock,
            java.util.function.Consumer<PairingOperationLease> release){
        this.operation=operation;this.capability=capability;this.release=release;this.clock=clock;original=bounds;
        state=new AtomicReference<>(new State(bounds,observed,Long.MAX_VALUE,null));
    }
    RemoteFeature.PairingOperation operation(){return operation;}
    String cancelCapability(){return capability;}
    boolean isClosed(){return state.get().closure!=null;}
    void update(RemoteFeature.PairingOperation operation){this.operation=operation;}
    boolean matches(PairingLifecycleLedger.Operation bounds){
        return original.id.equals(bounds.id)&&original.generation.equals(bounds.generation)&&original.direction==bounds.direction;
    }
    /** Memory-only publication inside the approval SQL work. It retains demand, never application authority.
     * The caller must run the returned abort action if commit or continuity sealing fails. */
    Runnable stageApproval(PairingLifecycleLedger.Operation approved,FormalRelationshipClock.Sample observed){
        if(approved.approval==null)return ()->{};
        if(!matches(approved)||!original.tokenHash.equals(approved.tokenHash)
                ||original.peer!=null&&!original.peer.equals(approved.peer)
                ||original.bounds.created.wall!=approved.bounds.created.wall||original.bounds.created.elapsed!=approved.bounds.created.elapsed
                ||original.bounds.created.boot!=approved.bounds.created.boot||original.bounds.publicWall!=approved.bounds.publicWall
                ||original.bounds.publicElapsed!=approved.bounds.publicElapsed)
            throw new SecurityException("Pairing lease original bounds changed");
        while(true){
            State saved=state.get();
            if(saved.closure!=null){
                if(saved.closure!=Closure.VOLUNTARY)throw new SecurityException("Pairing listener original deadline unavailable");
                return ()->{}; // A deliberate service stop is not renewed by an approval callback.
            }
            if(saved.bounds.approval!=null){
                if(!saved.bounds.approvalDigest.equals(approved.approvalDigest)
                        ||saved.bounds.approval.first.wall!=approved.approval.first.wall
                        ||saved.bounds.approval.first.elapsed!=approved.approval.first.elapsed
                        ||saved.bounds.approval.first.boot!=approved.approval.first.boot
                        ||saved.bounds.approval.deadlineWall!=approved.approval.deadlineWall
                        ||saved.bounds.approval.deadlineElapsed!=approved.approval.deadlineElapsed)
                    throw new SecurityException("Pairing first approval changed");
                return ()->{};
            }
            State staged=new State(approved,observed,saved.drainDeadline,null);
            if(state.compareAndSet(saved,staged))return ()->close(Closure.UNAVAILABLE);
        }
    }
    void observeApproval(PairingLifecycleLedger.Operation approved,FormalRelationshipClock.Sample observed){stageApproval(approved,observed);}
    /** Deadline-executor attenuation only; no SQL, provider, Keystore or operation queue is consulted. */
    void checkDeadline(){
        while(true){
            State saved=state.get();if(saved.closure!=null)return;Closure reason;
            if(saved.drainDeadline!=Long.MAX_VALUE){if(System.nanoTime()<saved.drainDeadline)return;reason=Closure.EXPIRED;}
            else try{
                PairingLifecycleClock.Verdict verdict=saved.bounds.verdict(clock.transition(saved.observed));
                if(verdict==PairingLifecycleClock.Verdict.LIVE)return;
                reason=verdict==PairingLifecycleClock.Verdict.EXPIRED?Closure.EXPIRED:Closure.UNAVAILABLE;
            }catch(RuntimeException unavailable){reason=Closure.UNAVAILABLE;}
            // A newly published first approval defeats a stale PENDING expiry observation.
            if(state.compareAndSet(saved,saved.closed(reason))){release.accept(this);return;}
        }
    }
    boolean activeDraining(long originalDeadline){
        while(true){State saved=state.get();if(saved.closure!=null)return false;
            if(saved.drainDeadline!=Long.MAX_VALUE)return System.nanoTime()<saved.drainDeadline;
            State draining=new State(saved.bounds,saved.observed,originalDeadline,null);
            if(state.compareAndSet(saved,draining))return System.nanoTime()<originalDeadline;
        }
    }
    private void close(Closure reason){
        while(true){State saved=state.get();if(saved.closure!=null)return;
            if(state.compareAndSet(saved,saved.closed(reason))){release.accept(this);return;}
        }
    }
    @Override public void close(){close(Closure.VOLUNTARY);}
}
