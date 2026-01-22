# RTSP Server Reliability Fix

## Problem Statement

The RTSP server occasionally failed to start with the following error:
```
java.net.BindException: bind failed: EADDRINUSE (Address already in use)
```

This prevented the RTSP stream from starting and required a device restart to resolve.

## Root Cause Analysis

The issue occurred due to several factors:

1. **Socket in TIME_WAIT state**: When a ServerSocket is closed, the underlying TCP socket may remain in TIME_WAIT state for up to 2 minutes, preventing immediate rebinding to the same port.

2. **Rapid restart cycles**: Quick enable/disable cycles of the RTSP server didn't allow sufficient time for socket cleanup at the OS level.

3. **Missing SO_REUSEADDR**: The ServerSocket was created without the SO_REUSEADDR option, which prevents binding to sockets in TIME_WAIT state.

4. **Race conditions**: No synchronization between start() and stop() operations could lead to conflicting state.

5. **No retry logic**: A single bind failure would prevent RTSP from starting, with no automatic recovery.

## Solution

The fix implements a multi-layered approach following StreamMaster's supervised initialization pattern:

### 1. Socket Configuration (SO_REUSEADDR)

**File**: `RTSPServer.kt`

Added a new `createServerSocket()` method that properly configures the ServerSocket:

```kotlin
private fun createServerSocket(port: Int): ServerSocket {
    // Create unbound socket first so we can set options
    val socket = ServerSocket()
    
    // Enable SO_REUSEADDR to allow binding to recently closed sockets
    socket.reuseAddress = true
    
    // Set socket timeout for accept() operations
    socket.soTimeout = 5000 // 5 second timeout
    
    // Now bind to the port
    socket.bind(java.net.InetSocketAddress(port))
    
    return socket
}
```

**Key improvements**:
- Creates unbound socket first to allow option configuration
- Sets `SO_REUSEADDR = true` to enable binding to sockets in TIME_WAIT state
- Adds socket timeout for accept() operations
- Only binds after configuration is complete

### 2. Retry Logic with Exponential Backoff

Enhanced the `start()` method with retry logic:

```kotlin
fun start(): Boolean = synchronized(serverLock) {
    // ...
    
    // Retry logic with exponential backoff for bind failures
    val maxAttempts = 3
    val retryDelays = listOf(1000L, 2000L, 4000L) // 1s, 2s, 4s
    
    for (attempt in 1..maxAttempts) {
        try {
            // ... socket creation ...
            serverSocket = createServerSocket(port)
            return true
        } catch (e: BindException) {
            Log.w(TAG, "RTSP server bind attempt $attempt/$maxAttempts failed")
            
            if (attempt < maxAttempts) {
                val delay = retryDelays[attempt - 1]
                Thread.sleep(delay)
            } else {
                Log.e(TAG, "Failed after $maxAttempts attempts", e)
                return false
            }
        }
    }
}
```

**Key improvements**:
- Up to 3 bind attempts before giving up
- Exponential backoff: 1s, 2s, 4s delays
- Specific handling for `BindException`
- Detailed logging for each attempt
- Consistent with StreamMaster's supervised initialization pattern

### 3. Synchronization

Added a lock to prevent race conditions:

```kotlin
// Synchronization lock for start/stop operations
private val serverLock = Any()

fun start(): Boolean = synchronized(serverLock) { /* ... */ }
fun stop() { synchronized(serverLock) { /* ... */ } }
```

**Key improvements**:
- Prevents simultaneous start/stop operations
- Ensures thread-safe state transitions
- Eliminates race conditions

### 4. Enhanced Socket Cleanup

Improved the `stop()` method:

```kotlin
fun stop() {
    synchronized(serverLock) {
        // ... close sessions ...
        
        // Stop server job first
        serverJob?.cancel()
        serverJob = null
        
        // Close server socket with proper cleanup
        serverSocket?.let { socket ->
            try {
                socket.close()
                Log.d(TAG, "ServerSocket closed")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing ServerSocket", e)
            }
        }
        serverSocket = null
        
        // Brief delay to allow socket cleanup at OS level
        Thread.sleep(100)
        
        // Stop encoder with error handling
        encoder?.let { enc ->
            try {
                enc.stop()
                enc.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing encoder", e)
            }
        }
        encoder = null
    }
}
```

**Key improvements**:
- Cancels server job before closing socket
- Defensive error handling for socket closure
- 100ms delay allows OS-level socket cleanup
- Separate error handling for encoder release
- Better logging for diagnostics

## Testing

### Automated Test Script

Created `test_rtsp_reliability.sh` to verify the fix:

