package com.subhub.app.onboarding;

import com.subhub.app.R;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import static org.junit.Assert.*;

public final class DomOnboardingFlowTest {
    @Test public void establishesAuthorityAndConsentBeforeTaskAuthoringAndReview() {
        List<OnboardingPage> pages = DomOnboardingFlow.pages();
        int security = position(pages, OnboardingPage.Scene.SECURITY);
        int pairing = position(pages, OnboardingPage.Scene.CONNECT_SUB);
        int contract = position(pages, OnboardingPage.Scene.CONTRACTS);
        int consent = position(pages, OnboardingPage.Scene.LOCAL_FEATURES);
        int hardcore = position(pages, OnboardingPage.Scene.HARDCORE);
        int create = position(pages, OnboardingPage.Scene.CREATE_TASK);
        int review = position(pages, OnboardingPage.Scene.REVIEW_TASK);

        assertEquals(0, security);
        assertTrue(security < pairing && pairing < contract);
        assertTrue(contract < consent && consent < hardcore && hardcore < create);
        assertEquals("Task review needs its own page immediately after creation", create + 1, review);
        assertEquals(OnboardingPage.Kind.STORY, pages.get(create).kind);
        assertEquals(OnboardingPage.Kind.STORY, pages.get(review).kind);
    }

    @Test public void domSetupCannotConfigureTheSubOrEnableHardcore() {
        List<OnboardingPage> pages = DomOnboardingFlow.pages();
        Set<String> ids = new HashSet<>();
        Set<OnboardingPage.Scene> scenes = new HashSet<>();
        int securityPages = 0;
        int pairingActions = 0;
        for (OnboardingPage page : pages) {
            assertTrue("Dom draft identifiers need their own namespace", page.id.startsWith("dom_"));
            assertTrue("Duplicate page: " + page.id, ids.add(page.id));
            assertTrue("Each topic needs a distinct hero scene", scenes.add(page.scene));
            if (page.kind == OnboardingPage.Kind.SECURITY) {
                securityPages++;
                assertEquals(OnboardingPage.Scene.SECURITY, page.scene);
                assertEquals(OnboardingPage.Workspace.DEVICE, page.workspace);
            } else {
                assertEquals("Dom has no local feature or permissions setup", OnboardingPage.Kind.STORY, page.kind);
                assertEquals(OnboardingPage.Workspace.DOM, page.workspace);
            }
            if (page.action != OnboardingPage.Action.NONE) {
                pairingActions++;
                assertEquals(OnboardingPage.Action.PAIR_SUB, page.action);
                assertEquals(OnboardingPage.Scene.CONNECT_SUB, page.scene);
                assertTrue(page.actionLabel != 0);
            }
        }
        assertEquals(1, securityPages);
        assertEquals(1, pairingActions);
        assertEquals(OnboardingPage.Action.NONE, pages.get(position(pages, OnboardingPage.Scene.HARDCORE)).action);
        assertThrows(UnsupportedOperationException.class, pages::clear);
    }

    @Test public void invitationCopyExplainsApprovalOnBothDevices() throws Exception {
        String pairing = copy(OnboardingPage.Scene.CONNECT_SUB);
        requireTerms(pairing, "invitation", "qr", "sub reviews", "requests pairing", "fingerprint", "approve");
        assertFalse(pairing.contains("automatically paired"));
    }

    @Test public void visibleProfilePathsUseTheSubsNavigationLabel() throws Exception {
        requireTerms(copy(OnboardingPage.Scene.CONNECT_SUB), "in subs");
        requireTerms(copy(OnboardingPage.Scene.CONTRACTS), "open subs", "select a sub", "open contract");
        requireTerms(copy(OnboardingPage.Scene.CREATE_TASK), "in subs", "open tasks");
        for (String value : strings().values()) {
            assertFalse("Visible paths must use the navigation label Subs", value.matches("(?is).*\\bpeople\\b.*"));
        }
    }

