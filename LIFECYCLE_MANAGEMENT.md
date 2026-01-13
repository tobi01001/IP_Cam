# Lifecycle Management Implementation

## Overview

This document describes the explicit lifecycle control implementation for handlers and coroutines in the IP_Cam application. This addresses the potential pitfall where callbacks might be invoked on destroyed contexts, causing crashes or resource leaks.

## Problem Statement

### Original Issue

When MainActivity is destroyed but CameraService keeps running (foreground service persists), there was a risk that:

1. **Callbacks might be invoked on dead contexts** - Bitmaps could be passed to callbacks tied to a destroyed Activity
2. **Resource leaks** - Bitmaps not properly recycled when callbacks couldn't be invoked
3. **Crashes** - Attempting to update destroyed Views through callbacks
4. **Coroutines might not be cancelled** - Long-running operations continuing after service destroy

## Architecture Decisions

### Why NOT LifecycleService?

The application uses a custom `LifecycleOwner` implementation instead of `LifecycleService` for these reasons:

1. **Service Must Persist Across Activity Lifecycle**
   - Camera operations continue when MainActivity is destroyed
   - Web clients can stream even when UI is not visible
   - Service lifecycle is independent of Activity lifecycle

2. **Precise Control Over Resource Management**
   - Custom LifecycleRegistry gives exact control over state transitions
   - Can set DESTROYED state at the optimal point in cleanup sequence
   - Enables fine-grained lifecycle checks in callbacks

3. **Single Source of Truth**
   - CameraService is the authoritative source for all camera operations
   - Must remain operational regardless of Activity state
   - Callbacks are one-way communication, not bidirectional lifecycle coupling

### Coroutine Scope Strategy

**Custom serviceScope instead of lifecycleScope:**

```kotlin
private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
```

**Rationale:**
- Service needs long-running operations that outlive Activity lifecycle
- SupervisorJob isolates failures - one coroutine failure doesn't cancel others
- Cancelled only in onDestroy() after all other cleanup
- Web streaming, watchdog, and network monitoring must persist independently

## Implementation Details

### 1. Thread-Safe Callback Fields

All callback fields are marked `@Volatile` for thread-safe access:

```kotlin
@Volatile private var onCameraStateChangedCallback: ((CameraSelector) -> Unit)? = null
@Volatile private var onFrameAvailableCallback: ((Bitmap) -> Unit)? = null
@Volatile private var onConnectionsChangedCallback: (() -> Unit)? = null
```

**Why @Volatile:**
- Ensures visibility across threads (service background threads → main thread)
- Prevents reading stale null/non-null state
- No locks needed for simple read/write operations

### 2. Safe Callback Wrappers

Three wrapper methods provide lifecycle-aware callback invocation:

#### safeInvokeCameraStateCallback()

```kotlin
private fun safeInvokeCameraStateCallback(selector: CameraSelector) {
    if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
        Log.w(TAG, "Skipping camera state callback - service lifecycle is DESTROYED")
        return
    }
    onCameraStateChangedCallback?.invoke(selector)
}
```

**Guards Against:**
- Invoking callbacks after service destroyed
- Attempting UI updates on destroyed MainActivity

#### safeInvokeFrameCallback()

```kotlin
private fun safeInvokeFrameCallback(bitmap: Bitmap) {
    if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
        Log.w(TAG, "Skipping frame callback - service lifecycle is DESTROYED, recycling bitmap")
        bitmapPool.returnBitmap(bitmap)
        return
    }
    val callback = onFrameAvailableCallback
    if (callback != null) {
        callback(bitmap)
    } else {
        bitmapPool.returnBitmap(bitmap)
    }
}
```

**Guards Against:**
- Memory leaks from unreturned bitmaps
- Passing bitmaps to destroyed Activity contexts
- Accumulating bitmaps when MainActivity not attached

**Key Feature:** Always returns bitmap to pool if callback can't be invoked

#### safeInvokeConnectionsCallback()

```kotlin
private fun safeInvokeConnectionsCallback() {
    if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
        Log.w(TAG, "Skipping connections callback - service lifecycle is DESTROYED")
        return
    }
    onConnectionsChangedCallback?.invoke()
}
```

**Guards Against:**
- Attempting to update destroyed MainActivity UI
- Crashes from dead View references

### 3. Callback Registration/Cleanup

MainActivity lifecycle integration:

```kotlin
// In MainActivity.onServiceConnected():
cameraService?.setOnCameraStateChangedCallback { _ ->
    runOnUiThread {
        updateUI()
    }
}

// In MainActivity.onDestroy():
if (isServiceBound) {
    cameraService?.clearCallbacks()  // ← CRITICAL: Must be called
    unbindService(serviceConnection)
    isServiceBound = false
}
```

**clearCallbacks() Implementation:**

```kotlin
fun clearCallbacks() {
    Log.d(TAG, "Clearing all MainActivity callbacks")
    onCameraStateChangedCallback = null
    onFrameAvailableCallback = null
    onConnectionsChangedCallback = null
}
```

**Why This Works:**
- Breaks reference cycle between Service and Activity
- Allows Activity to be garbage collected
- Service continues operating, just stops notifying destroyed Activity
- New Activity can re-register callbacks on next bind

### 4. Improved onDestroy() Cleanup Order

Cleanup sequence optimized to prevent crashes:

