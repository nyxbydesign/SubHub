package com.subhub.app.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure tests for the bounded, privacy-safe surface identity algorithm. */
public final class AccessibilitySurfaceIdentityResolverTest {
    @Test public void prefersUniqueIdAndDoesNotExposeRawIdentifiers() {
        AccessibilitySurfaceIdentityResolver resolver =
                new AccessibilitySurfaceIdentityResolver();
        FakeNode owner = new FakeNode(true, "androidx.recyclerview.widget.RecyclerView",
                "unique-owner", "com.example:id/feed");
        owner.parent = new FakeNode(false, "android.view.ViewRootImpl", null, "root");

        AccessibilitySurfaceIdentityResolver.Identity identity =
                resolver.resolveForNode(7, "com.example.app", owner);

        assertEquals(AccessibilitySurfaceIdentityResolver.OWNER_UNIQUE_ID,
                identity.ownerKind);
        assertEquals(AccessibilitySurfaceIdentityResolver.CONFIDENCE_HIGH,
                identity.confidence);
        assertTrue(identity.isCacheable());
        assertNotEquals(0L, identity.telemetryToken());
        assertFalse(identity.toString().contains("com.example.app"));
        assertFalse(identity.toString().contains("unique-owner"));
        assertFalse(identity.toString().contains("com.example:id/feed"));
    }

    @Test public void fallsBackToViewIdBeforeStructuralIdentity() {
        AccessibilitySurfaceIdentityResolver resolver =
                new AccessibilitySurfaceIdentityResolver();
        FakeNode owner = new FakeNode(true, "android.widget.ScrollView",
                null, "com.example:id/content");

        AccessibilitySurfaceIdentityResolver.Identity identity =
                resolver.resolveForNode(3, "com.example.app", owner);

        assertEquals(AccessibilitySurfaceIdentityResolver.OWNER_VIEW_ID,
                identity.ownerKind);
        assertEquals(AccessibilitySurfaceIdentityResolver.CONFIDENCE_MEDIUM,
                identity.confidence);
        assertTrue(identity.isCacheable());
    }

    @Test public void recycledChildrenDoNotChangeTheirScrollableOwner() {
        AccessibilitySurfaceIdentityResolver resolver =
                new AccessibilitySurfaceIdentityResolver();
        FakeNode firstOwner = new FakeNode(true, "androidx.recyclerview.widget.RecyclerView",
                "stable-recycler", null);
        firstOwner.parent = new FakeNode(false, "android.view.ViewRootImpl", null, "root");
        FakeNode firstChild = new FakeNode(false, "android.widget.TextView",
                "virtual-child-a", null);
        firstChild.parent = firstOwner;

        FakeNode secondOwner = new FakeNode(true, "androidx.recyclerview.widget.RecyclerView",
                "stable-recycler", null);
        secondOwner.parent = new FakeNode(false, "android.view.ViewRootImpl", null, "root");
        FakeNode secondChild = new FakeNode(false, "android.widget.TextView",
                "virtual-child-b", null);
        secondChild.parent = secondOwner;

        AccessibilitySurfaceIdentityResolver.Identity first = resolver.resolveForNode(
                9, "com.example.app", firstChild);
        AccessibilitySurfaceIdentityResolver.Identity second = resolver.resolveForNode(
                9, "com.example.app", secondChild);

        assertTrue(first.sameSurface(second));
        assertTrue(first.isCacheable());
        assertEquals(1, firstChild.closeCount);
        assertEquals(1, firstOwner.closeCount);
        assertEquals(1, secondChild.closeCount);
        assertEquals(1, secondOwner.closeCount);
    }

    @Test public void nearestNestedOwnerWins() {
        AccessibilitySurfaceIdentityResolver resolver =
                new AccessibilitySurfaceIdentityResolver();
        FakeNode inner = new FakeNode(true, "androidx.recyclerview.widget.RecyclerView",
                "inner-list", null);
        inner.parent = new FakeNode(true, "android.widget.ScrollView", "outer-page", null);
        FakeNode source = new FakeNode(false, "android.widget.ImageView", "cell", null);
        source.parent = inner;

        AccessibilitySurfaceIdentityResolver.Identity identity = resolver.resolveForNode(
                4, "com.example.app", source);

        assertEquals(AccessibilitySurfaceIdentityResolver.OWNER_UNIQUE_ID,
                identity.ownerKind);
        AccessibilitySurfaceIdentityResolver.Identity outerOnly = resolver.resolveForNode(
                4, "com.example.app", sourceWithoutInnerOwner());
        assertFalse(identity.sameSurface(outerOnly));
    }

