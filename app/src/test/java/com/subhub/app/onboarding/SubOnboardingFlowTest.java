package com.subhub.app.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.subhub.app.R;
import com.subhub.app.onboarding.OnboardingPage.Action;
import com.subhub.app.onboarding.OnboardingPage.Kind;
import com.subhub.app.onboarding.OnboardingPage.Scene;
import com.subhub.app.onboarding.OnboardingPage.Workspace;
import java.io.File;
import java.lang.reflect.Field;
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

public final class SubOnboardingFlowTest {
    private static final Map<Integer, String> copy = new HashMap<>();

    @BeforeClass public static void loadCopy() throws Exception {
        File file = new File("src/main/res/values/onboarding_sub.xml");
        if (!file.isFile()) file = new File("app/src/main/res/values/onboarding_sub.xml");
        assertTrue("Could not locate Sub onboarding copy", file.isFile());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        NodeList strings = factory.newDocumentBuilder().parse(file).getElementsByTagName("string");
        for (int index = 0; index < strings.getLength(); index++) {
            Element entry = (Element) strings.item(index);
            Field resource = R.string.class.getField(entry.getAttribute("name"));
            assertFalse(entry.getAttribute("name"), entry.getTextContent().trim().isEmpty());
            copy.put(resource.getInt(null), entry.getTextContent());
        }
    }

    @Test public void resumableSubJourneyRetainsLocalSetupWithoutControllerOrPermissionPages() {
        List<OnboardingPage> pages = SubOnboardingFlow.pages();
        Set<String> ids = new HashSet<>();
        for (OnboardingPage page : pages) {
            assertTrue(page.id, page.id.startsWith("sub_"));
            assertTrue("Duplicate resume identity: " + page.id, ids.add(page.id));
            assertEquals(page.id, Workspace.SUB, page.workspace);
            assertNotEquals(page.id, Kind.PERMISSIONS, page.kind);
            assertNotEquals(page.id, Scene.PERMISSIONS, page.scene);
            assertNotEquals(page.id, Scene.CONNECT_SUB, page.scene);
            assertNotEquals(page.id, Scene.CREATE_TASK, page.scene);
            assertNotEquals(page.id, Scene.REVIEW_TASK, page.scene);
            text(page);
        }
        assertEquals(1, pages.stream().filter(page -> page.kind == Kind.APPEARANCE).count());
        assertEquals(1, pages.stream().filter(page -> page.kind == Kind.WALLET).count());
        assertEquals(1, pages.stream().filter(page -> page.kind == Kind.APPS).count());
        assertEquals(1, pages.stream().filter(page -> page.kind == Kind.SECURITY).count());
        assertTrue(indexOf(Scene.CONNECT_DOM) < indexOf(Scene.SUB_TASKS));
        assertTrue(indexOf("sub_appearance") < indexOf("sub_wallet"));
        assertTrue(indexOf("sub_wallet") < indexOf("sub_apps"));
        assertTrue(indexOf("sub_allowed_features") < indexOf(Scene.HARDCORE));
        assertThrows(UnsupportedOperationException.class, () -> pages.remove(0));
    }

    @Test public void pairingExplainsBothApprovalsSharingAndAuthorizedRevocationBeforeRouting() {
        OnboardingPage pairing = scene(Scene.CONNECT_DOM);
        assertEquals(Kind.STORY, pairing.kind);
        assertEquals(Action.PAIR_DOM, pairing.action);
        String visible = text(pairing);
        contains(visible, "optional", "invitation", "fingerprint", "both devices",
                "dom must also approve", "app names", "app usage", "activity",
                "session state", "keyholder authorization", "unpair", "already shared");
    }

