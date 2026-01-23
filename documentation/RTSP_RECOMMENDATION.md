# RTSP: The Recommended Solution for Hardware-Accelerated Streaming

## Executive Summary

For devices without MPEG-TS support (like Galaxy S10+ with Lineage OS), **RTSP (Real-Time Streaming Protocol)** is the recommended solution for hardware-accelerated H.264 streaming. RTSP is the industry standard for IP cameras and bypasses the container format issues that make HLS impossible on these devices.

## The Problem with HLS on Non-MPEG-TS Devices

### Why HLS Doesn't Work:
1. **HLS requires specific containers:**
   - MPEG-TS (.ts files) - Requires API 26+ (Android 8.0+)
   - Fragmented MP4 (.m4s files) - Requires fMP4 format

2. **Android MediaMuxer limitations:**
   - `MUXER_OUTPUT_MPEG_4` creates **standard MP4** (moov at end)
   - Does NOT create fragmented MP4 (fMP4)
   - Standard MP4 is fundamentally incompatible with HLS streaming

3. **Why standard MP4 doesn't work for HLS:**
   - moov atom written at END (after muxer.stop())
   - Not independently playable/seekable
   - HLS players expect either MPEG-TS or fMP4
   - No amount of version tweaking or timing fixes will make it work

### What We Tried:
- ✅ Fixed buffer corruption (green artifacts) - this worked
- ❌ MP4 timing fixes - segments finalized but wrong format
- ❌ HLS version 3 instead of 7 - still wrong container
- **Conclusion:** The approach was fundamentally flawed

---

## RTSP: The Right Solution

### What is RTSP?

RTSP (Real-Time Streaming Protocol) is the industry-standard protocol for IP cameras and surveillance systems. It streams raw H.264 video over RTP (Real-time Transport Protocol) **without needing a container format**.

### Why RTSP Solves Our Problems:

**1. No Container Issues**
```
HLS Approach (BROKEN):
Camera → MediaCodec (H.264) → MediaMuxer (MP4) → File → HTTP → Player
                                    ↑
                              Problem: Need fMP4, get standard MP4

RTSP Approach (WORKS):
Camera → MediaCodec (H.264) → Extract NAL units → RTP → Network → Player
                                    ↑
                              No container needed!
```

**2. Hardware Accelerated**
- Uses same MediaCodec H.264 hardware encoder
- Compressed streaming (2-4 Mbps vs 8 Mbps MJPEG)
- Same quality as HLS would have provided

**3. Universal Compatibility**
- All professional NVR software (ZoneMinder, Shinobi, Blue Iris, MotionEye)
- VLC, FFmpeg, and all major media players
- Mobile apps and web players (via WebRTC gateway)

**4. Works on All Android Versions**
- No API 26+ requirement
- Just needs MediaCodec (available since API 16)
- Works on Galaxy S10+ with Lineage OS

**5. Better Latency Than HLS**
- RTSP: ~500ms-1s latency
- HLS: 6-12s latency (by design)
- MJPEG: ~150-280ms latency

---

## Technical Implementation

### High-Level Architecture:

```kotlin
class RTSPServer(private val port: Int = 8554) {
    private val mediaCodec: MediaCodec  // H.264 hardware encoder
    private val sessions: MutableList<RTSPSession> = mutableListOf()
    
    // RTSP server listens on port 8554 (standard RTSP port)
    fun start() {
        // Accept RTSP connections
        // Handle DESCRIBE, SETUP, PLAY, TEARDOWN commands
    }
    
    fun streamFrame(imageProxy: ImageProxy) {
        // 1. Encode frame to H.264 using hardware MediaCodec
        val h264Buffer = encodeFrame(imageProxy)
        
        // 2. Extract NAL units from encoded buffer
        val nalUnits = extractNALUnits(h264Buffer)
        
        // 3. Package NAL units as RTP packets (RFC 6184)
        val rtpPackets = packageAsRTP(nalUnits)
        
        // 4. Send RTP packets to all connected clients
        sessions.forEach { session ->
            session.sendRTP(rtpPackets)
        }
    }
    
    private fun extractNALUnits(buffer: ByteBuffer): List<NALUnit> {
        // H.264 NAL units are separated by start codes (0x00 0x00 0x00 0x01)
        // Parse buffer and extract individual NAL units
    }
    
    private fun packageAsRTP(nalUnits: List<NALUnit>): List<RTPPacket> {
        // Package NAL units according to RFC 6184
        // Handle fragmentation for large NAL units (FU-A)
    }
}
```

### Key Components:

**1. RTSP Server**
- Handles RTSP protocol (DESCRIBE, SETUP, PLAY, TEARDOWN)
- Manages client sessions
- Sends SDP (Session Description Protocol) info

**2. RTP Packetizer**
- Extracts H.264 NAL units from MediaCodec output
- Packages NAL units as RTP packets
- Handles fragmentation for large NAL units (FU-A mode)
- Adds RTP headers (sequence numbers, timestamps)

**3. MediaCodec Integration**
- Same H.264 encoder already used for HLS attempt
- Hardware acceleration via OMX.Exynos.AVC.Encoder
- Just extract raw NAL units instead of using MediaMuxer

### Libraries Available:

**Option 1: libstreaming**
- GitHub: https://github.com/fyhertz/libstreaming
- Mature, production-tested Android RTSP library
- Hardware encoding support
- Used in many commercial IP camera apps
- MIT License

**Option 2: Custom Implementation**
- Moderate complexity (~1000-2000 lines)
- Full control over implementation
- RFCs to implement:
  - RFC 2326: RTSP protocol
  - RFC 3550: RTP/RTCP
  - RFC 6184: H.264 over RTP

---

## Comparison: RTSP vs HLS vs MJPEG