    @Test public void structuralFallbackIsLowAndNotCacheable() {
        AccessibilitySurfaceIdentityResolver resolver =
                new AccessibilitySurfaceIdentityResolver();
        FakeNode owner = new FakeNode(true, "android.webkit.WebView", null, null);
        owner.parent = new FakeNode(false, "android.view.ViewRootImpl", null, null);

        AccessibilitySurfaceIdentityResolver.Identity identity =
                resolver.resolveForNode(2, "com.example.app", owner);

        assertEquals(AccessibilitySurfaceIdentityResolver.OWNER_STRUCTURAL,
                identity.ownerKind);
        assertEquals(AccessibilitySurfaceIdentityResolver.CONFIDENCE_LOW,
                identity.confidence);
        assertFalse(identity.isCacheable());
        assertTrue(identity.isLowConfidence());
    }

    @Test public void nullSourceIsExplicitlyLowAndNonCacheable() {
        AccessibilitySurfaceIdentityResolver resolver =
                new AccessibilitySurfaceIdentityResolver();

        AccessibilitySurfaceIdentityResolver.Identity identity =
                resolver.resolveForNode(2, "com.example.app", null);

        assertEquals(AccessibilitySurfaceIdentityResolver.OWNER_NONE,
                identity.ownerKind);
        assertEquals(AccessibilitySurfaceIdentityResolver.CONFIDENCE_LOW,
                identity.confidence);
        assertFalse(identity.isCacheable());
    }

    @Test public void parentWalkStopsAtSixHops() {
        AccessibilitySurfaceIdentityResolver resolver =
                new AccessibilitySurfaceIdentityResolver();
        FakeNode[] nodes = new FakeNode[8];
        for (int index = nodes.length - 1; index >= 0; index--) {
            nodes[index] = new FakeNode(index == 7, "node-" + index,
                    "owner-" + index, null);
            if (index + 1 < nodes.length) nodes[index].parent = nodes[index + 1];
        }

        AccessibilitySurfaceIdentityResolver.Identity identity =
                resolver.resolveForNode(1, "com.example.app", nodes[0]);

        assertFalse(identity.isCacheable());
        assertEquals(AccessibilitySurfaceIdentityResolver.CONFIDENCE_LOW,
                identity.confidence);
        int parentReads = 0;
        for (FakeNode node : nodes) parentReads += node.parentReadCount;
        assertEquals(AccessibilitySurfaceIdentityResolver.MAX_PARENT_HOPS, parentReads);
        assertEquals(0, nodes[7].closeCount);
    }

    @Test public void packageAndWindowArePartOfTheSurfaceToken() {
        AccessibilitySurfaceIdentityResolver resolver =
                new AccessibilitySurfaceIdentityResolver();
        AccessibilitySurfaceIdentityResolver.Identity first = resolver.resolveForNode(
                1, "com.example.one", newOwner("same-owner"));
        AccessibilitySurfaceIdentityResolver.Identity differentWindow = resolver.resolveForNode(
                2, "com.example.one", newOwner("same-owner"));
        AccessibilitySurfaceIdentityResolver.Identity differentPackage = resolver.resolveForNode(
                1, "com.example.two", newOwner("same-owner"));

        assertFalse(first.sameSurface(differentWindow));
        assertFalse(first.sameSurface(differentPackage));
    }

