package com.subhub.app.onboarding;

import com.subhub.app.R;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.BeforeClass;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import static com.subhub.app.onboarding.OnboardingPage.Action;
import static com.subhub.app.onboarding.OnboardingPage.Kind;
import static com.subhub.app.onboarding.OnboardingPage.Scene;
import static com.subhub.app.onboarding.OnboardingPage.Workspace;
import static org.junit.Assert.*;

public class BothOnboardingFlowTest {
    private static final Map<Integer, String> copy = new HashMap<>();

    @BeforeClass public static void loadBothCopy() throws Exception {
        File file = new File("src/main/res/values/onboarding_both.xml");
        if (!file.isFile()) file = new File("app/src/main/res/values/onboarding_both.xml");
        assertTrue("Could not locate Both onboarding copy", file.isFile());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        NodeList entries = factory.newDocumentBuilder().parse(file).getElementsByTagName("string");
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            int resource = R.string.class.getField(entry.getAttribute("name")).getInt(null);
            assertNull("Duplicate Both string", copy.put(resource, entry.getTextContent()));
        }
    }

    @Test public void requiredJourneyPairsOnceAndCoversBothSidesOfTheTaskCycle() {
        List<OnboardingPage> pages = BothOnboardingFlow.pages();
        assertEquals(Workspace.SUB, onlyScene(pages, Scene.CONNECT_DOM).workspace);
        assertEquals(Workspace.SUB, onlyScene(pages, Scene.SUB_TASKS).workspace);
        assertEquals(Workspace.SUB, onlyScene(pages, Scene.HARDCORE).workspace);
        assertFalse(pages.stream().anyMatch(page -> page.scene == Scene.CONNECT_SUB));
        assertEquals(Workspace.DOM, onlyScene(pages, Scene.WALLET_RULES).workspace);
        assertEquals(Workspace.DOM, onlyScene(pages, Scene.CENSOR_SETTINGS).workspace);
        assertEquals(Workspace.DOM, onlyScene(pages, Scene.CREATE_TASK).workspace);
        assertEquals(Workspace.DOM, onlyScene(pages, Scene.REVIEW_TASK).workspace);
        assertTrue(pages.stream().anyMatch(page -> page.scene == Scene.CONTRACTS
                && page.workspace == Workspace.DOM));
        for (Kind kind : new Kind[] {Kind.APPS, Kind.SECURITY}) {
            assertEquals("Required device setup: " + kind, 1,
                    pages.stream().filter(page -> page.kind == kind).count());
        }
    }

    @Test public void roleHandoffKeepsSubLessonsBeforeDomLessonsInTheirOriginalOrder() {
        List<OnboardingPage> pages = BothOnboardingFlow.pages();
        assertEquals("Start directly with the Sub journey", Scene.CONNECT_DOM, pages.get(0).scene);
        assertEquals(Workspace.SUB, pages.get(0).workspace);

        int handoff = indexOf(pages, "both_dom_handoff");
        assertTrue(handoff > 1);
        assertTrue(handoff < pages.size() - 1);
        assertEquals(Scene.SWITCH_ROLES, pages.get(handoff).scene);
        assertEquals(Workspace.DOM, pages.get(handoff).workspace);
        for (int i = 0; i < handoff; i++) {
            assertNotEquals("Dom controls appear after the handoff: " + pages.get(i).id,
                    Workspace.DOM, pages.get(i).workspace);
        }
        for (int i = handoff + 1; i < pages.size(); i++) {
            assertNotEquals("Sub lessons belong before the handoff: " + pages.get(i).id,
                    Workspace.SUB, pages.get(i).workspace);
        }
        assertTrue(indexOfScene(pages, Scene.CONNECT_DOM) < handoff);
        assertTrue(indexOfScene(pages, Scene.SUB_TASKS) < handoff);
        assertEquals(handoff + 1, indexOfScene(pages, Scene.WALLET_RULES));
        assertEquals(handoff + 2, indexOfScene(pages, Scene.CENSOR_SETTINGS));
        assertTrue(indexOfScene(pages, Scene.CREATE_TASK) < indexOfScene(pages, Scene.REVIEW_TASK));
        assertReusedOrder(SubOnboardingFlow.pages(), pages.subList(0, handoff));
        assertReusedOrder(DomOnboardingFlow.pages(), pages.subList(handoff + 1, pages.size()));
    }

    @Test public void sharedSetupIsNotRepeatedAndPermissionsRemainWithTheActivity() {
        List<OnboardingPage> pages = BothOnboardingFlow.pages();
        Set<String> ids = new HashSet<>();
        for (OnboardingPage page : pages) {
            assertTrue("Duplicate lesson: " + page.id, ids.add(page.id));
            assertNotEquals(Kind.PERMISSIONS, page.kind);
            assertNotEquals(Scene.PERMISSIONS, page.scene);
        }
        assertEquals(1, pages.stream().filter(page -> page.kind == Kind.SECURITY
                || page.scene == Scene.SECURITY).count());
        assertEquals(1, pages.stream().filter(page -> page.kind == Kind.STORY
                && page.scene == Scene.LOCAL_FEATURES).count());
        assertEquals(1, pages.stream().filter(page -> page.scene == Scene.HARDCORE).count());
        assertEquals(1, pages.stream().filter(page -> page.scene == Scene.SWITCH_ROLES).count());
        int handoff = indexOf(pages, "both_dom_handoff");
        assertTrue(indexOf(pages, "sub_allowed_features") < handoff);
        assertTrue(indexOfScene(pages, Scene.HARDCORE) < handoff);
        assertTrue(indexOfScene(pages, Scene.SECURITY) < handoff);
    }

    @Test public void actionsRetainConsentAndWorkspaceBoundaries() {
        List<OnboardingPage> pages = BothOnboardingFlow.pages();
        assertEquals(Action.PAIR_DOM, onlyScene(pages, Scene.CONNECT_DOM).action);
        assertFalse(pages.stream().anyMatch(page -> page.action == Action.PAIR_SUB));
        assertEquals(Action.HARDCORE, onlyScene(pages, Scene.HARDCORE).action);
        for (OnboardingPage page : pages) {
            switch (page.action) {
                case PAIR_DOM:
                    assertEquals(Scene.CONNECT_DOM, page.scene);
                    assertEquals(Workspace.SUB, page.workspace);
                    assertTrue(page.actionLabel != 0);
                    break;
                case PAIR_SUB:
                    assertEquals(Scene.CONNECT_SUB, page.scene);
                    assertEquals(Workspace.DOM, page.workspace);
                    assertTrue(page.actionLabel != 0);
                    break;
                case HARDCORE:
                    assertEquals(Scene.HARDCORE, page.scene);
                    assertEquals(Workspace.SUB, page.workspace);
                    assertTrue(page.actionLabel != 0);
                    break;
                default:
                    assertEquals(Action.NONE, page.action);
                    assertEquals(0, page.actionLabel);
            }
            if (page.id.startsWith("both_")) {
                assertEquals(Kind.STORY, page.kind);
                assertEquals(Action.NONE, page.action);
            }
        }
        OnboardingPage handoff = pages.get(indexOf(pages, "both_dom_handoff"));
        assertTrue(handoff.details.stream().anyMatch(detail ->
                detail.body == R.string.onboarding_both_access_body));
    }

    @Test public void reusedLessonsKeepTheirIdsCopyAndActionsAndResultsAreImmutable() {
        List<OnboardingPage> pages = BothOnboardingFlow.pages();
        List<OnboardingPage> original = new ArrayList<>(SubOnboardingFlow.pages());
        original.addAll(DomOnboardingFlow.pages());
        for (OnboardingPage source : original) {
            if (source.kind == Kind.STORY && source.scene != Scene.SWITCH_ROLES
                    && source.scene != Scene.SECURITY && source.scene != Scene.PERMISSIONS
                    && source.scene != Scene.LOCAL_FEATURES && source.scene != Scene.HARDCORE
                    && source.scene != Scene.CONNECT_SUB) {
                assertTrue("Keep each role-specific lesson: " + source.id,
                        pages.stream().anyMatch(page -> page.id.equals(source.id)));
            }
        }
        for (OnboardingPage page : pages) {
            assertTrue(page.title != 0);
            assertTrue(page.description != 0);
            if (page.id.startsWith("both_")) continue;
            OnboardingPage source = original.stream().filter(candidate -> candidate.id.equals(page.id))
                    .findFirst().orElseThrow(() -> new AssertionError("Unexpected lesson: " + page.id));
            assertEquals(source.title, page.title);
            assertEquals(source.description, page.description);
            assertEquals(source.scene, page.scene);
            assertEquals(source.workspace, page.workspace);
            assertEquals(source.kind, page.kind);
            assertEquals(source.action, page.action);
            assertEquals(source.actionLabel, page.actionLabel);
            assertEquals(source.details.size(), page.details.size());
            for (int i = 0; i < source.details.size(); i++) {
                assertEquals(source.details.get(i).title, page.details.get(i).title);
                assertEquals(source.details.get(i).body, page.details.get(i).body);
            }
        }
        assertThrows(UnsupportedOperationException.class, pages::clear);
        assertThrows(UnsupportedOperationException.class, () -> pages.get(0).details.clear());
        assertEquals(pages.size(), BothOnboardingFlow.pages().size());
    }

    @Test public void handoffTeachesExplicitRoleSwitchingWithoutEndingTheSession() {
        String orientation = copyFor("both_dom_handoff");
        requireTerms(orientation, "sub and dom", "dom workspace", "menu", "unlocking",
                "preserves settings", "active session");
    }

    @Test public void domPreviewDoesNotImplyAnUnlockedSessionOrOverrideRemoteAuthority() {
        String handoff = copyFor("both_dom_handoff");
        requireTerms(handoff, "this device", "connected sub", "separate contract", "tasks",
                "preview dom controls", "unlock dom separately", "remote dom", "recovery",
                "unpairing");
        for (OnboardingPage page : BothOnboardingFlow.pages()) {
            if (!page.id.startsWith("both_")) continue;
            assertTrue(text(page.title).length() <= 42);
            assertFalse(text(page.description).contains("\n"));
            assertTrue(page.details.size() <= 3);
            for (OnboardingPage.Detail detail : page.details) {
                assertTrue(text(detail.title).length() <= 42);
                assertFalse(text(detail.body).contains("\n"));
            }
        }
    }

    private static String copyFor(String id) {
        List<OnboardingPage> pages = BothOnboardingFlow.pages();
        OnboardingPage page = pages.get(indexOf(pages, id));
        StringBuilder result = new StringBuilder(text(page.title)).append(' ')
                .append(text(page.description));
        for (OnboardingPage.Detail detail : page.details) {
            result.append(' ').append(text(detail.title)).append(' ').append(text(detail.body));
        }
        return result.toString().toLowerCase(Locale.ROOT);
    }

    private static String text(int resource) {
        String value = copy.get(resource);
        assertNotNull("Missing Both copy: " + resource, value);
        assertFalse(value.trim().isEmpty());
        return value;
    }

    private static void requireTerms(String value, String... terms) {
        for (String term : terms) assertTrue("Missing explanation: " + term, value.contains(term));
    }

    private static OnboardingPage onlyScene(List<OnboardingPage> pages, Scene scene) {
        List<OnboardingPage> matches = new ArrayList<>();
        for (OnboardingPage page : pages) if (page.scene == scene) matches.add(page);
        assertEquals("One dedicated lesson: " + scene, 1, matches.size());
        return matches.get(0);
    }

    private static int indexOfScene(List<OnboardingPage> pages, Scene scene) {
        return indexOf(pages, onlyScene(pages, scene).id);
    }

    private static int indexOf(List<OnboardingPage> pages, String id) {
        for (int i = 0; i < pages.size(); i++) if (pages.get(i).id.equals(id)) return i;
        throw new AssertionError("Missing lesson: " + id);
    }

    private static void assertReusedOrder(List<OnboardingPage> source, List<OnboardingPage> reused) {
        int previous = -1;
        for (OnboardingPage page : reused) {
            int index = indexOf(source, page.id);
            assertTrue("Preserve lesson order: " + page.id, index > previous);
            previous = index;
        }
    }
}
