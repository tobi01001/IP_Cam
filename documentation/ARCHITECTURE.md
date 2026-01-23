# IP_Cam Architecture Documentation

## Overview

IP_Cam is an Android application that turns Android devices into IP cameras with HTTP streaming capabilities. This document explains the architectural decisions, particularly around concurrent connection handling.

## Connection Handling Architecture

### Problem Statement (Fixed in v1.1)

**Issue**: Cannot establish a second connection (simultaneous endpoints fail)

The original implementation had a critical bottleneck where long-lived streaming connections (`/stream` and `/events` endpoints) would occupy HTTP handler threads indefinitely. This led to thread pool exhaustion, preventing new connections even when the connection counter showed capacity.

### Root Cause Analysis

1. **Thread Pool Design**: Used `ThreadPoolExecutor` with only 8 maximum threads
2. **Blocking Streams**: Each `/stream` (MJPEG) and `/events` (SSE) connection blocked one thread for its entire duration
3. **Thread Starvation**: When 8 long-lived connections were active, all threads were exhausted and new requests were rejected

Example scenario:
- 3 clients viewing `/stream` = 3 threads occupied
- 2 clients listening to `/events` = 2 threads occupied  
- 1 browser viewing index page = 1 thread for page load
- Total: 6 threads used
- Any additional connection would wait in queue or be rejected if queue was full

### Solution Architecture

#### 1. Increased Thread Pool Capacity

```kotlin
private const val HTTP_MAX_POOL_SIZE = 32  // Increased from 8
```

This allows more concurrent HTTP request handlers, but alone doesn't solve the blocking issue.

#### 2. Dedicated Streaming Executor

```kotlin
private val streamingExecutor = Executors.newCachedThreadPool { r -> 
    Thread(r, "StreamingThread-${System.currentTimeMillis()}").apply {
        isDaemon = true
    }
}
```

A separate `CachedThreadPool` handles all long-lived streaming work. This pool:
- Creates threads on-demand
- Reuses idle threads
- Terminates threads after 60 seconds of inactivity
- Uses daemon threads that don't prevent JVM shutdown

#### 3. Non-Blocking Stream Pattern with PipedStreams

**Before (Blocking)**:
```kotlin
// HTTP handler thread blocked here
val inputStream = object : java.io.InputStream() {
    override fun read(): Int {
        while (...) {
            Thread.sleep(100)  // BLOCKS HTTP THREAD!
            // ... streaming logic
        }
    }
}
return newChunkedResponse(Status.OK, "...", inputStream)
```

**After (Non-Blocking)**:
```kotlin
val pipedOutputStream = PipedOutputStream()
val pipedInputStream = PipedInputStream(pipedOutputStream, 1024 * 1024)

// Submit work to dedicated executor - HTTP handler returns immediately
streamingExecutor.submit {
    while (streamActive) {
        // Write frames to pipe
        pipedOutputStream.write(frameData)
        Thread.sleep(100)  // Blocks streaming thread, NOT HTTP thread
    }
}

// HTTP handler returns immediately
return newChunkedResponse(Status.OK, "...", pipedInputStream)
```

#### 4. Connection Tracking

```kotlin
private val activeStreams = AtomicInteger(0)
private val sseClients = mutableListOf<SSEClient>()
```