    @Test public void verifiedStructureIgnoresRuntimeWindowAndUniqueIds() {
        AccessibilitySurfaceIdentityResolver resolver = new AccessibilitySurfaceIdentityResolver();
        AccessibilitySurfaceIdentityResolver.Identity first = resolver.resolveForNode(
                1, "com.example.app", nativeOwner("runtime-one", "feed"), name -> true);
        AccessibilitySurfaceIdentityResolver.Identity second = resolver.resolveForNode(
                92, "com.example.app", nativeOwner("runtime-two", "feed"), name -> true);
        assertTrue(first.durableDigest.matches("[0-9a-f]{64}"));
        assertEquals(first.durableDigest, second.durableDigest);
        assertFalse(first.sameSurface(second));
        assertEquals(first.durableDigest, first.learningDigest());
    }

    @Test public void unverifiedResourceShapedIdsAreSessionOnly() {
        AccessibilitySurfaceIdentityResolver resolver = new AccessibilitySurfaceIdentityResolver();
        AccessibilitySurfaceIdentityResolver.Identity first = resolver.resolveForNode(
                1, "com.example.app", nativeOwner("first", "feed"), name -> false);
        AccessibilitySurfaceIdentityResolver.Identity second = resolver.resolveForNode(
                2, "com.example.app", nativeOwner("first", "feed"), name -> false);
        assertEquals(null, first.durableDigest);
        assertNotEquals(first.learningDigest(), second.learningDigest());
        assertTrue(first.learningDigest().matches("[0-9a-f]{64}"));
    }

    @Test public void ordinaryCallbackDoesNotComputeADurableKey() {
        AccessibilitySurfaceIdentityResolver.Identity identity =
                new AccessibilitySurfaceIdentityResolver().resolveForNode(
                        1, "com.example.app", nativeOwner("first", "feed"));
        assertEquals(null, identity.durableDigest);
    }

    @Test public void rawDomIdsAndForeignResourcesNeverReachVerifier() {
        String[] invalidIds = {"private-page-id", "com.foreign.app:id/feed",
                "com.example.app:id/url?secret", "com.example.app:id/123"};
        for (String id : invalidIds) {
            AccessibilitySurfaceIdentityResolver.Identity identity =
                    new AccessibilitySurfaceIdentityResolver().resolveForNode(1, "com.example.app",
                            new FakeNode(true, "android.view.View", "virtual-owner", id), name -> {
                                throw new AssertionError("Unvalidated id must not reach Resources");
                            });
            assertEquals(null, identity.durableDigest);
        }
    }

    @Test public void browserStructureIsNeverDurableEvenWithACompiledResource() {
        FakeNode owner = nativeOwner("virtual-owner", "content");
        owner.parent = new FakeNode(false, "android.webkit.WebView", null, null);
        AccessibilitySurfaceIdentityResolver.Identity identity =
                new AccessibilitySurfaceIdentityResolver().resolveForNode(
                        1, "com.example.app", owner, name -> true);
        assertEquals(null, identity.durableDigest);
        assertFalse(identity.learningDigest().contains("content"));
    }

    @Test public void nestedOwnersAndAncestorStructureHaveDifferentKeys() {
        AccessibilitySurfaceIdentityResolver resolver = new AccessibilitySurfaceIdentityResolver();
        FakeNode inner = nativeOwner("inner", "feed");
        inner.parent = nativeOwner("outer", "page");
        String innerKey = resolver.resolveForNode(1, "com.example.app", inner, name -> true).durableDigest;
        String outerKey = resolver.resolveForNode(1, "com.example.app",
                nativeOwner("outer", "page"), name -> true).durableDigest;
        FakeNode other = nativeOwner("inner", "feed");
        other.parent = nativeOwner("outer", "other_page");
        String otherKey = resolver.resolveForNode(1, "com.example.app", other, name -> true).durableDigest;
        assertTrue(innerKey != null && outerKey != null && otherKey != null);
        assertNotEquals(innerKey, outerKey);
        assertNotEquals(innerKey, otherKey);
    }

    @Test public void recycledChildrenDoNotEnterDurableSignature() {
        AccessibilitySurfaceIdentityResolver resolver = new AccessibilitySurfaceIdentityResolver();
        FakeNode child = new FakeNode(false, "android.view.View", "private-item-123", "private-dom-id");
        child.parent = nativeOwner("one", "feed");
        String childKey = resolver.resolveForNode(1, "com.example.app", child, name -> true).durableDigest;
        String ownerKey = resolver.resolveForNode(1, "com.example.app",
                nativeOwner("different", "feed"), name -> true).durableDigest;
        assertTrue(childKey != null);
        assertEquals(ownerKey, childKey);
        assertEquals(1, child.closeCount);
        assertEquals(1, child.parent.closeCount);
    }

