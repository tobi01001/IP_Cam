# RTSP Streaming Implementation

## Overview

IP_Cam now supports **RTSP (Real Time Streaming Protocol)** with hardware-accelerated H.264 encoding as an alternative to MJPEG streaming. RTSP provides significantly lower bandwidth usage (2-4 Mbps vs 8 Mbps) while maintaining acceptable latency (~500ms-1s) for most surveillance applications.

## Why RTSP?

RTSP was chosen as the modern streaming protocol for IP_Cam based on comprehensive analysis documented in `RTSP_RECOMMENDATION.md`:

### Advantages

✅ **50-75% Bandwidth Reduction** - 2-4 Mbps (H.264) vs 8 Mbps (MJPEG)  
✅ **Hardware Acceleration** - Uses Android MediaCodec for efficient encoding  
✅ **Industry Standard** - Native support in VLC, FFmpeg, and all major surveillance systems  
✅ **Acceptable Latency** - ~500ms-1s (vs 150-280ms MJPEG, 6-12s HLS)  
✅ **No Container Issues** - Streams raw H.264 NAL units over RTP (no MP4/TS format dependencies)  
✅ **Dual Transport** - Supports both UDP (lower latency) and TCP (firewall-friendly)  
✅ **Proven Protocol** - Standard protocol used by IP cameras worldwide

### RTSP vs HLS (Why HLS Was Removed)

HLS streaming was fundamentally broken on many Android devices due to MediaMuxer limitations:

| Issue | HLS | RTSP |
|-------|-----|------|
| **Container Format** | Requires MPEG-TS or fragmented MP4 | No container (raw H.264 NAL units) |
| **MediaMuxer Support** | MPEG-TS only on API 26+, standard MP4 unusable | Not needed |
| **Compatibility** | Device-dependent, many failures | Universal |
| **Latency** | 6-12 seconds | 500ms-1s |
| **Implementation** | Complex (segments, playlists) | Standard RTSP protocol |

**RTSP Solution**: Bypasses MediaMuxer entirely by extracting NAL units directly from MediaCodec and sending them over RTP. This works on all Android devices with hardware H.264 encoding support (all modern Android 11+ devices).

## Architecture

### System Components

```
┌─────────────┐
│   Camera    │
│   Frames    │
└──────┬──────┘
       │ YUV_420_888 frames
       ↓
┌──────────────────┐
│   RTSPServer     │
│                  │
│ MediaCodec       │ ← Hardware H.264 encoder
│ (H.264 encoder)  │
└────────┬─────────┘
         │ H.264 NAL units
         ↓
┌─────────────────────┐
│  RTP Packetizer     │ ← RFC 6184 compliant
└──────────┬──────────┘
           │
    ┌──────┴──────┐
    ↓             ↓
┌────────┐   ┌────────┐
│  UDP   │   │  TCP   │
│  RTP   │   │  RTP   │
└────────┘   └────────┘
```

### Key Components

#### 1. RTSPServer.kt

Custom RTSP server implementation with:

- **RTSP Protocol Handler**: Processes DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN requests
- **SDP Generator**: Creates Session Description Protocol responses with SPS/PPS parameters
- **RTP Packetizer**: Packages H.264 NAL units into RTP packets (RFC 6184)
- **MediaCodec Integration**: Hardware-accelerated H.264 encoding
- **Session Management**: Multi-client support with independent sessions
- **Dual Transport**: UDP (DatagramSocket) and TCP (interleaved binary data)

#### 2. MediaCodec H.264 Encoder

```kotlin
val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
format.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)  // 2 Mbps
format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)  // Keyframe every 2 seconds
```

Configuration optimized for surveillance:
- **Bitrate**: 2 Mbps (adjustable)
- **Frame Rate**: 30 fps
- **I-frame Interval**: 2 seconds
- **Profile**: Baseline (maximum compatibility)

#### 3. SPS/PPS Extraction

The encoder configuration (SPS - Sequence Parameter Set, PPS - Picture Parameter Set) is extracted on the first frame:

```kotlin
when (outputBufferIndex) {
    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
        val newFormat = encoder?.outputFormat
        val spsBuffer = newFormat?.getByteBuffer("csd-0")  // SPS
        val ppsBuffer = newFormat?.getByteBuffer("csd-1")  // PPS
        // Store for SDP generation
    }
}
```

These parameters are required for clients to decode the H.264 stream and are sent in the RTSP DESCRIBE response.

## RTSP Protocol Flow

### 1. DESCRIBE - Retrieve Stream Information