Separate tracking for:
- **HTTP connections**: Counted by `BoundedAsyncRunner` (requests currently in HTTP thread pool)
- **Active streams**: Tracked by `activeStreams` counter (ongoing MJPEG streams)
- **SSE clients**: Tracked in `sseClients` list (Server-Sent Events connections)

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                        Client Requests                           │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                   NanoHTTPD Server                               │
│                   (Port 8080)                                    │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│              BoundedAsyncRunner (Thread Pool)                    │
│           HTTP_MAX_POOL_SIZE = 32 threads                        │
│           Handles: serve() method execution                      │
└────────────┬───────────────────────────┬────────────────────────┘
             │                           │
     Short-lived requests          Long-lived requests
     (snapshot, status, etc)       (stream, events)
             │                           │
             ▼                           ▼
    ┌────────────────┐         ┌─────────────────────────────┐
    │  Return JSON/  │         │  Return immediately with    │
    │  Image data    │         │  PipedInputStream           │
    │  (thread freed)│         │  (thread freed)             │
    └────────────────┘         └────────────┬────────────────┘
                                            │
                                            ▼
                         ┌──────────────────────────────────────┐
                         │  Streaming Executor (Separate Pool)  │
                         │  CachedThreadPool (unbounded)        │
                         │  Handles: Frame writing to pipes     │
                         └──────────────────────────────────────┘
                                            │
                         ┌──────────────────┴──────────────────┐
                         │                                     │
                    MJPEG Stream                          SSE Events
                (write frames @ 10fps)              (write updates as they occur)
                         │                                     │
                         ▼                                     ▼
                  PipedOutputStream                    PipedOutputStream
                         │                                     │
                         ▼                                     ▼
                  PipedInputStream                     PipedInputStream
                         │                                     │
                         ▼                                     ▼
                    To Client                            To Client
```

### Benefits

1. **Scalability**: Can handle 32+ concurrent requests
2. **No Thread Blocking**: HTTP threads return immediately, not blocked by streaming
3. **Resource Efficiency**: Streaming threads are created/destroyed as needed
4. **Better Monitoring**: Separate counters for HTTP connections, streams, and SSE clients
5. **Graceful Degradation**: Under extreme load, requests queue rather than failing immediately

### Endpoints

| Endpoint | Type | Thread Usage | Notes |
|----------|------|--------------|-------|
| `/` | Short-lived | HTTP thread only | Returns HTML page |
| `/snapshot` | Short-lived | HTTP thread only | Returns single JPEG |
| `/status` | Short-lived | HTTP thread only | Returns JSON status |
| `/switch` | Short-lived | HTTP thread only | Switches camera |
| `/stream` | Long-lived | HTTP thread + Streaming thread | MJPEG stream |
| `/events` | Long-lived | HTTP thread + Streaming thread | Server-Sent Events |

### Configuration Constants

```kotlin
// HTTP Thread Pool
HTTP_CORE_POOL_SIZE = 4      // Minimum threads kept alive
HTTP_MAX_POOL_SIZE = 32      // Maximum concurrent HTTP handlers
HTTP_QUEUE_CAPACITY = 50     // Max queued requests before rejection
HTTP_KEEP_ALIVE_TIME = 60L   // Thread idle timeout (seconds)

// Streaming
STREAM_FRAME_DELAY_MS = 100L // ~10 fps for MJPEG
```

## Testing Concurrent Connections

To verify multiple simultaneous connections work:

```bash
# Terminal 1: Start first stream
curl http://DEVICE_IP:8080/stream > stream1.mjpeg &

# Terminal 2: Start second stream
curl http://DEVICE_IP:8080/stream > stream2.mjpeg &

# Terminal 3: Check status
curl http://DEVICE_IP:8080/status

# Should show:
# "activeConnections": 2
# "activeStreams": 2
```

## Future Improvements

1. **Authentication**: Add basic auth or token-based authentication
2. **Bandwidth Throttling**: Per-client bandwidth limits
3. **Quality Adaptation**: Adjust quality based on network conditions
4. **WebRTC Support**: For even lower latency
5. **HLS Streaming**: For better mobile browser support

## Performance Considerations

- **Memory**: Each stream requires ~1MB buffer for PipedInputStream
- **CPU**: ~10% per streaming client on modern devices
- **Battery**: Continuous streaming uses ~5-10% battery per hour
- **Network**: ~2 Mbps per 1080p stream at 10fps

## Monitoring

Check server health via `/status` endpoint:

```json
{
  "status": "running",
  "camera": "back",
  "activeConnections": 5,
  "maxConnections": 32,
  "activeStreams": 2,
  "activeSSEClients": 3
}
```

- `activeConnections`: Current HTTP requests being processed
- `maxConnections`: Thread pool capacity
- `activeStreams`: Ongoing MJPEG streams
- `activeSSEClients`: Connected SSE clients for real-time updates
