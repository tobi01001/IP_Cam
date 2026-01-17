# Auto-Update Architecture Diagram

## Recommended Solution: GitHub Releases + In-App Update Client

```
┌─────────────────────────────────────────────────────────────────────┐
│                     DEVELOPER WORKFLOW                               │
└─────────────────────────────────────────────────────────────────────┘

    Developer commits code changes to main branch
                    ↓
    ┌───────────────────────────────────────┐
    │       GitHub Actions Triggered        │
    │   (on push to main, code changes)     │
    └───────────────────────────────────────┘
                    ↓
    ┌───────────────────────────────────────┐
    │      Build Release APK                │
    │   - Compile with release config       │
    │   - Extract version (BUILD_NUMBER)    │
    │   - Sign with release keystore        │
    └───────────────────────────────────────┘
                    ↓
    ┌───────────────────────────────────────┐
    │      Create GitHub Release            │
    │   - Tag: v{BUILD_NUMBER}              │
    │   - Name: Build {BUILD_NUMBER}        │
    │   - Attach signed APK as asset        │
    │   - Auto-generate release notes       │
    └───────────────────────────────────────┘
                    ↓
    ┌───────────────────────────────────────┐
    │    Release Published on GitHub        │
    │   - APK hosted on GitHub CDN          │
    │   - Accessible via API                │
    │   - Free, reliable, global CDN        │
    └───────────────────────────────────────┘


┌─────────────────────────────────────────────────────────────────────┐
│                     DEVICE UPDATE FLOW                               │
└─────────────────────────────────────────────────────────────────────┘

    CameraService running on device
                    ↓
    ┌───────────────────────────────────────┐
    │   Periodic Update Check Timer         │
    │   (every 6 hours, configurable)       │
    └───────────────────────────────────────┘
                    ↓
    ┌───────────────────────────────────────┐
    │    Pre-Update Checks                  │
    │   ✓ Auto-update enabled?              │
    │   ✓ WiFi connected? (if WiFi-only)    │
    │   ✓ Battery > threshold?              │
    │   ✓ No active streams? (optional)     │
    └───────────────────────────────────────┘
                    ↓ (all checks pass)
    ┌───────────────────────────────────────┐
    │   Call GitHub API                     │
    │   GET /repos/tobi01001/IP_Cam/        │
    │       releases/latest                 │
    │   - Fetch latest release info         │
    │   - Extract version code from tag     │
    └───────────────────────────────────────┘
                    ↓
    ┌───────────────────────────────────────┐
    │   Compare Versions                    │
    │   Latest > Current?                   │
    └───────────────────────────────────────┘
                    ↓ (yes, update available)
    ┌───────────────────────────────────────┐
    │   Show Notification (optional)        │
    │   "New update available"              │
    │   - Wait for user confirmation        │
    │   - Or proceed automatically          │
    └───────────────────────────────────────┘
                    ↓
    ┌───────────────────────────────────────┐
    │   Download APK                        │
    │   - From GitHub release asset URL     │
    │   - Show progress notification        │
    │   - Save to app-private storage       │
    └───────────────────────────────────────┘
                    ↓
    ┌───────────────────────────────────────┐
    │   Verify APK (optional)               │
    │   - Calculate SHA256 checksum         │
    │   - Compare with expected value       │
    │   - Android verifies signature        │
    └───────────────────────────────────────┘
                    ↓ (verification passed)
    ┌───────────────────────────────────────┐
    │   Trigger Installation                │
    │   - Use Android's PackageInstaller    │
    │   - Via FileProvider Intent           │
    │   - User must confirm (Android req)   │
    └───────────────────────────────────────┘
                    ↓
    ┌───────────────────────────────────────┐
    │   User Confirms Installation          │
    │   - Android validates signature       │
    │   - Installs update                   │
    │   - App restarts with new version     │
    └───────────────────────────────────────┘


┌─────────────────────────────────────────────────────────────────────┐
│                     ERROR HANDLING                                   │
└─────────────────────────────────────────────────────────────────────┘

    Network Unavailable → Skip check, retry next cycle
           ↓
    GitHub API Rate Limit → Exponential backoff
           ↓
    Download Failed → Retry with backoff (max 3 attempts)
           ↓
    Checksum Mismatch → Delete file, alert user, log error
           ↓
    Installation Failed → Keep current version, log error
           ↓
    User Cancels → Keep current version, remind later


┌─────────────────────────────────────────────────────────────────────┐
│                     KEY COMPONENTS                                   │
└─────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────┐
│ GitHub Actions Workflow        │
│ .github/workflows/release.yml  │
├────────────────────────────────┤
│ - Trigger: push to main        │
│ - Build release APK            │
│ - Sign with keystore           │
│ - Create GitHub release        │
│ - Attach APK as asset          │
└────────────────────────────────┘

┌────────────────────────────────┐
│ UpdateManager Service          │
│ app/.../UpdateManager.kt       │
├────────────────────────────────┤
│ - Check GitHub API             │
│ - Compare versions             │
│ - Download APK                 │
│ - Verify checksum              │
│ - Trigger installation         │
└────────────────────────────────┘

┌────────────────────────────────┐
│ CameraService Integration      │
│ app/.../CameraService.kt       │
├────────────────────────────────┤
│ - Schedule update checks       │
│ - Run pre-update checks        │
│ - Delegate to UpdateManager    │
│ - Handle notifications         │
└────────────────────────────────┘

┌────────────────────────────────┐
│ Update Settings UI             │
│ MainActivity / Settings        │
├────────────────────────────────┤
│ - Auto-update on/off           │
│ - WiFi-only toggle             │
│ - Battery threshold            │
│ - Update during streaming      │
│ - Manual check button          │
└────────────────────────────────┘

┌────────────────────────────────┐
│ Permissions & Provider         │
│ AndroidManifest.xml            │
├────────────────────────────────┤
│ - REQUEST_INSTALL_PACKAGES     │
│ - FileProvider configuration   │
│ - file_paths.xml               │
└────────────────────────────────┘


┌─────────────────────────────────────────────────────────────────────┐
│                     BENEFITS SUMMARY                                 │
└─────────────────────────────────────────────────────────────────────┘

    ✅ COST: $0 (completely free)
    ✅ INFRASTRUCTURE: None needed (uses GitHub)
    ✅ RELIABILITY: GitHub's 99.9% uptime + global CDN
    ✅ SIMPLICITY: Straightforward implementation
    ✅ EFFORT: 2-3 days of development
    ✅ COMPATIBILITY: Works on all Android devices
    ✅ SECURITY: APK signature verification, HTTPS
    ✅ TRANSPARENCY: Users can view release history
    ✅ FLEXIBILITY: Fully configurable update policies
    ✅ SPEED: Instant distribution (no approval)
    ✅ INTEGRATION: Tied directly to source code


┌─────────────────────────────────────────────────────────────────────┐
│                     COMPARISON WITH ALTERNATIVES                     │
└─────────────────────────────────────────────────────────────────────┘

    Approach              Cost      Effort    Complexity   Best For
    ──────────────────────────────────────────────────────────────────
    GitHub Releases       FREE      2-3d      ⭐⭐⭐⭐    Everyone ✓
    Play Store            $25       2-3d      ⭐⭐⭐      Play devices
    F-Droid Self-Host     $0-5/mo   4-5d      ⭐⭐       Privacy focus
    Self-Hosted Server    $5/mo     3-4d      ⭐⭐⭐      Max control
    Device Admin          $0-50/mo  5-7d      ⭐         Enterprise


┌─────────────────────────────────────────────────────────────────────┐
│                     IMPLEMENTATION TIMELINE                          │
└─────────────────────────────────────────────────────────────────────┘

    Day 1-2: Core Update Infrastructure
    ├── Create UpdateManager service
    ├── Implement GitHub API client
    ├── Add update check logic to CameraService
    ├── Implement APK download with progress
    └── Add SHA256 verification (optional)

    Day 2: GitHub Actions Automation
    ├── Create release workflow file
    ├── Configure release signing
    ├── Set up automatic tagging
    └── Add release notes generation

    Day 3: User Interface
    ├── Add update preferences UI
    ├── Add update notifications
    ├── Implement installation flow
    └── Add manual "Check for Updates" button

    Day 3-4: Testing & Documentation
    ├── Test on multiple devices
    ├── Test network failure scenarios
    ├── Test partial download recovery
    ├── Document user setup
    └── Create troubleshooting guide

    TOTAL: 2-3 days of focused development


┌─────────────────────────────────────────────────────────────────────┐
│                     NEXT STEPS                                       │
└─────────────────────────────────────────────────────────────────────┘

    1. Generate release signing keystore
       keytool -genkey -v -keystore release.keystore ...

    2. Store keystore in GitHub Secrets
       - SIGNING_KEY (base64 encoded)
       - KEY_STORE_PASSWORD
       - ALIAS
       - KEY_PASSWORD

    3. Implement UpdateManager service
       - GitHub API client
       - Download logic
       - Installation trigger

    4. Create GitHub Actions workflow
       - Build on main commits
       - Sign APK
       - Create release

    5. Add update preferences UI
       - Settings activity
       - Update configuration

    6. Test thoroughly
       - Multiple devices
       - Network scenarios
       - Failure handling

    7. Document for users
       - Setup instructions
       - Troubleshooting guide
```

---

**Document**: Auto-Update Architecture Diagram  
**Date**: 2026-01-17  
**Recommendation**: GitHub Releases + In-App Update Client  
**Effort**: 2-3 days | **Cost**: $0 | **Complexity**: Low