```kotlin
override fun onDestroy() {
    super.onDestroy()
    Log.d(TAG, "onDestroy() - cleaning up service resources")
    
    // 1. LIFECYCLE: Transition to DESTROYED state FIRST
    lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    
    // 2. CALLBACKS: Clear immediately
    clearCallbacks()
    
    // 3. RECEIVERS & SERVERS: Stop external connections
    unregisterNetworkReceiver()
    orientationEventListener?.disable()
    httpServer?.stop()
    cameraProvider?.unbindAll()
    
    // 4. EXECUTORS: Shutdown in order
    cameraExecutor.shutdown()
    processingExecutor.shutdown()
    processingExecutor.awaitTermination(2, TimeUnit.SECONDS)
    streamingExecutor.shutdownNow()
    
    // 5. BITMAPS: Clear pool and references
    synchronized(bitmapLock) {
        lastFrameBitmap?.let { bitmapPool.returnBitmap(it) }
        lastFrameBitmap = null
    }
    bitmapPool.clear()
    
    // 6. COROUTINES: Cancel LAST
    serviceScope.cancel()
    
    // 7. LOCKS: Release
    releaseLocks()
}
```

**Why This Order:**

1. **Lifecycle FIRST** - Stops new callback invocations immediately
2. **Callbacks SECOND** - Breaks Activity reference cycle early
3. **External Resources** - Close connections that might trigger callbacks
4. **Executors** - Stop frame processing threads cleanly
5. **Memory** - Free bitmaps and pools
6. **Coroutines LAST** - Ensures no coroutines try to use cleaned resources
7. **Locks** - Final cleanup to release system resources

### 5. Executor Management

Three executors with different shutdown strategies:

```kotlin
// Camera executor: Clean shutdown
cameraExecutor.shutdown()

// Processing executor: Wait briefly, then force
processingExecutor.shutdown()
if (!processingExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
    processingExecutor.shutdownNow()
}

// Streaming executor: Immediate force shutdown
streamingExecutor.shutdownNow()
```

**Rationale:**
- Camera: Clean shutdown to avoid frame corruption
- Processing: Brief wait for JPEG encoding, then force
- Streaming: Force immediately to close client connections

## Testing Strategy

### Unit Test Scenarios (Manual)

1. **Rapid Activity Recreation**
   ```
   Test: Rotate device 10 times rapidly while streaming
   Expected: No crashes, callbacks work after each rotation
   Verify: Check logcat for "Clearing all MainActivity callbacks"
   ```

2. **Background Frame Processing**
   ```
   Test: Navigate away from MainActivity during heavy streaming
   Expected: Service continues, no callback crashes
   Verify: Check logcat for "Skipping frame callback" messages
   ```

3. **Service Destroy During Activity Use**
   ```
   Test: Force-stop app from Settings while MainActivity visible
   Expected: Clean shutdown, no resource leaks
   Verify: Check logcat for proper cleanup sequence
   ```

4. **Task Removal**
   ```
   Test: Swipe away app from Recent Apps during streaming
   Expected: Service restarts via onTaskRemoved()
   Verify: Service automatically restarts and continues
   ```

### Memory Leak Testing

Use Android Profiler to verify:

1. **Bitmap Pool Behavior**
   - Bitmaps returned to pool when callbacks unavailable
   - Pool size stays bounded under all conditions

2. **Activity References**
   - MainActivity garbage collected after destroy
   - Service doesn't hold Activity references via callbacks

3. **Coroutine Cleanup**
   - All coroutines cancelled on service destroy
   - No leaked coroutines continuing after cleanup

## Migration Guide

### For Other Services

If adding similar lifecycle safety to other services:

1. **Mark callback fields @Volatile**
   ```kotlin
   @Volatile private var myCallback: ((Data) -> Unit)? = null
   ```

2. **Create safe wrapper**
   ```kotlin
   private fun safeInvokeMyCallback(data: Data) {
       if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
           Log.w(TAG, "Skipping callback - destroyed")
           return
       }
       myCallback?.invoke(data)
   }
   ```

3. **Replace direct invocations**
   ```kotlin
   // Before:
   myCallback?.invoke(data)
   
   // After:
   safeInvokeMyCallback(data)
   ```

4. **Clear in onDestroy()**
   ```kotlin
   override fun onDestroy() {
       super.onDestroy()
       lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
       myCallback = null
       // ... other cleanup
   }
   ```

## Compatibility

**Minimum API Level:** 30 (Android 11+)

No special handling needed for different API levels - all lifecycle APIs used are available on API 30+:
- `LifecycleOwner` and `LifecycleRegistry`
- Kotlin coroutines with structured concurrency
- `@Volatile` annotation for thread safety

## Performance Impact

**Negligible:**
- Lifecycle checks are simple enum comparisons
- No additional allocations
- No synchronization overhead (using @Volatile, not locks)
- Safe wrappers are inline-eligible (JVM may optimize)

**Benefits:**
- Prevents crashes (app stability)
- Prevents memory leaks (improved long-term performance)
- Better resource management (battery efficiency)

## References

- [Android Lifecycle Documentation](https://developer.android.com/topic/libraries/architecture/lifecycle)
- [LifecycleOwner](https://developer.android.com/reference/androidx/lifecycle/LifecycleOwner)
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)
- [Thread Safety in Kotlin](https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html)

## Summary

The lifecycle management implementation provides:

✅ **Crash Prevention** - Callbacks never invoked on destroyed contexts  
✅ **Memory Safety** - Bitmaps always recycled when not delivered  
✅ **Thread Safety** - @Volatile ensures visibility across threads  
✅ **Clean Shutdown** - Proper cleanup order prevents resource leaks  
✅ **Service Persistence** - Camera continues operating independently of Activity  
✅ **Documentation** - Comprehensive inline comments explain decisions  

The implementation follows Android best practices while maintaining the single source of truth architecture required for an IP camera service.
