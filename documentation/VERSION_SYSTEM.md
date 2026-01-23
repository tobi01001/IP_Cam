# Version and Build Information System

## Overview

The IP Camera application implements an automatic version and build information system that displays version, git commit, branch, and build details in both the Android app UI and the web interface. This system is designed to make debugging, support, and deployment tracking easier.

## Implementation Details

### 1. Build Configuration (build.gradle)

The version information is automatically generated during the build process using Gradle BuildConfig fields. The `app/build.gradle` file includes helper functions that extract git information:

- **Git Commit Hash**: Short 7-character SHA of the current commit (`git rev-parse --short HEAD`)
- **Git Branch**: Current branch name (`git rev-parse --abbrev-ref HEAD`)
- **Build Timestamp**: UTC timestamp in format `yyyyMMdd-HHmmss`
- **Build Number**: Auto-incrementing number based on timestamp (format: `yyyyMMddHHmmss`)

These values are injected into the BuildConfig class at compile time:

```kotlin
buildConfigField "String", "GIT_COMMIT_HASH", "\"${getGitCommitHash()}\""
buildConfigField "String", "GIT_BRANCH", "\"${getGitBranch()}\""
buildConfigField "String", "BUILD_TIMESTAMP", "\"${getBuildTimestamp()}\""
buildConfigField "long", "BUILD_NUMBER", "${getBuildNumber()}"
```

### 2. BuildInfo Object

The `BuildInfo.kt` file provides a centralized interface to access version information throughout the app. It exposes:

- `versionName`: Application version (e.g., "1.0")
- `versionCode`: Integer version code
- `commitHash`: Git commit SHA (short form)
- `branch`: Git branch name
- `buildTimestamp`: Build timestamp in UTC
- `buildNumber`: Unique, monotonically increasing build number

Helper methods:
- `getVersionString()`: Returns formatted version (e.g., "v1.0 (main@a4c656b)")
- `getBuildString()`: Returns formatted build info (e.g., "Build 20260102161234 - 20260102-161234")
- `getFullVersionString()`: Returns complete version info (e.g., "v1.0 (main@a4c656b) | Build 20260102161234")
- `toMap()`: Returns version data as a map for JSON serialization

### 3. Display Locations

#### Android App (MainActivity)

A version TextView is displayed at the bottom of the main activity layout showing the full version string. The text is:
- Font size: 11sp
- Style: Italic
- Color: #666666 (gray)
- Centered alignment
- Located at the bottom of the scrollable layout

Example display: `v1.0 (copilot/add-version-build-display-feature@d837737) | Build 20260102162247`

#### Web Interface

A footer section is added to the web page displaying:
- "IP Camera Server" heading
- Version string (branch@commit)
- Build information with timestamp

The footer is:
- Centered with gray italicized text
- Separated from main content with a horizontal rule
- Font size: 11px
- Located at the bottom of the main container

#### HTTP /status Endpoint

The `/status` JSON endpoint includes a `version` object with all build details:

```json
{
  "status": "running",
  "server": "Ktor",
  "camera": "back",
  "version": {
    "versionName": "1.0",
    "versionCode": 1,
    "commitHash": "d837737",
    "branch": "copilot/add-version-build-display-feature",
    "buildTimestamp": "20260102-162247",
    "buildNumber": 20260102162247
  },
  ...
}
```

## Build Number Generation

The build number is automatically generated using a timestamp-based approach:

- **Format**: `yyyyMMddHHmmss` (UTC)
- **Example**: `20260102162247` represents 2026-01-02 16:22:47 UTC
- **Advantages**:
  - Unique for every build (unless built within the same second)
  - Monotonically increasing
  - Human-readable timestamp embedded
  - No need for external build counter or CI integration
  - Works for both local and CI builds

**Note**: If two builds occur within the same second, they will have the same build number. In production CI/CD scenarios, you may want to use an environment variable or CI build counter instead.

## Usage in CI/CD

For CI/CD environments, you can enhance the build number generation:

### Option 1: Use CI Build Number

Modify `app/build.gradle` to check for an environment variable:

```groovy
def getBuildNumber() {
    def ciBuildNumber = System.getenv("CI_BUILD_NUMBER")
    if (ciBuildNumber != null && !ciBuildNumber.isEmpty()) {
        return ciBuildNumber.toLong()
    }
    return new Date().format('yyyyMMddHHmmss', TimeZone.getTimeZone('UTC')).toLong()
}
```

Set `CI_BUILD_NUMBER` in your CI workflow.

### Option 2: Detect Pull Requests

You can enhance git branch detection to show PR numbers:

```groovy
def getGitBranch() {
    def prNumber = System.getenv("GITHUB_PR_NUMBER") ?: System.getenv("CI_PULL_REQUEST")
    if (prNumber != null && !prNumber.isEmpty()) {
        return "PR-${prNumber}"
    }
    // ... existing implementation
}
```

## Benefits

1. **Debugging Support**: Quickly identify which version a user is running
2. **Deployment Tracking**: Track which commit/branch is deployed where
3. **Issue Reporting**: Users can easily provide version information
4. **Development Workflow**: Developers can verify they're testing the right build
5. **Automated**: No manual version updates required
6. **Consistent**: Same version info shown everywhere (app, web, API)

## Maintenance

The version system requires no manual maintenance:

- Build numbers auto-increment with each build
- Git information is automatically extracted
- No version file editing needed
- Works in both local development and CI/CD

To update the semantic version (e.g., from 1.0 to 1.1), simply modify the `versionName` and `versionCode` fields in `app/build.gradle`:

```groovy
defaultConfig {
    versionCode 2
    versionName "1.1"
    // ... rest of config
}
```

## Testing

To verify the version system is working:

1. **Build the app**: `./gradlew assembleDebug`
2. **Check BuildConfig**: Verify `app/build/generated/source/buildConfig/debug/com/ipcam/BuildConfig.java` contains the git fields
3. **Run the app**: Install the APK and check the version footer at the bottom of the main screen
4. **Check web UI**: Start the server and open `http://DEVICE_IP:8080/` - scroll to the bottom to see version footer
5. **Check API**: Query `http://DEVICE_IP:8080/status` and verify the `version` object in the JSON response

## Future Enhancements

Potential improvements to consider:

1. **Git Tag Integration**: Use `git describe --tags` for semantic versioning
2. **CI Build Numbers**: Integrate with GitHub Actions or other CI build counters
3. **Build Flavor Support**: Different version info for debug vs release builds
4. **Version Check**: API endpoint to check for newer versions
5. **Changelog Integration**: Link version to changelog or release notes
6. **Debug Mode Indicator**: Show debug/release build type in version string
