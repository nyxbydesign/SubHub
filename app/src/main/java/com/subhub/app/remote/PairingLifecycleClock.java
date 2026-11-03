package com.subhub.app.remote;

import android.content.Context;

/** Original local clock bounds; retry, presentation and delayed callbacks never create a new lifetime. */
final class PairingLifecycleClock {
    static final long PUBLIC_MILLIS=600_000,COMPLETION_MILLIS=600_000,PROOF_MILLIS=30_000;
    enum Direction { CREATE, ACCEPT }
    enum Verdict { LIVE, EXPIRED, UNCERTAIN }
    interface Source extends FormalRelationshipClock.Source { }

    static final class Bounds {
        final FormalRelationshipClock.Sample created;
        final long publicWall,publicElapsed,outerWall,outerElapsed;
        Bounds(FormalRelationshipClock.Sample created,long publicWall,long publicElapsed){
            created.requireValid();
            long wall=Math.subtractExact(publicWall,created.wall),elapsed=Math.subtractExact(publicElapsed,created.elapsed);
            if(wall<1||wall>PUBLIC_MILLIS||wall!=elapsed)throw new IllegalArgumentException("Invalid original invitation bounds");
            this.created=created;this.publicWall=publicWall;this.publicElapsed=publicElapsed;
            outerWall=Math.addExact(publicWall,COMPLETION_MILLIS);outerElapsed=Math.addExact(publicElapsed,COMPLETION_MILLIS);
        }
    }
    static final class Approval {
        final FormalRelationshipClock.Sample first;
        final long deadlineWall,deadlineElapsed;
        Approval(Bounds bounds,FormalRelationshipClock.Sample first,long deadlineWall,long deadlineElapsed){
            first.requireValid();
            long wall=Math.subtractExact(deadlineWall,first.wall),elapsed=Math.subtractExact(deadlineElapsed,first.elapsed);
            if(!FormalRelationshipClock.consistent(bounds.created,first)||wall<1||wall>COMPLETION_MILLIS||wall!=elapsed
                    ||deadlineWall>bounds.outerWall||deadlineElapsed>bounds.outerElapsed)
                throw new IllegalArgumentException("Invalid original approval bounds");
            this.first=first;this.deadlineWall=deadlineWall;this.deadlineElapsed=deadlineElapsed;
        }
    }

    private PairingLifecycleClock(){ }

    static Source source(Context context){
        FormalRelationshipClock.Source source=FormalRelationshipClock.source(context);
        return new Source(){
            @Override public FormalRelationshipClock.Sample observe(){return source.observe();}
            @Override public FormalRelationshipClock.Sample transition(FormalRelationshipClock.Sample observed){return source.transition(observed);}
        };
    }
    static Bounds begin(FormalRelationshipClock.Sample original,long expiresWall){
        original.requireValid();long remaining=Math.subtractExact(expiresWall,original.wall);
        return new Bounds(original,expiresWall,Math.addExact(original.elapsed,remaining));
    }
    static Verdict publicVerdict(Bounds bounds,FormalRelationshipClock.Sample now){
        if(!FormalRelationshipClock.consistent(bounds.created,now))return Verdict.UNCERTAIN;
        return now.wall<bounds.publicWall&&now.elapsed<bounds.publicElapsed?Verdict.LIVE:Verdict.EXPIRED;
    }
    static Approval firstApproval(Bounds bounds,Direction direction,FormalRelationshipClock.Sample first){
        first.requireValid();
        if(direction==null)throw new IllegalArgumentException("Pairing direction required");
        if(!FormalRelationshipClock.consistent(bounds.created,first)
                ||direction==Direction.CREATE&&publicVerdict(bounds,first)!=Verdict.LIVE)
            throw new SecurityException("Original pairing approval unavailable");
        long remaining=Math.min(COMPLETION_MILLIS,Math.min(Math.subtractExact(bounds.outerWall,first.wall),
                Math.subtractExact(bounds.outerElapsed,first.elapsed)));
        if(remaining<=0)throw new SecurityException("Original pairing completion expired");
        return new Approval(bounds,first,Math.addExact(first.wall,remaining),Math.addExact(first.elapsed,remaining));
    }
    static Verdict completionVerdict(Bounds bounds,Approval approval,FormalRelationshipClock.Sample now){
        if(!FormalRelationshipClock.consistent(bounds.created,now)||!FormalRelationshipClock.consistent(approval.first,now))
            return Verdict.UNCERTAIN;
        return now.wall<approval.deadlineWall&&now.elapsed<approval.deadlineElapsed?Verdict.LIVE:Verdict.EXPIRED;
    }
    static long remaining(Bounds bounds,Approval approval,FormalRelationshipClock.Sample now){
        Verdict verdict=approval==null?publicVerdict(bounds,now):completionVerdict(bounds,approval,now);
        if(verdict!=Verdict.LIVE)return 0;
        return Math.min((approval==null?bounds.publicWall:approval.deadlineWall)-now.wall,
                (approval==null?bounds.publicElapsed:approval.deadlineElapsed)-now.elapsed);
    }
    static boolean freshProof(FormalRelationshipClock.Sample original,FormalRelationshipClock.Sample now){
        return FormalRelationshipClock.consistent(original,now)&&now.elapsed-original.elapsed<PROOF_MILLIS
                &&now.wall-original.wall<PROOF_MILLIS;
    }
}