    @Test public void featureConsentAndHardcoreRemainSeparateLocalDecisions() throws Exception {
        String consent = copy(OnboardingPage.Scene.LOCAL_FEATURES);
        requireTerms(consent, "sub controls", "own device", "disable", "censor", "limits", "tributes",
                "whispers", "popup storm", "tasks", "cannot run", "queued", "unpair", "authentication",
                "stops further sharing");
        String hardcore = copy(OnboardingPage.Scene.HARDCORE);
        requireTerms(hardcore, "sub device", "opt in locally", "android approval", "accessibility",
                "device admin", "overrides allowed features", "off restores",
                "off locally in settings on the sub device", "ending a session remotely does not disable it",
                "cannot make android impossible to bypass");
        assertFalse(hardcore.contains("dom can release hardcore"));
    }

    @Test public void taskCreationTeachesSchedulingAttachmentsAndOptionalRefusal() throws Exception {
        String create = copy(OnboardingPage.Scene.CREATE_TASK);
        requireTerms(create, "choose a sub", "subs", "tasks", "instructions", "future start", "duration",
                "time zone", "repeat days", "one image, audio or video", "allow refusal", "turn on",
                "one occurrence", "entire recurring series");
    }

    @Test public void reviewExplainsProofAndOutsideRequestsNeedASubmissionSpecificVerdict() throws Exception {
        String review = copy(OnboardingPage.Scene.REVIEW_TASK);
        requireTerms(review, "pending", "submission", "completion text", "each image, audio or video",
                "own task occurrence", "outside the app", "no proof", "still needs your verdict",
                "accept or deny", "accepted or denied", "proof alone does not complete");
    }

    @Test public void everyPageAndDetailResolvesToFocusedCopy() throws Exception {
        Map<Integer, String> strings = strings();
        for (OnboardingPage page : DomOnboardingFlow.pages()) {
            String title = text(strings, page.title);
            assertTrue("Keep native headings short: " + title, title.length() <= 42);
            assertFalse(text(strings, page.description).contains("\n"));
            assertTrue("Keep detail groups readable", page.details.size() <= 3);
            for (OnboardingPage.Detail detail : page.details) {
                text(strings, detail.title);
                assertFalse(text(strings, detail.body).contains("\n"));
            }
            if (page.action != OnboardingPage.Action.NONE) text(strings, page.actionLabel);
        }
    }

    private static int position(List<OnboardingPage> pages, OnboardingPage.Scene scene) {
        int found = -1;
        for (int index = 0; index < pages.size(); index++) {
            if (pages.get(index).scene != scene) continue;
            assertEquals("Topic must appear once: " + scene, -1, found);
            found = index;
        }
        assertTrue("Missing journey topic: " + scene, found >= 0);
        return found;
    }

    private static String copy(OnboardingPage.Scene scene) throws Exception {
        Map<Integer, String> strings = strings();
        List<OnboardingPage> pages = DomOnboardingFlow.pages();
        OnboardingPage page = pages.get(position(pages, scene));
        StringBuilder copy = new StringBuilder(text(strings, page.title))
                .append(' ').append(text(strings, page.description));
        for (OnboardingPage.Detail detail : page.details) {
            copy.append(' ').append(text(strings, detail.title))
                    .append(' ').append(text(strings, detail.body));
        }
        return copy.toString().toLowerCase(Locale.ROOT);
    }

    private static void requireTerms(String copy, String... terms) {
        for (String term : terms) assertTrue("Missing explanation: " + term, copy.contains(term));
    }

    private static String text(Map<Integer, String> strings, int resource) {
        String text = strings.get(resource);
        assertNotNull("Copy must resolve in onboarding_dom.xml: " + resource, text);
        assertFalse("Copy must not be empty", text.trim().isEmpty());
        return text;
    }

    private static Map<Integer, String> strings() throws Exception {
        File file = new File("src/main/res/values/onboarding_dom.xml");
        if (!file.isFile()) file = new File("app/src/main/res/values/onboarding_dom.xml");
        assertTrue("Could not locate onboarding_dom.xml", file.isFile());
        NodeList entries = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(file).getElementsByTagName("string");
        Map<Integer, String> result = new HashMap<>();
        for (int index = 0; index < entries.getLength(); index++) {
            Element entry = (Element) entries.item(index);
            int resource = R.string.class.getField(entry.getAttribute("name")).getInt(null);
            assertNull("Duplicate string resource", result.put(resource, entry.getTextContent()));
        }
        return result;
    }
}