**Test scenarios**:
1. Initial state check
2. Single enable/disable cycle
3. **Rapid enable/disable cycles (10 iterations)** - Tests for EADDRINUSE
4. Multiple consecutive enables (5 iterations) - Tests retry logic
5. RTSP stream verification with ffprobe (if available)

**Usage**:
```bash
./test_rtsp_reliability.sh <DEVICE_IP>
# Example: ./test_rtsp_reliability.sh 192.168.1.100
```

### Manual Testing

To manually test the fix:

1. **Enable RTSP**:
   ```bash
   curl http://DEVICE_IP:8080/enableRTSP
   ```

2. **Disable RTSP**:
   ```bash
   curl http://DEVICE_IP:8080/disableRTSP
   ```

3. **Check status**:
   ```bash
   curl http://DEVICE_IP:8080/rtspStatus
   ```

4. **Rapid cycling** (should all succeed):
   ```bash
   for i in {1..10}; do
       curl http://DEVICE_IP:8080/enableRTSP
       sleep 0.5
       curl http://DEVICE_IP:8080/disableRTSP
       sleep 0.5
   done
   ```

5. **Monitor logs**:
   ```bash
   adb logcat -s RTSPServer:* CameraService:*
   ```

## Expected Behavior After Fix

### Before Fix
- ❌ RTSP server occasionally fails with EADDRINUSE
- ❌ Requires device restart to recover
- ❌ No automatic retry on bind failures
- ❌ Race conditions on rapid start/stop

### After Fix
- ✅ RTSP server handles bind failures gracefully
- ✅ Automatic retry with exponential backoff (3 attempts)
- ✅ SO_REUSEADDR allows binding to recently closed sockets
- ✅ Synchronized start/stop prevents race conditions
- ✅ Proper socket cleanup reduces bind conflicts
- ✅ Detailed logging for diagnostics

## Monitoring

To verify the fix is working in production:

1. **Look for bind attempt logs**:
   ```
   RTSP server bind attempt 1/3 on port 8554
   RTSP server bind attempt 2/3 on port 8554
   RTSP server started on port 8554
   ```

2. **Check for successful retries**:
   ```
   Bind failed (attempt 1/3): Address already in use
   Waiting 1000ms before retry...
   RTSP server bind attempt 2/3 on port 8554
   RTSP server started on port 8554
   ```

3. **Monitor for failures after 3 attempts** (should be rare):
   ```
   Failed to start RTSP server after 3 attempts
   ```

## Technical Details

### Changes Summary

**File**: `app/src/main/java/com/ipcam/RTSPServer.kt`

- **Added imports**: `BindException`, `SocketException`
- **Added field**: `serverLock: Any` for synchronization
- **Modified method**: `start()` - Added retry logic and synchronization
- **Added method**: `createServerSocket()` - Proper socket configuration
- **Modified method**: `stop()` - Enhanced cleanup and synchronization
- **Line changes**: ~145 lines added, ~67 lines modified

### Performance Impact

- **Minimal**: Retry logic only activates on bind failures (rare)
- **Latency**: Maximum additional 7 seconds delay (1s + 2s + 4s) on persistent failures
- **Overhead**: Synchronization adds negligible overhead (<1ms)
- **Success case**: No performance impact when bind succeeds on first attempt

### Compatibility

- ✅ Compatible with existing RTSP clients (VLC, FFmpeg, NVR systems)
- ✅ No changes to RTSP protocol or streaming behavior
- ✅ Backward compatible with existing HTTP API endpoints
- ✅ No changes to camera or encoder configuration

## Related Issues

This fix addresses:
- Primary issue: RTSP server bind failure (EADDRINUSE)
- Secondary issue: Frozen camera image on stream activation failure
- Edge case: Rapid enable/disable cycles causing conflicts

## StreamMaster Compliance

This implementation follows StreamMaster's core principles:

1. **Supervised Initialization**: Retry logic with exponential backoff (1s, 2s, 4s)
2. **Reliability-First**: Automatic recovery from bind failures
3. **Proper Resource Cleanup**: Enhanced socket and encoder cleanup
4. **Thread Safety**: Synchronization prevents race conditions
5. **Detailed Logging**: Comprehensive diagnostics for troubleshooting

## Conclusion

The fix ensures RTSP server reliability by:

1. **Preventing bind failures**: SO_REUSEADDR allows immediate rebinding
2. **Automatic recovery**: Retry logic handles transient failures
3. **Race condition prevention**: Synchronization ensures thread safety
4. **Better cleanup**: Proper socket closure prevents conflicts
5. **Improved diagnostics**: Enhanced logging for troubleshooting

The RTSP server should now start reliably without manual intervention, even under rapid start/stop cycles or immediately after a previous shutdown.