    @Test public void truncatedAncestryAndFailedVerifierNeverBecomeDurable() {
        FakeNode owner = nativeOwner("one", "feed");
        FakeNode tail = owner;
        for (int index = 0; index < 8; index++) {
            tail.parent = new FakeNode(false, "android.view.View", null, null);
            tail = tail.parent;
        }
        AccessibilitySurfaceIdentityResolver resolver = new AccessibilitySurfaceIdentityResolver();
        assertEquals(null, resolver.resolveForNode(1, "com.example.app", owner, name -> true).durableDigest);
        FakeNode broken = nativeOwner("two", "feed");
        AccessibilitySurfaceIdentityResolver.Identity failed = resolver.resolveForNode(
                1, "com.example.app", broken, name -> { throw new IllegalStateException("lookup"); });
        assertEquals(null, failed.durableDigest);
        assertFalse(failed.isCacheable());
        assertEquals(1, broken.closeCount);
    }

    @Test public void outcomeCountsDistinguishMissingSourceFromMissingOwnerAndFailure() {
        AccessibilitySurfaceIdentityResolver resolver = new AccessibilitySurfaceIdentityResolver();
        AccessibilitySurfaceIdentityResolver.Identity missing = resolver.resolveForNode(1, "com.example.app", null);
        assertFalse(missing.sourcePresent);
        assertEquals(0, missing.nodeCount);
        assertEquals(-1, missing.ownerDepth);
        FakeNode leaf = new FakeNode(false, "android.view.View", null, null);
        AccessibilitySurfaceIdentityResolver.Identity noOwner = resolver.resolveForNode(1, "com.example.app", leaf);
        assertTrue(noOwner.sourcePresent);
        assertEquals(1, noOwner.nodeCount);
        assertEquals(-1, noOwner.ownerDepth);
        assertFalse(noOwner.traversalFailed);
        AccessibilitySurfaceIdentityResolver.Identity failed = resolver.resolveForNode(1,
                "com.example.app", nativeOwner("one", "feed"), name -> { throw new IllegalStateException(); });
        assertTrue(failed.sourcePresent);
        assertTrue(failed.traversalFailed);
        assertFalse(failed.isCacheable());
        AccessibilitySurfaceIdentityResolver.Identity owner = resolver.resolveForNode(1, "com.example.app", sourceWithoutInnerOwner());
        assertEquals(2, owner.nodeCount);
        assertEquals(1, owner.ownerDepth);
    }

    private static FakeNode nativeOwner(String unique, String resource) {
        return new FakeNode(true, "android.widget.ScrollView", unique,
                "com.example.app:id/" + resource);
    }

    private static FakeNode newOwner(String uniqueId) {
        return new FakeNode(true, "android.widget.ScrollView", uniqueId, null);
    }

    private static FakeNode sourceWithoutInnerOwner() {
        FakeNode source = new FakeNode(false, "android.widget.ImageView", "cell", null);
        source.parent = new FakeNode(true, "android.widget.ScrollView", "outer-page", null);
        return source;
    }

    private static final class FakeNode implements AccessibilitySurfaceIdentityResolver.Node {
        private final boolean scrollable;
        private final String className;
        private final String uniqueId;
        private final String viewId;
        private FakeNode parent;
        private int closeCount;
        private int parentReadCount;

        private FakeNode(
                boolean scrollable,
                String className,
                String uniqueId,
                String viewId) {
            this.scrollable = scrollable;
            this.className = className;
            this.uniqueId = uniqueId;
            this.viewId = viewId;
        }

        @Override public boolean isScrollable() { return scrollable; }
        @Override public String uniqueId() { return uniqueId; }
        @Override public String viewId() { return viewId; }
        @Override public String className() { return className; }

        @Override public AccessibilitySurfaceIdentityResolver.Node parent() {
            parentReadCount++;
            return parent;
        }

        @Override public void close() { closeCount++; }
    }
}
