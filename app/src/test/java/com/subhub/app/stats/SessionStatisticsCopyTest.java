package com.subhub.app.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Keeps session statistics concise without removing the elapsed metric or timestamp. */
public final class SessionStatisticsCopyTest {
    private Map<String, String> strings() throws Exception {
        File file = new File("src/main/res/values/stats_session_time.xml");
        if (!file.isFile()) file = new File("app/src/main/res/values/stats_session_time.xml");
        assertTrue("Could not locate session statistics strings", file.isFile());
        NodeList entries = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(file).getElementsByTagName("string");
        Map<String, String> result = new HashMap<>();
        for (int index = 0; index < entries.getLength(); index++) {
            Element entry = (Element) entries.item(index);
            result.put(entry.getAttribute("name"), entry.getTextContent());
        }
        return result;
    }

    @Test public void localAndRemoteExplanatorySubtextsAreRemoved() throws Exception {
        Map<String, String> copy = strings();
        assertFalse(copy.containsKey("stats_session_elapsed_explanation"));
        assertFalse(copy.containsKey("stats_session_remote_observed"));
    }

    @Test public void elapsedMetricAndObservationTimestampRemain() throws Exception {
        Map<String, String> copy = strings();
        assertEquals("Session elapsed", copy.get("stats_session_elapsed"));
        assertEquals("As of %1$s", copy.get("stats_session_as_of"));
    }
}
