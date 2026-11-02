package com.subhub.app.remote;

import static org.junit.Assert.*;
import static com.subhub.app.remote.RemoteSessionCommands.*;
import static com.subhub.app.remote.RemoteSessionRuntime.*;

import java.util.Collections;
import org.json.JSONObject;
import org.junit.Test;

public class RemoteSessionCommandsTest {
    private static final String DOM = repeat("a"), SUB = repeat("b"), FENCE = repeat("c"), SESSION = repeat("d");
    private static final Duration SERVICE = new Duration(3_600_000, MIN_DURATION_MILLIS, MAX_DURATION_MILLIS, false);
    private static String repeat(String value) { return String.join("", Collections.nCopies(64, value)); }
    private static Request start() { return Request.start("start", DOM, SUB, "generation", 1, 7, 1000, 121000, FENCE, SERVICE); }
    private static Request stop() { return Request.stop("stop", DOM, SUB, "generation", 1, 7, 1000, 121000, SESSION); }
    private static Facts facts(boolean active, boolean hc, HardcoreConsent consent, String fence, String session,
            long revision, Readiness readiness) {
        return new Facts(SUB, DOM, "generation", RemoteProtocol.Role.SUB, true, true, true, VERSION,
                revision, active, fence, session, SERVICE, hc, consent, readiness);
    }
    private static HardcoreConsent consent() { return new HardcoreConsent(DOM, SUB, "generation", 900); }

    @Test public void canonicalRoundTripAndDigestBindEveryOperationField() throws Exception {
        Request original = start(), copy = Request.parse(new JSONObject(original.json().toString()));
        assertEquals(original.digest(), copy.digest());
        assertEquals(stop().digest(), Request.parse(stop().json()).digest());
        JSONObject altered = original.json().put("appliedRevision", 8);
        assertNotEquals(original.digest(), Request.parse(altered).digest());
        assertThrows(Exception.class, () -> Request.parse(original.json().put("apply", true)));
        assertThrows(Exception.class, () -> Request.parse(original.json().put("appliedRevision", "7")));
        assertThrows(Exception.class, () -> Request.parse(original.json().put("service", SERVICE.json().put("hidden", "false"))));
    }

    @Test public void lifetimeAndRecipientCannotBeExpanded() {
        assertThrows(IllegalArgumentException.class, () -> Request.start("id", DOM, DOM, "g", 1, 0, 1, 2, FENCE, SERVICE));
        assertThrows(IllegalArgumentException.class, () -> Request.start("id", DOM, SUB, "g", 1, 0, 1, 120002, FENCE, SERVICE));
        assertThrows(IllegalArgumentException.class, () -> Request.start("id", DOM, SUB, "g", 1, 0, 2, 2, FENCE, SERVICE));
        assertThrows(IllegalArgumentException.class, () -> Request.stop("id", DOM, SUB, "g", 1, 0, 1, 2, ""));
    }

    @Test public void optionalCapabilityNeverGrantsApplication() throws Exception {
        assertEquals(0, negotiatedVersion(new JSONObject()));
        assertEquals(1, negotiatedVersion(new JSONObject().put("sessionControlVersion", 1)));
        assertEquals(0, negotiatedVersion(new JSONObject().put("sessionControlVersion", 2)));
        assertThrows(Exception.class, () -> negotiatedVersion(new JSONObject().put("sessionControlVersion", "1")));
        assertThrows(Exception.class, () -> negotiatedVersion(new JSONObject().put("sessionControlVersion", 0)));
        assertFalse(applicationEnabled());
    }

    @Test public void startRequiresCurrentReceiverHardcoreReviewAndExactGeneration() throws Exception {
        assertEquals(Reason.NONE, validateIncoming(start(), facts(false,true,consent(),FENCE,"",7,Readiness.APP_MODE_READY),1001));
        assertEquals(Reason.HARDCORE_REQUIRED, validateIncoming(start(), facts(false,false,consent(),FENCE,"",7,Readiness.APP_MODE_READY),1001));
        assertEquals(Reason.CONSENT_REQUIRED, validateIncoming(start(), facts(false,true,null,FENCE,"",7,Readiness.APP_MODE_READY),1001));
        HardcoreConsent old = new HardcoreConsent(DOM,SUB,"old-generation",900);
        assertEquals(Reason.CONSENT_REQUIRED, validateIncoming(start(), facts(false,true,old,FENCE,"",7,Readiness.APP_MODE_READY),1001));
        assertThrows(Exception.class, () -> HardcoreConsent.parseStored(consent().json().put("version", 2)));
        assertTrue(HardcoreConsent.parseStored(consent().json()).matches(start()));
    }

    @Test public void stopDoesNotRequireHardcoreButCannotTargetLaterSession() throws Exception {
        assertEquals(Reason.NONE, validateIncoming(stop(), facts(true,false,null,"",SESSION,99,Readiness.UNAVAILABLE),1001));
        assertEquals(Reason.SESSION_CHANGED, validateIncoming(stop(), facts(true,false,null,"",FENCE,7,Readiness.APP_MODE_READY),1001));
        assertEquals(Reason.SESSION_CHANGED, validateIncoming(stop(), facts(false,false,null,FENCE,"",7,Readiness.APP_MODE_READY),1001));
        assertEquals(Reason.EXPIRED, validateIncoming(stop(), facts(true,false,null,"",SESSION,7,Readiness.APP_MODE_READY),121000));
    }

