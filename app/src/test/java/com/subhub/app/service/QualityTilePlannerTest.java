package com.subhub.app.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;

import org.junit.Test;

import java.util.List;

public final class QualityTilePlannerTest {
    @Test public void portraitAlternatesOverlappingTopAndBottomTiles() {
        QualityTilePlanner.Tile top = QualityTilePlanner.select(1_344, 2_992, 2L);
        QualityTilePlanner.Tile bottom = QualityTilePlanner.select(1_344, 2_992, 3L);

        assertEquals(0, top.top());
        assertEquals(2, top.count());
        assertEquals(2_992, bottom.bottom());
        assertTrue(top.bottom() > bottom.top());
        assertEquals(top.height(), bottom.height());
        assertEquals(Math.round(2_992 * .82f), top.height());
        assertFalse(top.fullFrame());
    }

    @Test public void landscapeAlternatesOverlappingColumns() {
        QualityTilePlanner.Tile left = QualityTilePlanner.select(2_400, 1_080, 4L);
        QualityTilePlanner.Tile right = QualityTilePlanner.select(2_400, 1_080, 5L);

        assertEquals(0, left.left());
        assertEquals(2_400, right.right());
        assertTrue(left.right() > right.left());
    }

    @Test public void nearSquareSourceKeepsFullFrame() {
        QualityTilePlanner.Tile tile = QualityTilePlanner.select(1_000, 1_100, 7L);
        assertTrue(tile.fullFrame());
        assertEquals(1_000, tile.width());
        assertEquals(1_100, tile.height());
    }

    @Test public void continuousQualityCyclesFullTopAndBottom() {
        QualityTilePlanner.Tile full = QualityTilePlanner.selectContinuous(1_344, 2_992, 0L);
        QualityTilePlanner.Tile top = QualityTilePlanner.selectContinuous(1_344, 2_992, 1L);
        QualityTilePlanner.Tile bottom = QualityTilePlanner.selectContinuous(1_344, 2_992, 2L);
        QualityTilePlanner.Tile nextFull = QualityTilePlanner.selectContinuous(
                1_344, 2_992, 3L);

        assertTrue(full.fullFrame());
        assertEquals(0, top.top());
        assertEquals(2_992, bottom.bottom());
        assertTrue(nextFull.fullFrame());
    }

    @Test public void tileDetectionsMapBackIntoFullFrameCoordinates() {
        QualityTilePlanner.Tile bottom = QualityTilePlanner.select(1_344, 2_992, 3L);
        Detection local = new Detection(
                "FACE_FEMALE", "face", .9f, new BBox(100, 200, 80, 90), true, true,
                Detection.ObservationSource.QUALITY_VISUAL,
                Detection.GeometryQuality.MODEL, null);

        Detection mapped = QualityTilePlanner.toFullFrame(
                List.of(local), bottom, 1_344, 2_992).get(0);

        assertEquals(new BBox(100, bottom.top() + 200, 80, 90), mapped.getBox());
    }
}
