package com.subhub.app.people;

import android.content.Context;
import com.subhub.app.internal.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** JVM token contracts. Reflection supplies a synthetic null host without invoking Android stubs.
 * Native owned fixtures separately exercise physical Context binding and the provider path. */
public final class InternalVisibilityBarrierTest {
    @Test public void requestRequiresAnExplicitChangeAndCapturedPositiveBoundary() throws Exception {
        try (Fixture f = new Fixture()) {
            assertThrows(IllegalArgumentException.class, () -> new InternalVisibilityBarrier.Request(f.record, false, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> new InternalVisibilityBarrier.Request(f.record, true, 0, 0));
            InternalVisibilityBarrier.Request request = new InternalVisibilityBarrier.Request(f.record, true, Long.MIN_VALUE, 1234);
            assertSame(f.record, request.expected); assertEquals(Long.MIN_VALUE, request.controllerRevision);
            assertEquals(1234, request.transitionAtMillis);
        }
    }
    @Test public void missingRuntimeProofFailsWithoutChangingTheRecord() throws Exception {
        try (Fixture f = new Fixture()) {
            Field field = InternalVisibilityBarrier.class.getDeclaredField("UNAVAILABLE"); field.setAccessible(true);
            InternalVisibilityBarrier.Provider missing = (InternalVisibilityBarrier.Provider) field.get(null);
            ExecutionException unavailable = assertThrows(ExecutionException.class, () -> missing.prepare(null,
                    new InternalVisibilityBarrier.Request(f.record, true, 1, 1234)).get());
            assertTrue(unavailable.getCause() instanceof InternalVisibilityBarrier.UnavailableException);
            assertEquals(f.record, f.store.read());
        }
    }
    @Test public void providerInstallationCannotReplaceAnEstablishedProofOwner() throws Exception {
        Field field = InternalVisibilityBarrier.class.getDeclaredField("provider"); field.setAccessible(true);
        Object original = field.get(null);
        InternalVisibilityBarrier.Provider injected = (context, request) -> new CompletableFuture<>();
        try {
            assertThrows(NullPointerException.class, () -> InternalVisibilityBarrier.install(null));
            assertSame(original, field.get(null));
            InternalVisibilityBarrier.install(injected);
            assertSame(injected, field.get(null));
            assertThrows(IllegalStateException.class, () -> InternalVisibilityBarrier.install((context, request) -> new CompletableFuture<>()));
            assertSame(injected, field.get(null));
        } finally { field.set(null, original); }
    }
    @Test public void proofBindsEveryTransitionFieldAndCannotBeReused() throws Exception {
        try (Fixture f = new Fixture(); OwnedProvider owned = new OwnedProvider()) {
            Context context = null;
            InternalVisibilityBarrier.Proof proof = owned.proof(new InternalVisibilityBarrier.Request(f.record, true, 7, 1234));
            assertThrows(SecurityException.class, () -> proof.consume(context, f.record, 8, true, 1234));
            assertThrows(SecurityException.class, () -> proof.consume(context, f.record, 7, false, 1234));
            assertThrows(SecurityException.class, () -> proof.consume(context, f.record, 7, true, 1235));
            assertEquals(0, owned.admissions);
            proof.consume(context, f.record, 7, true, 1234);
            assertThrows(SecurityException.class, () -> proof.consume(context, f.record, 7, true, 1234));
            assertEquals(1, owned.admissions);
            InternalSubRecord saved = f.store.setVisible(f.record, true, 1234);
            proof.complete(saved, null).get();
            assertEquals(InternalVisibilityBarrier.Outcome.ACKNOWLEDGED, owned.completion.outcome);
            assertSame(saved, owned.completion.acknowledged);
            assertThrows(IllegalStateException.class, () -> proof.complete(saved, null));
        }
    }
    @Test public void preAdmissionFailureFencesAnOtherwiseQueuedProof() throws Exception {
        try (Fixture f = new Fixture(); OwnedProvider owned = new OwnedProvider()) {
            Context context = null;
            InternalVisibilityBarrier.Proof proof = owned.proof(new InternalVisibilityBarrier.Request(f.record, true, 7, 1234));
            proof.complete(null, new SecurityException("Fixture controller revoked")).get();
            assertEquals(InternalVisibilityBarrier.Outcome.NOT_ADMITTED, owned.completion.outcome);
            assertThrows(SecurityException.class, () -> proof.consume(context, f.record, 7, true, 1234));
            assertEquals(0, owned.admissions); assertEquals(f.record, f.store.read());
        }
    }
    @Test public void consumedButUnacknowledgedMutationRemainsExplicitlyUncertain() throws Exception {
        try (Fixture f = new Fixture(); OwnedProvider owned = new OwnedProvider()) {
            Context context = null;
            InternalVisibilityBarrier.Proof proof = owned.proof(new InternalVisibilityBarrier.Request(f.record, true, 7, 1234));
            proof.consume(context, f.record, 7, true, 1234);
            Throwable interrupted = new IllegalStateException("Interrupted wait", new InterruptedException());
            proof.complete(null, interrupted).get();
            assertEquals(InternalVisibilityBarrier.Outcome.UNCERTAIN, owned.completion.outcome);
            assertSame(interrupted, owned.completion.failure); assertNull(owned.completion.acknowledged);
        }
    }
    private static final class OwnedProvider implements AutoCloseable {
        int admissions;
        InternalVisibilityBarrier.Completion completion;
        InternalVisibilityBarrier.Proof proof(InternalVisibilityBarrier.Request request) throws Exception {
            java.lang.reflect.Constructor<InternalVisibilityBarrier.Proof> constructor =
                    InternalVisibilityBarrier.Proof.class.getDeclaredConstructor(Context.class,
                            InternalVisibilityBarrier.Request.class, InternalVisibilityBarrier.Permit.class);
            constructor.setAccessible(true);
            return constructor.newInstance(null, request, new InternalVisibilityBarrier.Permit() {
                @Override public void requireCurrent() { admissions++; }
                @Override public CompletableFuture<Void> complete(InternalVisibilityBarrier.Completion value) {
                    completion = value; return CompletableFuture.completedFuture(null);
                }
            });
        }
        @Override public void close() { }
    }
    private static final class Fixture implements AutoCloseable {
        final Path parent = Files.createTempDirectory("internal-visibility-proof-");
        final InternalSubStore store;
        final InternalSubRecord record;
        Fixture() throws Exception {
            store = new InternalSubStore(FileInternalSubBackend.createNew(parent.toFile(),
                    new InternalProfile("Owned", "", "", "", 0), Map.of()), value -> { });
            record = store.read();
        }
        @Override public void close() throws Exception {
            try (java.util.stream.Stream<Path> paths = Files.walk(parent)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }
}
