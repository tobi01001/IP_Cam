# HLS Version 3 Fix - Final Compatibility Update

## Issue Reported (Comment #3699697487)

User reported that after the MP4 timing fix, ffplay still couldn't play the stream:
```
[hls @ 0x7fa828000bc0] Skip ('#EXT-X-VERSION:7')
[hls @ 0x7fa828000bc0] Opening 'http://192.168.2.122:8080/hls/segment0.m4s' for reading
```

The message "Skip ('#EXT-X-VERSION:7')" indicated ffplay was rejecting the HLS version 7 declaration.

## Root Cause Analysis

### Issue 1: HLS Version 7 Incompatibility

**What Version 7 Requires:**
- HLS version 7 is for ISO Base Media File Format (ISO BMFF) segments
- Specifically designed for **fragmented MP4 (fMP4)** format
- fMP4 structure:
  - Initialization segment with `moov` and `mvex` atoms
  - Media segments with `moof` (movie fragment) and `mdat` atoms
  - Progressive writing, no finalization needed

**What We Actually Have:**
- Android MediaMuxer `MUXER_OUTPUT_MPEG_4` creates **standard MP4**
- Standard MP4 structure:
  - `ftyp` (file type box)
  - `mdat` (media data - written during recording)
  - `moov` (movie metadata - written on stop(), at the END)
- Not fragmented MP4 format

**The Mismatch:**
- Players expecting HLS version 7 look for fMP4 structure
- They expect `moof` atoms, initialization segments, etc.
- Our standard MP4 files have `moov` at the end, no `moof` atoms
- Players reject or fail to parse the segments

### Issue 2: Missing Content-Type Header

**The Bug:**
```kotlin
// HttpServer.kt - serveHLSSegment()
val contentType = when {
    segmentName.endsWith(".ts") -> ContentType.parse("video/mp2t")
    segmentName.endsWith(".m4s") -> ContentType.parse("video/mp4")
    else -> ContentType.Application.OctetStream
}
// contentType calculated but NEVER USED!
call.respondFile(segmentFile)
```

The `contentType` variable was computed but never applied to the HTTP response, so players received segments without proper MIME type information.

## Solutions Implemented

### Fix 1: Use HLS Version 3

Changed from version 7 to version 3 for all segments:

```kotlin
// Before (BROKEN):
val version = if (muxerOutputFormat == 8) 3 else 7  // Version 7 for MP4

// After (FIXED):
val version = 3  // Version 3 for all formats
```

**Why Version 3?**
- Version 3 is from the original HLS specification
- More permissive, doesn't require specific segment formats
- Many players accept non-standard segments with version 3
- VLC, FFplay, and most modern players support it
- Avoids version 7's strict fMP4 requirements

### Fix 2: Apply Content-Type Header

Added explicit Content-Type header to HTTP responses:

```kotlin
// Before (BROKEN):
val contentType = when { ... }  // Calculated but not used
call.respondFile(segmentFile)

// After (FIXED):
val contentType = when { ... }
call.response.header("Content-Type", contentType.toString())  // Applied!
call.response.header("Cache-Control", "public, max-age=60")
call.response.header("Access-Control-Allow-Origin", "*")
call.respondFile(segmentFile)
```

**Impact:**
- Segments now served with `Content-Type: video/mp4` header
- Players can properly identify the format
- Better compatibility with strict players

## Why No Fragmented MP4?

Android's MediaMuxer API limitations:

### Available Output Formats:
1. `MUXER_OUTPUT_MPEG_4 (0)` - Standard MP4 (what we use)
2. `MUXER_OUTPUT_WEBM (1)` - WebM (VP8/VP9, not H.264)
3. `MUXER_OUTPUT_3GPP (2)` - 3GPP (similar to MP4)
4. `MUXER_OUTPUT_HEIF (3)` - HEIF images (API 28+)
5. `MUXER_OUTPUT_OGG (4)` - OGG audio (API 29+)
6. `MUXER_OUTPUT_MPEG_2_TS (8)` - MPEG-TS (API 26+, not available on user's device)

### No Fragmented MP4 Option
- No `MUXER_OUTPUT_FRAGMENTED_MP4` or similar
- No way to create fMP4 with initialization segments
- Would require custom muxer implementation (complex, out of scope)

### Why Not WebM?
- Uses VP8/VP9 codecs, not H.264
- Would require different encoder setup
- Not standard HLS format
- Less reliable hardware acceleration

## Expected Behavior After Fix

### HLS Playlist
```m3u8
#EXTM3U
#EXT-X-VERSION:3           ← Changed from 7
#EXT-X-TARGETDURATION:2
#EXT-X-MEDIA-SEQUENCE:0
#EXTINF:2.0,
segment0.m4s
#EXTINF:2.0,
segment1.m4s
```

### HTTP Headers
```
HTTP/1.1 200 OK
Content-Type: video/mp4    ← Now included
Cache-Control: public, max-age=60
Access-Control-Allow-Origin: *
```

### Player Behavior
1. Player fetches playlist
2. Sees version 3 (accepts it, no skip)
3. Fetches segment0.m4s
4. Receives proper Content-Type header
5. Attempts to parse as MP4
6. Should successfully decode and play

## Testing Results Expected

### With ffplay:
```bash
$ ffplay http://192.168.2.122:8080/hls/stream.m3u8
ffplay version 4.2.7...
[hls @ ...] (no "Skip" message)
[hls @ ...] Opening 'http://192.168.2.122:8080/hls/segment0.m4s' for reading
[hls @ ...] Opening 'http://192.168.2.122:8080/hls/segment1.m4s' for reading
[Video starts playing after ~2 second delay]
```

### Verification Commands:
```bash
# Check playlist version
curl http://192.168.2.122:8080/hls/stream.m3u8
# Should show: #EXT-X-VERSION:3

# Check segment headers
curl -I http://192.168.2.122:8080/hls/segment0.m4s
# Should show: Content-Type: video/mp4

# Check segment file size (should be > 0)
curl http://192.168.2.122:8080/hls/segment0.m4s > test.m4s
ls -lh test.m4s
# Should show file size > 0 (e.g., 50KB+)
```

## Known Limitations

### Standard MP4 in HLS is Non-Standard
- HLS specification recommends MPEG-TS or fMP4
- Standard MP4 works with many players but not guaranteed
- Some strict HLS validators may flag it
- May not work with all professional NVR/surveillance software

### Player Compatibility
- ✅ **Should work:** VLC, FFplay, most modern browsers
- ⚠️ **May work:** Some NVR software, older players
- ❌ **Won't work:** Strict HLS-compliant players expecting only TS or fMP4

### Recommended Solution
For best HLS experience:
- Use device with **Android 8.0+ (API 26+)**
- Enables MPEG-TS format (progressive, standard HLS)
- Universal compatibility
- No timing delays

## Commit Summary

**Commit:** ba39363

**Changes:**
1. `HLSEncoderManager.kt`: Changed version from 7 to 3 for all formats
2. `HttpServer.kt`: Apply Content-Type header to segment responses
3. Enhanced logging for MP4 format warnings

**Impact:**
- Fixes version 7 rejection by players
- Provides proper MIME type information
- Maximum compatibility with version 3
- Non-breaking change (all existing functionality preserved)

## Debugging Guide

If playback still doesn't work:

### Check Segments Are Created
```bash
adb shell "ls -lh /data/data/com.ipcam/cache/hls_segments/"
# Should show multiple .m4s files with size > 0
```

### Check Logs
```bash
adb logcat | grep "HLSEncoderManager"
# Look for:
# - "MP4 segment finalized and now available"
# - "Generating playlist with N available segments"
# - "Using standard MP4 format for HLS segments"
```

### Test Segment Download
```bash
curl http://192.168.2.122:8080/hls/segment0.m4s > test.m4s
file test.m4s
# Should say: "ISO Media, MP4 Base Media v1"
ffprobe test.m4s
# Should show video stream info
```

### If Still Not Working
Possible issues:
1. Encoder not writing data (check logs for errors)
2. Segments not being finalized (check timing - wait 5+ seconds)
3. Player strictly requires MPEG-TS (try VLC, it's most tolerant)
4. Network/firewall issues (try from same device as server)

## Conclusion

This fix addresses the HLS version 7 incompatibility by using version 3, which is more permissive and widely supported. Combined with proper Content-Type headers, standard MP4 segments should now be playable in most HLS players, despite not being fragmented MP4.

The approach is pragmatic: while not perfectly compliant with HLS specification, it provides a functional solution for devices without MPEG-TS support, maintaining hardware-accelerated H.264 encoding.

**Status:** ✅ Ready for testing on Galaxy S10+ with Lineage OS
