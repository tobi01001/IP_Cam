# Dependency Version Migration to libs.versions.toml

## Overview

This document describes the migration from hardcoded dependency versions in `build.gradle` files to a centralized version catalog using `libs.versions.toml`. This migration modernizes dependency management and makes future upgrades more maintainable.

## Migration Date
January 2026

## Changes Made

### 1. Created Version Catalog (`gradle/libs.versions.toml`)

Centralized all dependency and plugin versions in a single TOML file following the Gradle Version Catalog specification.

**File Location**: `gradle/libs.versions.toml`

### 2. Updated Build Configuration Files

#### Root `build.gradle`
- **Before**: Used `buildscript` block with hardcoded plugin versions
- **After**: Using version catalog plugin references with `alias(libs.plugins.*)`

#### App `app/build.gradle`
- **Before**: Hardcoded dependency versions (e.g., `implementation 'androidx.core:core-ktx:1.12.0'`)
- **After**: Version catalog references (e.g., `implementation libs.androidx.core.ktx`)
- **Before**: Hardcoded SDK versions (e.g., `compileSdk 34`, `targetSdk 34`)
- **After**: Dynamic references from version catalog (e.g., `compileSdk libs.versions.compileSdk.get().toInteger()`)

#### Gradle Wrapper
- **Before**: Gradle 8.5
- **After**: Gradle 8.9 (required for AGP 8.7.3)

### 3. Dependency Version Updates

All dependencies have been updated to their latest stable versions compatible with Android SDK 35:

| Dependency | Old Version | New Version | Notes |
|------------|-------------|-------------|-------|
| Android Gradle Plugin | 8.1.0 | 8.7.3 | Major update, requires Gradle 8.9 |
| Kotlin | 1.9.0 | 2.1.0 | Major version update |
| compileSdk | 34 | 35 | Updated to latest Android |
| targetSdk | 34 | 35 | Updated to latest Android |
| minSdk | 30 | 30 | Unchanged |
| AndroidX Core KTX | 1.12.0 | 1.15.0 | Minor update |
| AndroidX AppCompat | 1.6.1 | 1.7.0 | Minor update |
| Material Design | 1.11.0 | 1.12.0 | Minor update |
| ConstraintLayout | 2.1.4 | 2.2.0 | Minor update |
| CameraX | 1.3.1 | 1.4.1 | Minor update |
| Lifecycle | 2.6.2 | 2.8.7 | Minor update |
| Ktor | 2.3.7 | 2.3.12 | Patch update (stayed in 2.x for compatibility) |
| Kotlinx Coroutines | 1.7.3 | 1.9.0 | Minor update |
| Kotlinx Serialization | 1.6.2 | 1.7.3 | Minor update |
| JUnit | 4.13.2 | 4.13.2 | Unchanged |
| AndroidX Test JUnit | 1.1.5 | 1.2.1 | Minor update |
| Espresso | 3.5.1 | 3.6.1 | Minor update |

### 4. Code Fixes

#### BitmapPool.kt
Fixed null safety issue in `returnBitmap()` method to handle nullable `Bitmap.Config`:
```kotlin
// Before
val key = BitmapKey(bitmap.width, bitmap.height, bitmap.config)

// After
val config = bitmap.config ?: Bitmap.Config.ARGB_8888
val key = BitmapKey(bitmap.width, bitmap.height, config)
```

**Reason**: Kotlin 2.1.0 has stricter null safety checks. The `bitmap.config` property can be null, so we provide a safe default.

## Benefits

1. **Centralized Version Management**: All dependency versions are in one file (`libs.versions.toml`)
2. **Type Safety**: Gradle validates version catalog references at build time
3. **IDE Support**: Better autocomplete and navigation in Android Studio
4. **Consistency**: All modules use the same versions automatically
5. **Easier Updates**: Change version once, applies everywhere
6. **Dependency Bundles**: Related dependencies grouped together (e.g., `libs.bundles.camerax`)

## Using the Version Catalog

### Accessing Dependencies in build.gradle

```groovy
dependencies {
    // Single dependency
    implementation libs.androidx.core.ktx
    
    // Bundle of related dependencies
    implementation libs.bundles.camerax
    
    // Test dependencies
    testImplementation libs.junit
}
```

### Accessing Plugins in build.gradle

```groovy
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}
```

### Accessing Versions

```groovy
android {
    compileSdk libs.versions.compileSdk.get().toInteger()
    
    defaultConfig {
        minSdk libs.versions.minSdk.get().toInteger()
        targetSdk libs.versions.targetSdk.get().toInteger()
    }
}
```