    @Test public void proofIsSubmittedWithCompletionRequestAndDoesNotBypassDomReview() {
        OnboardingPage tasks = scene(Scene.SUB_TASKS);
        assertEquals(Kind.STORY, tasks.kind);
        assertEquals(Action.NONE, tasks.action);
        assertTrue("Submission must be explained before the verdict",
                text(tasks).indexOf("add proof") < text(tasks).indexOf("wait for dom review"));
        contains(text(tasks), "instructions", "deadline", "completion request",
                "text or up to five attachments", "pending review", "accepts or denies",
                "without in-app proof", "acceptance is still required");
    }

    @Test public void optionalHardcoreExplainsOverrideReleaseAndSeparatePaymentConsent() {
        OnboardingPage hardcore = scene(Scene.HARDCORE);
        assertEquals(Kind.STORY, hardcore.kind);
        assertEquals(Action.HARDCORE, hardcore.action);
        String label = text(hardcore.actionLabel).toLowerCase(Locale.ROOT);
        assertTrue(label, label.contains("settings"));
        assertFalse(label, label.contains("enable") || label.contains("turn on"));
        contains(text(hardcore), "optional", "opt in locally", "accessibility", "device admin",
                "does not start protection", "local consent", "overrides allowed features",
                "turning it off", "restores your choices", "deactivation", "separate consent",
                "if already enabled", "active service lock", "within your caps");
        contains(text(page("sub_allowed_features")), "disable", "locally", "queued updates",
                "only after you enable it locally");
        contains(text(page("sub_wallet")), "do not authorize payment", "separate consent");
    }

    @Test public void onlyExplicitPairingAndHardcoreActionsLeaveTheWalkthrough() {
        int actions = 0;
        for (OnboardingPage page : SubOnboardingFlow.pages()) {
            if (page.action == Action.NONE) {
                assertEquals(page.id, 0, page.actionLabel);
                continue;
            }
            actions++;
            assertEquals(page.id, Kind.STORY, page.kind);
            assertTrue(page.id, page.actionLabel != 0);
            assertTrue(page.id, page.scene == Scene.CONNECT_DOM && page.action == Action.PAIR_DOM
                    || page.scene == Scene.HARDCORE && page.action == Action.HARDCORE);
        }
        assertEquals(2, actions);
        OnboardingPage security = page("sub_security");
        assertEquals(Kind.SECURITY, security.kind);
        assertEquals(Scene.SECURITY, security.scene);
        contains(text(security), "optional when unpaired", "keyholder code", "recovery",
                "unpairing", "ordinary contract changes stay with your dom");
    }

    private static OnboardingPage scene(Scene scene) {
        return SubOnboardingFlow.pages().stream().filter(page -> page.scene == scene)
                .findFirst().orElseThrow(() -> new AssertionError("Missing scene: " + scene));
    }

    private static OnboardingPage page(String id) {
        return SubOnboardingFlow.pages().stream().filter(page -> page.id.equals(id))
                .findFirst().orElseThrow(() -> new AssertionError("Missing page: " + id));
    }

    private static int indexOf(Scene scene) { return indexOf(scene(scene).id); }

    private static int indexOf(String id) {
        List<OnboardingPage> pages = SubOnboardingFlow.pages();
        for (int index = 0; index < pages.size(); index++) {
            if (pages.get(index).id.equals(id)) return index;
        }
        fail("Missing page: " + id);
        return -1;
    }

    private static String text(OnboardingPage page) {
        StringBuilder visible = new StringBuilder(text(page.title)).append(' ')
                .append(text(page.description));
        for (OnboardingPage.Detail detail : page.details) {
            visible.append(' ').append(text(detail.title)).append(' ').append(text(detail.body));
        }
        if (page.actionLabel != 0) visible.append(' ').append(text(page.actionLabel));
        return visible.toString().toLowerCase(Locale.ROOT);
    }

    private static String text(int resource) {
        assertTrue("Missing Sub copy for resource " + resource, copy.containsKey(resource));
        return copy.get(resource);
    }

    private static void contains(String visible, String... required) {
        for (String phrase : required) assertTrue("Missing explanation: " + phrase, visible.contains(phrase));
    }
}
