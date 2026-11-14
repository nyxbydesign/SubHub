package com.subhub.app.fullaccess;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class FullAccessVideoTest {
    @Test public void projectionFitsPortraitLandscapeAndCodecAlignment() {
        assertEquals(new FullAccessVideo.Size(720, 1280), FullAccessVideo.fit(1080, 1920, 720, 2, 2));
        assertEquals(new FullAccessVideo.Size(1280, 720), FullAccessVideo.fit(1920, 1080, 720, 2, 2));
        assertEquals(new FullAccessVideo.Size(576, 1280), FullAccessVideo.fit(1080, 2400, 720, 16, 16));
        assertEquals(new FullAccessVideo.Size(418, 746), FullAccessVideo.fit(1080, 1920, 420, 2, 2));
        assertEquals(new FullAccessVideo.Size(320, 240), FullAccessVideo.fit(320, 240, 420, 2, 2));
    }

    @Test public void roundTripConfigAndKeyFrameWithoutBase64() throws Exception {
        FullAccessVideo.Size size = new FullAccessVideo.Size(720, 1280);
        byte[] sps = {1, 2}, pps = {3}, video = {4, 5};
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        FullAccessVideo.write(output, FullAccessVideo.Packet.config(size, sps, pps));
        FullAccessVideo.write(output, FullAccessVideo.Packet.sample(true, 40_000, video));

        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        FullAccessVideo.Packet header = FullAccessVideo.read(input);
        assertEquals(FullAccessVideo.CONFIG, header.kind());
        assertEquals(size, header.size());
        assertArrayEquals(sps, header.first());
        assertArrayEquals(pps, header.second());

        FullAccessVideo.Packet sample = FullAccessVideo.read(input);
        assertEquals(FullAccessVideo.KEY_FRAME, sample.kind());
        assertEquals(40_000, sample.timeUs());
        assertArrayEquals(video, sample.first());
        assertEquals(0, input.available());
    }

    @Test public void packetBoundsRejectUntrustedAllocationAndDimensions() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte(FullAccessVideo.FRAME);
        output.writeLong(0);
        output.writeInt(FullAccessVideo.MAX_FRAME_BYTES + 1);
        assertThrows(IOException.class, () -> FullAccessVideo.read(input(bytes)));

        bytes.reset();
        output.writeByte(FullAccessVideo.CONFIG);
        output.writeShort(721);
        output.writeShort(1280);
        assertThrows(IOException.class, () -> FullAccessVideo.read(input(bytes)));
    }

    @Test public void slowViewerRestartsOnlyAtFreshKeyFrameAndHideIsNotLost() throws Exception {
        FullAccessVideo.Queue queue = new FullAccessVideo.Queue();
        FullAccessVideo.Packet header = FullAccessVideo.Packet.config(new FullAccessVideo.Size(720, 1280),
                new byte[]{1}, new byte[]{2});
        queue.configure(header);
        queue.publish(frame(true, 0));
        for (int i = 1; i <= 8; i++) queue.publish(frame(false, i));
        queue.publish(frame(true, 9));
        assertEquals(header, queue.next());
        assertEquals(9, queue.next().timeUs());

        queue.hide();
        queue.configure(header);
        queue.publish(frame(false, 10));
        queue.publish(frame(true, 11));
        assertEquals(FullAccessVideo.HIDDEN, queue.next().kind());
        assertEquals(header, queue.next());
        assertEquals(11, queue.next().timeUs());

        queue.close();
        queue.publish(frame(true, 12));
        assertEquals(FullAccessVideo.END, queue.next().kind());
    }

    private static DataInputStream input(ByteArrayOutputStream bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    }

    private static FullAccessVideo.Packet frame(boolean key, long timeUs) {
        return FullAccessVideo.Packet.sample(key, timeUs, new byte[]{7});
    }
}
