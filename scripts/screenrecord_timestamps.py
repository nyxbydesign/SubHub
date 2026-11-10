"""Read validated Winscope v2 timing metadata embedded by Android screenrecord.

Format reference (implementation uses uint32 frame count, despite its prose saying 8B):
https://android.googlesource.com/platform/frameworks/av/+/b675cea85b508d7f60f1344e5a24c3db7a5b8d0f/cmds/screenrecord/screenrecord.cpp
This is a bounded metadata extractor, not a general MP4 parser. Consumers must also compare
the timestamp count against the actual decoded video frame count before using frame indexes.
"""
import struct

MAGIC = b"#VV1NSC0PET1ME2#"


def parse(data):
    start = data.find(MAGIC)
    if start < 0 or data.find(MAGIC, start + 1) >= 0:
        raise ValueError("Missing or ambiguous Winscope v2 metadata")
    offset = start + len(MAGIC)
    if len(data) < offset + 16:
        raise ValueError("Truncated timing header")
    version, realtime_offset, count = struct.unpack_from("<IqI", data, offset)
    if version != 2 or not 0 < count <= 1_000_000:
        raise ValueError("Unsupported timing version or frame count")
    offset += 16
    if len(data) < offset + count * 8:
        raise ValueError("Truncated frame timestamps")
    elapsed = list(struct.unpack_from(f"<{count}Q", data, offset))
    if elapsed[0] <= 0 or any(right <= left for left, right in zip(elapsed, elapsed[1:])):
        raise ValueError("Nonpositive or non-increasing frame timestamps")
    return {"version": version, "frameCount": count, "realtimeOffsetNs": realtime_offset,
            "elapsedNs": elapsed, "epochNs": [value + realtime_offset for value in elapsed]}