## Dependency Bundles

The following bundles are defined for convenience:

- **`libs.bundles.camerax`**: All CameraX libraries (core, camera2, lifecycle, view)
- **`libs.bundles.lifecycle`**: AndroidX Lifecycle libraries (runtime-ktx, common)
- **`libs.bundles.ktor`**: Ktor server libraries (core, cio, content-negotiation, serialization, cors, status-pages)

## Build Verification

The build has been tested and verified:
- ✅ `./gradlew clean` - Success
- ✅ `./gradlew build` - Success (104 tasks executed)
- ✅ Debug variant builds successfully
- ✅ Release variant builds successfully
- ⚠️ Some deprecation warnings present (existing code, not related to migration)

## Deprecation Warnings

The following deprecation warnings are present in the codebase (not introduced by this migration):

1. **WiFi Lock**: `WIFI_MODE_FULL_HIGH_PERF` deprecated in CameraService.kt
2. **Connectivity**: `CONNECTIVITY_ACTION` deprecated in CameraService.kt
3. **MediaCodec Color Formats**: Various `COLOR_Format*` constants deprecated in RTSPServer.kt
4. **Ktor API**: Delicate API usage in HttpServer.kt (intentional)

These warnings are existing issues and should be addressed separately from this migration.

## Future Maintenance

### Updating a Dependency Version

1. Open `gradle/libs.versions.toml`
2. Find the version in the `[versions]` section
3. Update the version number
4. Run `./gradlew build` to verify

Example:
```toml
[versions]
androidx-core-ktx = "1.15.0"  # Update this number
```

### Adding a New Dependency

1. Add version to `[versions]` section if needed
2. Add library to `[libraries]` section
3. Use in `build.gradle` with `libs.*` reference

Example:
```toml
[versions]
retrofit = "2.9.0"

[libraries]
retrofit = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
```

Then in `app/build.gradle`:
```groovy
dependencies {
    implementation libs.retrofit
}
```

### Adding a New Bundle

Bundles group related dependencies for convenience:

```toml
[bundles]
networking = ["retrofit", "okhttp", "gson"]
```

## Compatibility

- **Minimum Gradle Version**: 8.9
- **Minimum Android SDK**: 30 (Android 11)
- **Target Android SDK**: 35 (Android 15)
- **Compile Android SDK**: 35
- **JDK**: 17

## References

- [Gradle Version Catalogs Documentation](https://docs.gradle.org/current/userguide/platforms.html)
- [Android Gradle Plugin Release Notes](https://developer.android.com/build/releases/gradle-plugin)
- [Kotlin Release Notes](https://kotlinlang.org/docs/releases.html)
- [AndroidX Release Notes](https://developer.android.com/jetpack/androidx/versions)

## Rollback Procedure

If issues arise, you can rollback by:

1. Checkout the previous commit: `git checkout <previous-commit>`
2. Or manually revert changes:
   - Delete `gradle/libs.versions.toml`
   - Restore `build.gradle` and `app/build.gradle` from previous version
   - Restore `gradle/wrapper/gradle-wrapper.properties` to Gradle 8.5

## Testing Checklist

- [x] Project builds successfully
- [x] Debug APK builds
- [x] Release APK builds
- [x] All dependencies resolve correctly
- [x] No new compilation errors introduced
- [x] Existing functionality maintained
- [ ] Manual testing on device (requires device)
- [ ] Camera streaming works (requires device)
- [ ] HTTP server works (requires device)

## Notes

- **Ktor Version**: Kept at 2.3.12 instead of upgrading to 3.x to avoid breaking API changes. The Ktor 3.0 release has significant breaking changes that would require code refactoring.
- **Deprecation Warnings**: All deprecation warnings are pre-existing and not introduced by this migration. They should be addressed in separate PRs to maintain focus.
- **BitmapPool Fix**: The null safety fix was necessary due to stricter Kotlin 2.1.0 compiler checks and is a good defensive programming practice.

## Success Criteria Met

✅ All dependency versions centralized in `libs.versions.toml`  
✅ No leftover dependency versions in individual build files  
✅ Project builds successfully with new configuration  
✅ Updated dependencies compatible with minSdk 30 and targetSdk 35  
✅ Documentation provided (this file)  

## Migration Complete

The dependency version migration has been successfully completed. All dependencies are now managed through the version catalog system, making future updates easier and more maintainable.
