package com.subhub.app.onboarding;

import static org.junit.Assert.*;
import com.subhub.app.security.DeviceRolePreferences.Mode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class OnboardingFlowTest {
    @Test public void eachRoleEndsWithOneDevicePermissionPageAndStableUniqueIds() {
        for (Mode mode : Mode.values()) {
            List<OnboardingPage> pages = OnboardingFlow.pages(mode);
            Set<String> ids = new HashSet<>();
            int permissions = 0;
            for (OnboardingPage page : pages) {
                assertTrue(ids.add(page.id));
                if (page.kind == OnboardingPage.Kind.PERMISSIONS) permissions++;
            }
            assertEquals(1, permissions);
            assertEquals(OnboardingPage.Kind.PERMISSIONS, pages.get(pages.size() - 1).kind);
            assertEquals(OnboardingPage.Workspace.DEVICE, pages.get(pages.size() - 1).workspace);
            assertThrows(UnsupportedOperationException.class, pages::clear);
            for (int step = 1; step <= pages.size(); step++)
                assertEquals(step, OnboardingFlow.index(pages, OnboardingFlow.pageId(pages, step)));
        }
    }

    @Test public void olderLocalDraftsResumeAtTheEquivalentEditorWithoutGrantingDomCapabilities() {
        List<OnboardingPage> sub = OnboardingFlow.pages(Mode.SUB_ONLY);
        assertKind(sub, 2, 1, OnboardingPage.Kind.APPS);
        assertKind(sub, 2, 2, OnboardingPage.Kind.APPS);
        assertKind(sub, 2, 3, OnboardingPage.Kind.APPS);
        assertKind(sub, 2, 4, OnboardingPage.Kind.SECURITY);
        assertKind(sub, 2, 5, OnboardingPage.Kind.PERMISSIONS);
        assertKind(sub, 1, 1, OnboardingPage.Kind.APPS);
        assertKind(sub, 1, 2, OnboardingPage.Kind.APPS);
        assertEquals(OnboardingFlow.ROLE, OnboardingFlow.legacyPage(sub, 2, 0));
    }

    @Test public void olderDomLocalEditorDraftsResolveToSecurityAndNeverAddSubEditors() {
        List<OnboardingPage> dom = OnboardingFlow.pages(Mode.DOM_ONLY);
        for (int old = 1; old <= 4; old++) {
            OnboardingPage page = dom.get(OnboardingFlow.index(dom, OnboardingFlow.legacyPage(dom, 2, old)) - 1);
            assertEquals(OnboardingPage.Kind.SECURITY, page.kind);
        }
        for (OnboardingPage page : dom)
            assertFalse(page.kind == OnboardingPage.Kind.APPS || page.kind == OnboardingPage.Kind.APPEARANCE
                    || page.kind == OnboardingPage.Kind.WALLET);
        assertEquals(0, OnboardingFlow.index(dom, "sub_apps"));
        assertEquals(OnboardingFlow.ROLE, OnboardingFlow.pageId(dom, -1));
    }

    @Test public void removedStablePagesResumeInTheirOwnRoleWithoutRepeatingPairing() {
        for (Mode mode : Mode.values()) {
            List<OnboardingPage> pages = OnboardingFlow.pages(mode);
            for (OnboardingPage page : pages)
                assertEquals(page.id, OnboardingFlow.resumePage(pages, page.id));
            assertEquals(OnboardingFlow.ROLE, OnboardingFlow.resumePage(pages, "unknown_future_page"));
            assertEquals(OnboardingFlow.ROLE, OnboardingFlow.resumePage(pages, null));
        }
        List<OnboardingPage> sub = OnboardingFlow.pages(Mode.SUB_ONLY);
        List<OnboardingPage> dom = OnboardingFlow.pages(Mode.DOM_ONLY);
        List<OnboardingPage> both = OnboardingFlow.pages(Mode.BOTH);
        assertEquals("sub_apps", OnboardingFlow.resumePage(sub, "sub_appearance"));
        assertEquals("sub_apps", OnboardingFlow.resumePage(sub, "sub_wallet"));
        assertEquals("sub_apps", OnboardingFlow.resumePage(both, "sub_wallet"));
        assertEquals("dom_create_task", OnboardingFlow.resumePage(dom, "dom_hardcore"));
        assertEquals("dom_create_task", OnboardingFlow.resumePage(both, "dom_hardcore"));
        assertEquals("dom_wallet", OnboardingFlow.resumePage(both, "dom_add_sub"));
        assertEquals("dom_add_sub", OnboardingFlow.resumePage(dom, "dom_add_sub"));
        assertEquals(OnboardingFlow.ROLE, OnboardingFlow.resumePage(sub, "dom_add_sub"));
        assertEquals(7, sub.size()); assertEquals(9, dom.size()); assertEquals(13, both.size());
    }

    private void assertKind(List<OnboardingPage> pages, int version, int old, OnboardingPage.Kind expected) {
        String id = OnboardingFlow.legacyPage(pages, version, old);
        assertEquals(expected, pages.get(OnboardingFlow.index(pages, id) - 1).kind);
    }
}