**Client Request:**
```
DESCRIBE rtsp://192.168.2.158:8554/stream RTSP/1.0
CSeq: 1
Accept: application/sdp
```

**Server Response:**
```
RTSP/1.0 200 OK
CSeq: 1
Content-Type: application/sdp
Content-Length: XXX

v=0
o=- 0 0 IN IP4 192.168.2.158
s=IP_Cam RTSP Stream
c=IN IP4 192.168.2.158
t=0 0
m=video 0 RTP/AVP 96
a=rtpmap:96 H264/90000
a=fmtp:96 packetization-mode=1;sprop-parameter-sets=XXXXXX,YYYYYY
```

The `sprop-parameter-sets` contains Base64-encoded SPS and PPS from MediaCodec.

### 2. SETUP - Configure Transport

**Client Request (UDP):**
```
SETUP rtsp://192.168.2.158:8554/stream RTSP/1.0
CSeq: 2
Transport: RTP/AVP;unicast;client_port=50000-50001
```

**Client Request (TCP):**
```
SETUP rtsp://192.168.2.158:8554/stream RTSP/1.0
CSeq: 2
Transport: RTP/AVP/TCP;unicast;interleaved=0-1
```

**Server Response:**
```
RTSP/1.0 200 OK
CSeq: 2
Transport: RTP/AVP;unicast;client_port=50000-50001;server_port=50002-50003
Session: session1
```

The server allocates UDP ports or configures TCP interleaved channels.

### 3. PLAY - Start Streaming

**Client Request:**
```
PLAY rtsp://192.168.2.158:8554/stream RTSP/1.0
CSeq: 3
Session: session1
```

**Server Response:**
```
RTSP/1.0 200 OK
CSeq: 3
Session: session1
RTP-Info: url=rtsp://192.168.2.158:8554/stream;seq=0;rtptime=0
```

Server starts sending RTP packets containing H.264 NAL units.

### 4. TEARDOWN - Stop Streaming

**Client Request:**
```
TEARDOWN rtsp://192.168.2.158:8554/stream RTSP/1.0
CSeq: 4
Session: session1
```

**Server Response:**
```
RTSP/1.0 200 OK
CSeq: 4
Session: session1
```

Server stops streaming and releases resources.

## RTP Packetization (RFC 6184)

H.264 NAL units are packetized into RTP packets according to RFC 6184:

### RTP Header (12 bytes)

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|V=2|P|X|  CC   |M|     PT      |       Sequence Number         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                           Timestamp                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|           SSRC (Synchronization Source)                       |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### Single NAL Unit Mode

For NAL units < 1400 bytes:
```
RTP Header + NAL Unit
```

### Fragmentation Unit (FU-A) Mode

For NAL units > 1400 bytes (split into multiple RTP packets):

**First Packet:**
```
RTP Header + FU Indicator + FU Header + NAL Fragment
```

**Middle Packets:**
```
RTP Header + FU Indicator + FU Header + NAL Fragment
```

**Last Packet:**
```
RTP Header + FU Indicator + FU Header + NAL Fragment
```

The marker bit (M) in the RTP header is set to 1 on the last packet of each frame.

## Transport Modes

### UDP Transport (Default)

**Advantages:**
- Lower latency (~500ms)
- Less protocol overhead
- Standard for RTSP streaming

**Considerations:**
- May be blocked by firewalls/NAT
- Packet loss possible (but rare on local networks)
- Some devices/networks have WiFi power saving that can delay UDP

**Usage:**
```bash
ffplay rtsp://192.168.2.158:8554/stream
vlc rtsp://192.168.2.158:8554/stream
```

Clients will automatically attempt UDP first.

### TCP Transport (Fallback)

**Advantages:**
- Works through firewalls/NAT
- Reliable delivery (no packet loss)
- Guaranteed to work if RTSP handshake succeeds

**Considerations:**
- Slightly higher latency (~100-200ms more than UDP)
- More protocol overhead
- Head-of-line blocking possible on congested networks

**Usage:**
```bash
ffplay -rtsp_transport tcp rtsp://192.168.2.158:8554/stream
vlc --rtsp-tcp rtsp://192.168.2.158:8554/stream
```

**Automatic Fallback:**
Most clients (ffplay, VLC) will automatically retry with TCP if UDP times out after 5-10 seconds.

### TCP Interleaved Binary Data (RFC 2326)

When using TCP transport, RTP packets are sent over the RTSP socket with special framing:

```
$<channel><length><rtp_packet>
```

- `$`: Magic byte (0x24)
- `<channel>`: 1 byte (0 for RTP, 1 for RTCP)
- `<length>`: 2 bytes (big-endian) - RTP packet size
- `<rtp_packet>`: The actual RTP packet data