| Feature | RTSP | HLS (MPEG-TS) | HLS (MP4) | MJPEG |
|---------|------|---------------|-----------|-------|
| **Encoding** | H.264 | H.264 | H.264 | JPEG |
| **Compression** | ✅ High | ✅ High | ✅ High | ❌ Low |
| **Bandwidth** | 2-4 Mbps | 2-4 Mbps | N/A | 8 Mbps |
| **Latency** | ~500ms | 6-12s | N/A | ~150ms |
| **Container** | None | MPEG-TS | MP4 | None |
| **API Requirement** | Any | 26+ | Any | Any |
| **Galaxy S10+ Support** | ✅ Yes | ❌ No | ❌ No | ✅ Yes |
| **NVR Compatibility** | ✅ Excellent | ✅ Excellent | ❌ Poor | ✅ Excellent |
| **VLC Support** | ✅ Yes | ✅ Yes | ⚠️ Limited | ✅ Yes |
| **Implementation** | Medium | Low | Low | Low |
| **CPU Usage** | Low | Low | Low | Medium |

---

## Usage Examples (Once Implemented)

### VLC Player:
```bash
vlc rtsp://192.168.2.122:8554/stream
```

### FFmpeg (Record):
```bash
ffmpeg -i rtsp://192.168.2.122:8554/stream -c copy output.mp4
```

### ZoneMinder Configuration:
```
Source Type: Remote
Source Path: rtsp://192.168.2.122:8554/stream
Method: RTP/RTSP
```

### Blue Iris:
```
Make: Generic/ONVIF
Model: RTSP/H.264
Path: /stream
Port: 8554
```

### Web Browser (via WebRTC):
```html
<!-- Would need WebRTC gateway to convert RTSP to WebRTC -->
<video id="stream" autoplay></video>
<script>
  // Use WebRTC client library
  const player = new RTSPWebRTCPlayer('rtsp://192.168.2.122:8554/stream');
  player.attachTo(document.getElementById('stream'));
</script>
```

---

## Implementation Roadmap

### Phase 1: Basic RTSP Server (2-3 days)
- [ ] Integrate libstreaming library
- [ ] Create RTSP server instance
- [ ] Connect to existing MediaCodec H.264 encoder
- [ ] Extract NAL units from encoder output
- [ ] Basic RTP streaming to single client

### Phase 2: Multi-Client Support (1 day)
- [ ] Handle multiple simultaneous RTSP sessions
- [ ] Session management (SETUP, PLAY, TEARDOWN)
- [ ] Proper RTCP handling for feedback

### Phase 3: Integration (1 day)
- [ ] Add RTSP server to CameraService
- [ ] Foreground service for RTSP
- [ ] Settings persistence
- [ ] Start/stop endpoints

### Phase 4: Polish (1 day)
- [ ] Error handling and recovery
- [ ] Network change handling
- [ ] Documentation
- [ ] Testing with various clients

**Total Estimate:** 5-6 days of development

---

## Current Solution (This PR)

Until RTSP is implemented, we:

1. **Disable HLS on non-MPEG-TS devices** with clear error:
```
HLS streaming requires Android 8.0+ (API 26+) for MPEG-TS format support.
Your device does not support this (API 25 or MPEG-TS unavailable).

Please use MJPEG streaming instead: http://DEVICE_IP:8080/stream

For hardware-accelerated compressed streaming, RTSP support will be 
added in a future update. RTSP is the industry standard for IP cameras
and will provide H.264 compression on all devices.
```

2. **Keep MJPEG working** as primary streaming method
   - Low latency (~150ms)
   - Universal compatibility
   - Works on all devices

3. **Document RTSP as future enhancement**
   - Proper solution for compressed streaming
   - Works on all Android versions
   - Industry standard protocol

---

## Benefits of RTSP Approach

### For Users:
- ✅ Hardware-accelerated compressed streaming on all devices
- ✅ Lower bandwidth than MJPEG
- ✅ Better latency than HLS
- ✅ Works with all NVR software they already use
- ✅ Standard protocol - no proprietary solutions

### For Developers:
- ✅ Cleaner architecture (no container workarounds)
- ✅ One implementation works everywhere
- ✅ Mature libraries available (libstreaming)
- ✅ Well-documented RFCs
- ✅ Easier to maintain than HLS MP4 hacks

### For IP Camera Use Case:
- ✅ RTSP is THE standard protocol for IP cameras
- ✅ All professional surveillance software expects RTSP
- ✅ Better fit for continuous streaming than HLS
- ✅ Lower latency more suitable for security monitoring

---

## Conclusion

**The MP4 fallback approach for HLS was fundamentally flawed** - standard MP4 files cannot work with HLS regardless of version numbers or timing fixes.

**RTSP is the right solution:**
- Bypasses container format issues entirely
- Uses same hardware H.264 encoder
- Industry standard for IP cameras
- Works on all Android versions
- Better suited for the use case than HLS

**Current state:**
- HLS disabled on non-MPEG-TS devices (with clear error message)
- MJPEG continues to work (immediate solution)
- RTSP documented as proper future enhancement

**Next steps:**
- Accept this PR to clean up the broken MP4 approach
- Create new issue/PR for RTSP implementation
- Use libstreaming library for faster development
- Provide proper hardware-accelerated streaming for all devices

---

## References

- **RTSP RFC 2326:** https://tools.ietf.org/html/rfc2326
- **RTP RFC 3550:** https://tools.ietf.org/html/rfc3550
- **H.264 over RTP RFC 6184:** https://tools.ietf.org/html/rfc6184
- **libstreaming:** https://github.com/fyhertz/libstreaming
- **Android MediaCodec:** https://developer.android.com/reference/android/media/MediaCodec

---

**Status:** Ready for RTSP implementation in future PR
