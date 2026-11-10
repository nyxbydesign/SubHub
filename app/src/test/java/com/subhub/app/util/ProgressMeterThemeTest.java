package com.subhub.app.util;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Resource/source contracts supplement, rather than replace, native meter pixel tests. */
public final class ProgressMeterThemeTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    @Test public void mutedFillKeepsGraphicalContrastAgainstEveryAdjacentSurface() throws Exception {
        Map<String, String> colors = values("res/values/colors.xml", "color");
        colors.putAll(values("res/values/progress_meter.xml", "color"));
        double fill = luminance(color(colors, "progress_fill"));
        for (String background : new String[]{"progress_track", "progress_outline", "surface_card",
                "surface_card_raised", "background"}) {
            double surface = luminance(color(colors, background));
            assertTrue(background + " needs 3:1 graphical contrast", (fill + .05) / (surface + .05) >= 3);
        }
        // No nearly-white endpoint or highlight: stay below the existing quiet control indicator.
        assertTrue(fill < luminance(color(colors, "control_checked_indicator")));
        assertTrue(fill < luminance(color(colors, "accent_text")));
    }

    @Test public void commonDrawableAndStyleKeepRoundedExactDirectionalGeometry() throws Exception {
        Document style = xml("res/values/progress_meter.xml");
        Element meter = (Element) style.getElementsByTagName("style").item(0);
        assertEquals("Widget.SubHub.ProgressMeter", meter.getAttribute("name"));
        Map<String, String> items = entries(meter.getElementsByTagName("item"));
        assertEquals("@drawable/bg_progress_meter", items.get("android:progressDrawable"));
        assertEquals("@null", items.get("android:progressTint"));
        assertEquals("@null", items.get("android:progressBackgroundTint"));
        assertEquals("@color/progress_fill", items.get("android:indeterminateTint"));
        assertEquals("true", items.get("android:mirrorForRtl"));
        assertEquals("0dp", items.get("android:padding"));
        assertEquals("@dimen/progress_meter_height", items.get("android:minHeight"));
        assertEquals("@dimen/progress_meter_height", items.get("android:maxHeight"));
        Document drawable = xml("res/drawable/bg_progress_meter.xml");
        assertEquals(0, drawable.getElementsByTagName("gradient").getLength());
        NodeList scales = drawable.getElementsByTagName("scale");
        assertEquals(1, scales.getLength());
        Element scale = (Element) scales.item(0);
        assertEquals("100%", scale.getAttributeNS(ANDROID, "scaleWidth"));
        assertEquals("left", scale.getAttributeNS(ANDROID, "scaleGravity"));
        assertEquals("@color/progress_fill", ((Element) scale.getElementsByTagName("solid").item(0))
                .getAttributeNS(ANDROID, "color"));
        NodeList corners = drawable.getElementsByTagName("corners");
        assertEquals(2, corners.getLength());
        for (int i = 0; i < corners.getLength(); i++)
            assertEquals("@dimen/progress_meter_radius", ((Element) corners.item(i)).getAttributeNS(ANDROID, "radius"));
    }

    @Test public void allXmlHorizontalBarsUseTheSameStyleAndHeightWithoutTintOverrides() throws Exception {
        int count = 0;
        try (Stream<java.nio.file.Path> paths = Files.walk(source("res/layout").toPath())) {
            for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) paths.filter(p -> p.toString().endsWith(".xml"))::iterator) {
                NodeList bars = parse(path.toFile()).getElementsByTagName("ProgressBar");
                for (int i = 0; i < bars.getLength(); i++) {
                    Element bar = (Element) bars.item(i);
                    if (!bar.hasAttribute("style")) continue; // Spinners are not value meters.
                    assertEquals(path.toString(), "@style/Widget.SubHub.ProgressMeter", bar.getAttribute("style"));
                    assertEquals(path.toString(), "@dimen/progress_meter_height", bar.getAttributeNS(ANDROID, "layout_height"));
                    assertFalse(path.toString(), bar.hasAttributeNS(ANDROID, "progressTint"));
                    assertFalse(path.toString(), bar.hasAttributeNS(ANDROID, "progressBackgroundTint"));
                    count++;
                }
            }
        }
        assertEquals("Achievements (2), combined limits, export, and update download", 5, count);
    }

    @Test public void programmaticMetersAndActivityChartUseTheSameMutedTokens() throws Exception {
        for (String path : new String[]{"appmode/AllowanceProgress.java", "appmode/AppLimitRow.java",
                "appmode/ExtraTimeViews.java", "stats/AchievementPreviewView.java", "stats/AchievementsPane.java",
                "capture/export/ExportWorkspaceActivity.java"}) {
            String code = readSource("java/com/subhub/app/" + path);
            assertTrue(path, code.contains("R.style.Widget_SubHub_ProgressMeter"));
            assertTrue(path, code.contains("R.dimen.progress_meter_height"));
            assertFalse(path, code.contains("progressBarStyleHorizontal"));
            assertFalse(path, code.contains("setProgressTintList"));
            assertFalse(path, code.contains("setProgressBackgroundTintList"));
        }
        String chart = readSource("java/com/subhub/app/stats/DailyStatsPanel.java");
        assertTrue(chart.contains("paint.setColor(getContext().getColor(R.color.progress_fill))"));
        assertTrue(chart.contains("getDimension(R.dimen.progress_meter_radius)"));
    }

    private static String readSource(String path) throws java.io.IOException {
        return new String(Files.readAllBytes(source(path).toPath()), StandardCharsets.UTF_8);
    }

    private static File source(String path) {
        File file = new File("src/main/" + path);
        if (!file.exists()) file = new File("app/src/main/" + path);
        assertTrue("Missing source " + path, file.exists());
        return file;
    }

    private static Document xml(String path) throws Exception { return parse(source(path)); }

    private static Document parse(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(file);
    }

    private static Map<String, String> values(String path, String tag) throws Exception {
        return entries(xml(path).getElementsByTagName(tag));
    }

    private static Map<String, String> entries(NodeList nodes) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element entry = (Element) nodes.item(i);
            result.put(entry.getAttribute("name"), entry.getTextContent().trim());
        }
        return result;
    }

    private static int color(Map<String, String> colors, String name) {
        String value = colors.get(name);
        return value.startsWith("@color/") ? color(colors, value.substring(7))
                : Integer.parseUnsignedInt(value.substring(1), 16);
    }

    private static double luminance(int color) {
        return .2126 * channel((color >> 16) & 255) + .7152 * channel((color >> 8) & 255)
                + .0722 * channel(color & 255);
    }

    private static double channel(int color) {
        double value = color / 255d;
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }
}
