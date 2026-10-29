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
        assertKind(sub, 2, 1, OnboardingPage.Kind.APPEARANCE);
        assertKind(sub, 2, 2, OnboardingPage.Kind.WALLET);
        assertKind(sub, 2, 3, OnboardingPage.Kind.APPS);
        assertKind(sub, 2, 4, OnboardingPage.Kind.SECURITY);
        assertKind(sub, 2, 5, OnboardingPage.Kind.PERMISSIONS);
        assertKind(sub, 1, 1, OnboardingPage.Kind.APPS);
        assertKind(sub, 1, 2, OnboardingPage.Kind.APPEARANCE);
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

    private void assertKind(List<OnboardingPage> pages, int version, int old, OnboardingPage.Kind expected) {
        String id = OnboardingFlow.legacyPage(pages, version, old);
        assertEquals(expected, pages.get(OnboardingFlow.index(pages, id) - 1).kind);
    }
}