This allows multiplexing RTP data over the same TCP connection as RTSP control messages.

## API Integration

### HTTP Endpoints

**Enable RTSP Streaming:**
```bash
curl http://192.168.2.158:8080/enableRTSP
```

Response:
```json
{
  "status": "ok",
  "message": "RTSP streaming enabled",
  "url": "rtsp://192.168.2.158:8554/stream",
  "port": 8554,
  "encoder": "OMX.Exynos.AVC.Encoder",
  "hardware": true
}
```

**Disable RTSP Streaming:**
```bash
curl http://192.168.2.158:8080/disableRTSP
```

**Get RTSP Status:**
```bash
curl http://192.168.2.158:8080/rtspStatus
```

Response:
```json
{
  "enabled": true,
  "url": "rtsp://192.168.2.158:8554/stream",
  "port": 8554,
  "activeSessions": 2,
  "playingSessions": 1,
  "framesEncoded": 1523,
  "encoder": "OMX.Exynos.AVC.Encoder",
  "hardware": true
}
```

### Web UI Controls

The web interface includes RTSP controls:

- **Enable RTSP** button - Starts the RTSP server
- **Disable RTSP** button - Stops the RTSP server
- **Check Status** button - Displays current RTSP metrics
- **RTSP URL** display - Shows the stream URL for easy copying

## Usage Examples

### VLC Media Player

```bash
vlc rtsp://192.168.2.158:8554/stream
```

Or open VLC → Media → Open Network Stream → Enter URL

### FFmpeg (Recording)

```bash
ffmpeg -i rtsp://192.168.2.158:8554/stream -c copy output.mp4
```

### FFplay (Testing)

```bash
ffplay rtsp://192.168.2.158:8554/stream
```

### Surveillance Systems

#### ZoneMinder

1. Add Monitor → Source Type: **Ffmpeg**
2. Source Path: `rtsp://192.168.2.158:8554/stream`
3. Target Colorspace: **24 bit color**

#### Shinobi

1. Add Monitor → Input Type: **H264**
2. Input URL: `rtsp://192.168.2.158:8554/stream`
3. Mode: **Watch Only** or **Record**

#### Blue Iris

1. Add Camera → Make: **Generic/ONVIF**
2. Network IP: `192.168.2.158`
3. Port: `8554`
4. Path: `/stream`

#### MotionEye / Motion

1. Add Camera → Camera Type: **Network Camera**
2. URL: `rtsp://192.168.2.158:8554/stream`

### Home Assistant

```yaml
camera:
  - platform: ffmpeg
    name: IP_Cam
    input: rtsp://192.168.2.158:8554/stream
```

## Performance Characteristics

### Bandwidth Usage

| Resolution | Frame Rate | Bitrate | Bandwidth |
|-----------|-----------|---------|-----------|
| 640x480 | 30 fps | 1-2 Mbps | ~1.5 Mbps |
| 1280x720 | 30 fps | 2-3 Mbps | ~2.5 Mbps |
| 1920x1080 | 30 fps | 3-4 Mbps | ~3.5 Mbps |

Compared to MJPEG:
- **640x480**: ~6 Mbps → ~1.5 Mbps (75% reduction)
- **1920x1080**: ~8 Mbps → ~3.5 Mbps (56% reduction)

### Latency

- **UDP Transport**: 500-800ms typical
- **TCP Transport**: 700-1000ms typical
- **Network Buffering**: +100-300ms depending on client

