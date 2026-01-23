# Automated Version Management System

## Overview

The IP_Cam application implements an automated version management system that:
1. **Automatically increments the minor version** when PRs are merged to `main`
2. **Displays beta labels** for non-main branches
3. **Avoids self-triggering workflows** using `[skip ci]` tags
4. **Maintains version in a central file** (`version.properties`)

## Architecture

### Components

#### 1. Version Storage (`version.properties`)
Central file that stores version information:
```properties
VERSION_NAME=1.2
VERSION_CODE=1
```

- **VERSION_NAME**: Semantic version in `MAJOR.MINOR` format (e.g., 1.2)
- **VERSION_CODE**: Integer code that increments with each release (used by Android)

#### 2. Build Configuration (`app/build.gradle`)
Reads version from `version.properties` at build time:
```groovy
def getVersionProperties() {
    def versionPropsFile = file("${rootProject.projectDir}/version.properties")
    def versionProps = new Properties()
    versionPropsFile.withInputStream { stream ->
        versionProps.load(stream)
    }
    return versionProps
}

android {
    defaultConfig {
        def versionProps = getVersionProperties()
        versionCode versionProps['VERSION_CODE'].toInteger()
        versionName versionProps['VERSION_NAME']
        ...
    }
}
```

#### 3. Beta Labeling (`BuildInfo.kt`)
Automatically appends "-beta" suffix when branch is not `main`:
```kotlin
fun getVersionString(): String {
    val betaSuffix = if (branch != "main") "-beta" else ""
    return "v$versionName$betaSuffix ($branch@$commitHash)"
}
```

**Examples:**
- On `main` branch: `v1.2 (main@a4c656b)`
- On feature branch: `v1.2-beta (feature/camera-fix@a4c656b)`
- On PR branch: `v1.2-beta (copilot/add-feature@a4c656b)`

#### 4. GitHub Actions Workflow (`.github/workflows/version-bump.yml`)
Automated workflow that:
1. Triggers on push to `main` (after PR merge)
2. Reads current version from `version.properties`
3. Increments minor version (e.g., 1.2 → 1.3)
4. Increments version code (e.g., 1 → 2)
5. Updates `version.properties` file
6. Commits with `[skip ci]` tag to prevent infinite loops
7. Pushes directly to `main` (no new PR required)

## Workflow Details

### Trigger Conditions
The workflow runs when:
- ✅ A PR is merged to `main` branch
- ✅ The commit message does NOT contain `[skip ci]`
- ✅ The commit message does NOT contain `Auto version bump`
- ✅ Changes are NOT to ignored paths (workflows, docs, markdown files)

### Version Increment Logic
```bash
# Current version: 1.2 (code: 1)
MAJOR=1
MINOR=2
CODE=1

# After increment
NEW_MINOR=$((MINOR + 1))  # 2 + 1 = 3
NEW_VERSION="1.3"
NEW_CODE=$((CODE + 1))    # 1 + 1 = 2
```

### Preventing Self-Triggering
Multiple safeguards prevent infinite workflow loops:

1. **`[skip ci]` tag**: The version bump commit message includes this tag
2. **Message check**: Workflow skips if message contains "Auto version bump"
3. **Path ignore**: Workflow ignores changes to `.github/workflows/**`
4. **Conditional job**: `if` condition checks commit message

## Beta Label Behavior

### Display Locations
Beta labels appear in:
1. **Android App UI**: Bottom of MainActivity
2. **Web Interface**: Footer section
3. **HTTP `/status` Endpoint**: JSON response under `version` object

### Branch Detection
The system uses the git branch name from BuildConfig:
- Built on `main`: No beta label
- Built on any other branch: `-beta` suffix added

**Important**: The beta label is determined at build time, not runtime. This means:
- ✅ Builds on feature branches always show beta
- ✅ Builds on `main` never show beta
- ✅ No runtime branch detection needed

## Usage Examples

### Normal Development Workflow

1. **Create feature branch**
   ```bash
   git checkout -b feature/new-camera-support
   ```

2. **Make changes and build**
   - App shows: `v1.2-beta (feature/new-camera-support@abc123)`
   - Web shows: `v1.2-beta (feature/new-camera-support@abc123)`

3. **Create PR to main**
   - PR build still shows: `v1.2-beta (feature/new-camera-support@def456)`

4. **Merge PR to main**
   - GitHub Actions workflow triggers
   - Version bumps from 1.2 to 1.3
   - `version.properties` updated automatically
   - Commit message: "Auto version bump to 1.3 [skip ci]"
   - Pushed to main without new PR

5. **Next build from main**
   - App shows: `v1.3 (main@ghi789)`
   - No beta label on main branch

### Manual Version Changes

To manually change the version (e.g., for major version bump):

1. Edit `version.properties`:
   ```properties
   VERSION_NAME=2.0
   VERSION_CODE=10
   ```