    @Test public void staleIdleFenceRevisionAndReadinessRefuseStart() throws Exception {
        assertEquals(Reason.SESSION_CHANGED, validateIncoming(start(),facts(false,true,consent(),SESSION,"",7,Readiness.APP_MODE_READY),1001));
        assertEquals(Reason.REVISION_CHANGED, validateIncoming(start(),facts(false,true,consent(),FENCE,"",8,Readiness.APP_MODE_READY),1001));
        assertEquals(Reason.READINESS_REQUIRED, validateIncoming(start(),facts(false,true,consent(),FENCE,"",7,Readiness.NEEDS_SUB_ACTION),1001));
        assertEquals(Reason.CLOCK_CHANGED, validateIncoming(start(),facts(false,true,consent(),FENCE,"",7,Readiness.APP_MODE_READY),0));
        Facts unauthenticated = new Facts(SUB,DOM,"generation",RemoteProtocol.Role.SUB,false,true,true,1,7,false,FENCE,"",SERVICE,true,consent(),Readiness.APP_MODE_READY);
        assertEquals(Reason.AUTHORITY_CHANGED, validateIncoming(start(),unauthenticated,1001));
    }

    @Test public void acknowledgementIsNotApplicationAndNativeProofMustMatchDuration() throws Exception {
        Result ack = new Result(start(),State.ACKNOWLEDGED,Reason.NONE,1001,null);
        assertNull(Result.parse(start(),ack.json()).proof);
        assertThrows(IllegalArgumentException.class, () -> new Result(start(),State.APPLIED,Reason.NONE,1001,null));
        Proof wrong = new Proof(true,SESSION,1001,2001,1000);
        assertThrows(IllegalArgumentException.class, () -> new Result(start(),State.APPLIED,Reason.NONE,1001,wrong));
        Result applied = new Result(start(),State.APPLIED,Reason.NONE,1002,new Proof(true,SESSION,1001,3601001,3600000));
        assertTrue(Result.parse(start(),applied.json()).proof.active);
        assertThrows(Exception.class, () -> Result.parse(start(),applied.json().put("requestDigest",FENCE)));
        assertThrows(IllegalArgumentException.class, () -> new Result(stop(),State.APPLIED,Reason.NONE,1002,applied.proof));
    }

    @Test public void resultsMoveForwardWithoutTerminalRewriteOrRetryClockRenewal() throws Exception {
        Result queued = new Result(start(),State.QUEUED,Reason.NONE,1001,null);
        Result ack = new Result(start(),State.ACKNOWLEDGED,Reason.NONE,1002,null);
        Result failed = new Result(start(),State.FAILED,Reason.APPLICATION_FAILED,1003,null);
        assertTrue(ack.mayFollow(queued)); assertFalse(queued.mayFollow(ack));
        assertTrue(failed.mayFollow(ack)); assertTrue(Result.parse(start(),failed.json()).mayFollow(failed));
        assertFalse(new Result(start(),State.FAILED,Reason.APPLICATION_FAILED,1004,null).mayFollow(failed));
        assertFalse(new Result(start(),State.ACKNOWLEDGED,Reason.NONE,1004,null).mayFollow(ack));
        assertThrows(IllegalArgumentException.class, () -> new Result(start(),State.REJECTED,Reason.NONE,1003,null));
    }

    @Test public void originalElapsedAnchorExpiresDespiteSmallRepeatedWallCorrections() {
        RemoteSessionClock.Bounds bound=RemoteSessionClock.Bounds.begin(start(),new RemoteSessionClock.Sample(1001,500,2));
        assertEquals(Reason.NONE,bound.check(start(),new RemoteSessionClock.Sample(2001,1500,2)));
        assertEquals(Reason.CLOCK_CHANGED,bound.check(start(),new RemoteSessionClock.Sample(2002,5500,2)));
        assertEquals(Reason.EXPIRED,bound.check(start(),new RemoteSessionClock.Sample(121001,120500,2)));
        assertEquals(120499,bound.deadlineElapsed);
    }

    @Test public void rollbackRebootUnknownClockAndElapsedResetCannotExtendLifetime() {
        RemoteSessionClock.Bounds bound=RemoteSessionClock.Bounds.begin(start(),new RemoteSessionClock.Sample(1001,500,2));
        assertEquals(Reason.CLOCK_CHANGED,bound.check(start(),new RemoteSessionClock.Sample(1000,501,2)));
        assertEquals(Reason.CLOCK_CHANGED,bound.check(start(),new RemoteSessionClock.Sample(1002,501,3)));
        assertEquals(Reason.CLOCK_CHANGED,bound.check(start(),new RemoteSessionClock.Sample(1002,499,2)));
        RemoteSessionClock.Sample unknown=new RemoteSessionClock.Sample(1001,500,-1);
        assertEquals(Reason.CLOCK_CHANGED,RemoteSessionClock.Bounds.begin(start(),unknown).check(start(),unknown));
    }

    @Test public void clockChangedTerminalPersistsActualRegressedOrNonpositiveWallTime() throws Exception {
        Result ack=new Result(start(),State.ACKNOWLEDGED,Reason.NONE,1001,null);
        Result changed=new Result(start(),State.REJECTED,Reason.CLOCK_CHANGED,-100,null);
        assertTrue(changed.mayFollow(ack));
        assertEquals(-100,Result.parse(start(),changed.json()).observedAt);
        assertTrue(new Result(start(),State.REJECTED,Reason.AUTHORITY_CHANGED,1000,null).mayFollow(ack));
        assertTrue(new Result(start(),State.ACKNOWLEDGED,Reason.NONE,900,null)
                .mayFollow(new Result(start(),State.QUEUED,Reason.NONE,1001,null)));
    }

    @Test public void wireRequiresPositiveSequenceAndDigestBindsIt() throws Exception {
        assertThrows(Exception.class,()->Request.parse(start().json().put("sequence",0)));
        JSONObject old=start().json();old.remove("sequence");
        assertThrows(Exception.class,()->Request.parse(old));
        assertNotEquals(start().digest(),Request.parse(start().json().put("sequence",2)).digest());
    }
}