Total end-to-end latency: **~600ms-1.3s** (significantly better than HLS's 6-12s)

### CPU Usage

- **Hardware Encoding**: 5-10% CPU usage (MediaCodec)
- **Software Fallback**: 20-40% CPU usage (if no hardware encoder)
- Most modern Android devices have hardware H.264 encoders

## Troubleshooting

### "UDP timeout, retrying with TCP"

**Cause**: Client not receiving UDP RTP packets

**Solutions:**
1. Check Android firewall settings (some devices block outbound UDP)
2. Check network firewall/router settings
3. Disable WiFi power saving on Android device
4. Use TCP transport explicitly: `ffplay -rtsp_transport tcp rtsp://...`
5. Check LogCat for UDP packet send logs: `adb logcat -s RTSPServer:*`

### "method SETUP failed: 461 Unsupported Transport"

**Cause**: Client requested a transport mode the server doesn't support (fixed in latest version)

**Solution**: Update to latest version with dual transport support (UDP + TCP)

### "Could not find codec parameters for stream"

**Cause**: SPS/PPS not extracted from encoder or DESCRIBE timing issue

**Solution:**
1. Wait 2-3 seconds after enabling RTSP before connecting
2. Check LogCat for "Extracted SPS" and "Extracted PPS" messages
3. Verify encoder is producing output: check "First frame queued with size" log

### Green/Corrupted Video

**Cause**: Incorrect YUV to NV12 conversion or encoder configuration

**Solution:**
1. Verify camera is producing YUV_420_888 format
2. Check encoder color format setting (COLOR_FormatYUV420Flexible)
3. Verify fillInputBuffer() conversion is correct

### High Latency (>2 seconds)

**Potential Causes:**
1. Network congestion or poor WiFi signal
2. Client buffer settings too large
3. Keyframe interval too long

**Solutions:**
1. Reduce I-frame interval (currently 2 seconds)
2. Adjust client buffer settings if possible
3. Use UDP transport instead of TCP
4. Ensure strong WiFi signal

## Technical Details

### Encoder Configuration

```kotlin
val format = MediaFormat.createVideoFormat(
    MediaFormat.MIMETYPE_VIDEO_AVC,
    width,
    height
)
format.setInteger(
    MediaFormat.KEY_COLOR_FORMAT,
    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
)
format.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
```

### Frame Processing

1. Camera produces YUV_420_888 frames
2. Convert to NV12 format for encoder
3. Queue to MediaCodec input buffer
4. Dequeue encoded H.264 NAL units
5. Extract SPS/PPS on first frame
6. Packetize NAL units into RTP packets
7. Send over UDP or TCP to clients

### Resource Management

- **Encoder Lifecycle**: Created on first frame, destroyed when RTSP disabled
- **UDP Sockets**: Allocated per session in SETUP, closed in TEARDOWN
- **TCP Streams**: Use existing RTSP socket with interleaved framing
- **Memory**: Reuses MediaCodec buffers, minimal allocation per frame

## Comparison: MJPEG vs RTSP

| Feature | MJPEG | RTSP |
|---------|-------|------|
| **Latency** | 150-280ms | 500-1000ms |
| **Bandwidth** | 6-8 Mbps | 2-4 Mbps |
| **Compatibility** | Universal | Modern systems |
| **Quality** | Good (JPEG) | Excellent (H.264) |
| **CPU Usage** | Medium | Low (hardware) |
| **Implementation** | Simple | Complex |
| **Recovery** | Instant | Good |
| **Firewall** | Easy | May need TCP |
| **Best For** | Real-time monitoring | Recording, bandwidth-limited |

## Recommendations

### Use MJPEG When:
- You need the lowest possible latency (<300ms)
- Maximum compatibility is required
- Network bandwidth is not a concern
- Simple deployment is preferred

### Use RTSP When:
- Bandwidth is limited (cellular, metered connections)
- Recording video to disk
- Multiple concurrent viewers
- Integration with modern surveillance systems
- Acceptable latency (~1 second) is sufficient

### Dual-Stream Setup:
Both MJPEG and RTSP can run simultaneously:
- MJPEG for real-time monitoring and motion detection
- RTSP for recording and remote viewing over limited bandwidth

## Future Enhancements

Potential improvements for future versions:

1. **Adaptive Bitrate**: Adjust encoding bitrate based on network conditions
2. **Multiple Resolutions**: Offer different quality streams (low/medium/high)
3. **Audio Support**: Add AAC audio encoding and streaming
4. **Authentication**: Implement RTSP digest authentication
5. **Encryption**: Add SRTP (Secure RTP) support
6. **Performance Tuning**: Configurable bitrate, frame rate, I-frame interval

## References

- **RFC 2326**: Real Time Streaming Protocol (RTSP)
- **RFC 3550**: RTP: A Transport Protocol for Real-Time Applications
- **RFC 6184**: RTP Payload Format for H.264 Video
- **Android MediaCodec**: https://developer.android.com/reference/android/media/MediaCodec
- **RTSP_RECOMMENDATION.md**: Original analysis and protocol selection rationale

## Conclusion

RTSP streaming provides a robust, industry-standard solution for bandwidth-efficient video streaming on IP_Cam. By leveraging hardware-accelerated H.264 encoding and implementing both UDP and TCP transport modes, the RTSP implementation offers excellent compatibility with surveillance systems while significantly reducing bandwidth requirements compared to MJPEG.

The dual-stream architecture (MJPEG + RTSP) ensures users can choose the best protocol for their specific use case, whether prioritizing latency (MJPEG) or bandwidth efficiency (RTSP).