2. Commit and push to main:
   ```bash
   git add version.properties
   git commit -m "Bump to version 2.0 for major release [skip ci]"
   git push origin main
   ```

3. Include `[skip ci]` to prevent automatic increment

### Version Verification

Check current version without building:
```bash
cat version.properties
```

Check version in built app:
```bash
# Android app
adb shell dumpsys package com.ipcam | grep versionName

# Web interface
curl http://DEVICE_IP:8080/status | jq '.version'
```

## Implementation Files

### Modified Files
1. **`app/build.gradle`**
   - Added `getVersionProperties()` function
   - Changed `defaultConfig` to read from `version.properties`

2. **`app/src/main/java/com/ipcam/BuildInfo.kt`**
   - Modified `getVersionString()` to add beta suffix for non-main branches

### New Files
1. **`version.properties`**
   - Central version storage file
   - Updated by GitHub Actions

2. **`.github/workflows/version-bump.yml`**
   - Automated version increment workflow
   - Triggers on merge to main

## Testing

### Test Beta Label (Local)
```bash
# On feature branch
git checkout -b test/beta-label
./gradlew assembleDebug
# Install and verify app shows "-beta" suffix
```

### Test Version Reading (Local)
```bash
# Verify build.gradle reads version.properties correctly
./gradlew clean assembleDebug
# Check BuildConfig.java for VERSION_NAME and VERSION_CODE
cat app/build/generated/source/buildConfig/debug/com/ipcam/BuildConfig.java | grep VERSION
```

### Test Workflow (GitHub)
1. Create a test PR with any change
2. Merge to main
3. Check Actions tab for "Auto Version Bump" workflow
4. Verify new commit appears with message "Auto version bump to X.X [skip ci]"
5. Verify `version.properties` was updated
6. Verify workflow did not trigger again (no infinite loop)

## Troubleshooting

### Problem: Beta label not showing on feature branch
**Solution**: Rebuild the app. The branch name is read at build time from git.

### Problem: Version not incrementing after merge
**Checks:**
1. Verify workflow ran: GitHub Actions tab
2. Check workflow logs for errors
3. Verify `version.properties` was updated
4. Check if commit message contained `[skip ci]` (would prevent workflow)

### Problem: Workflow creates infinite loop
**Prevention:**
- `[skip ci]` in commit message
- `if` condition checks for "Auto version bump"
- `paths-ignore` excludes workflow files

**Fix:** If loop occurs:
1. Disable workflow in GitHub UI
2. Fix the trigger conditions
3. Re-enable workflow

### Problem: Build fails after version.properties changes
**Solution:**
1. Verify `version.properties` format is correct
2. Check for syntax errors in file
3. Ensure VERSION_NAME and VERSION_CODE are present
4. Run `./gradlew clean` and rebuild

## Alternative Strategies Considered

### Strategy 1: Git Tags (Not Implemented)
- Use git tags (v1.2, v1.3) for versioning
- Build.gradle parses latest tag
- **Pros**: Git-native, integrates with GitHub Releases
- **Cons**: Complex tag parsing, requires tag creation workflow

### Strategy 2: CalVer / Timestamp (Not Implemented)
- Use date-based versioning (2026.01)
- **Pros**: Fully automated, no conflicts
- **Cons**: Non-semantic, less intuitive

### Current Strategy: Version File (Implemented)
- Central `version.properties` file
- **Pros**: Simple, explicit, easy to understand
- **Cons**: Requires file maintenance (automated by workflow)

## Future Enhancements

Potential improvements:
1. **Patch version support**: Support MAJOR.MINOR.PATCH format (e.g., 1.2.3)
2. **Manual workflow trigger**: Allow manual version increments from GitHub UI
3. **Release notes**: Auto-generate release notes from commit messages
4. **Git tags**: Create git tags automatically after version bump
5. **APK uploads**: Build and upload APK as GitHub Release asset
6. **Semantic commit parsing**: Increment version based on conventional commits

## Maintenance

### Changing Major Version
To bump major version (1.x → 2.0):
1. Manually edit `version.properties`: `VERSION_NAME=2.0`
2. Commit with `[skip ci]` tag
3. Next merge will increment to 2.1, 2.2, etc.

### Changing Version Code Base
To reset or change version code:
1. Edit `version.properties`: `VERSION_CODE=100`
2. Commit with `[skip ci]` tag
3. Future increments will be 101, 102, etc.

### Disabling Auto-Increment
To temporarily disable:
1. Include `[skip ci]` in merge commit messages
2. Or disable workflow in `.github/workflows/version-bump.yml`

## Summary

✅ **Automated**: Version increments on every merge to main  
✅ **No self-triggering**: Multiple safeguards prevent workflow loops  
✅ **Beta labels**: Automatic "-beta" suffix on non-main branches  
✅ **Simple**: Single source of truth in `version.properties`  
✅ **Visible**: Version displayed in app, web UI, and API  
✅ **Maintainable**: Easy to understand and modify
