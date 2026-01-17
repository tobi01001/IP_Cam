# Auto-Update Quick Reference

## TL;DR - What Should I Do?

**Use GitHub Releases + In-App Update Client**

- **Cost**: $0 (completely free)
- **Effort**: 2-3 days of development
- **Infrastructure**: None needed (uses GitHub)
- **Works on**: All Android devices

---

## Quick Decision Matrix

| If you want... | Use this approach |
|----------------|-------------------|
| **Zero cost, simple setup** | **GitHub Releases** ⭐ RECOMMENDED |
| Google ecosystem integration | Play Store Internal Track |
| Privacy-first, no tracking | F-Droid Self-Hosted |
| Maximum customization | Self-Hosted Update Server |
| True silent updates | Device Administration (enterprise only) |

---

## GitHub Releases Approach (Recommended)

### How It Works

1. **GitHub Actions** automatically builds APK on every commit to main
2. **Creates release** and attaches APK as asset
3. **IP_Cam app** checks GitHub API every 6 hours
4. **Downloads APK** if newer version available
5. **Prompts user** to install (Android requirement)

### What You Need

- GitHub repository (already have it ✓)
- Release signing keystore (generate once)
- Add GitHub Actions workflow file
- Implement `UpdateManager` in app
- Add update preferences UI

### Implementation Timeline

- **Day 1-2**: Core update infrastructure
- **Day 2**: GitHub Actions automation
- **Day 3**: User interface
- **Day 3-4**: Testing & documentation

**Total: 2-3 days**

### Code Changes Required

1. **GitHub Actions workflow** (`.github/workflows/release.yml`)
2. **UpdateManager service** (`app/src/main/java/com/ipcam/UpdateManager.kt`)
3. **Update preferences** (add to existing settings)
4. **FileProvider** configuration (for APK installation)
5. **Permissions** (`REQUEST_INSTALL_PACKAGES`)

### Key Benefits

✅ **Free** - no hosting costs  
✅ **Reliable** - GitHub's 99.9% uptime  
✅ **Simple** - straightforward implementation  
✅ **Fast** - instant distribution (no approval)  
✅ **Integrated** - tied to development workflow  
✅ **Flexible** - works everywhere  

### Limitations

⚠️ User must confirm installation (Android requirement)  
⚠️ Requires internet to reach GitHub  
⚠️ Rate limits (60/hour unauthenticated, 5000/hour with token)  

---

## Alternative Approaches (Brief)

### Play Store Internal Track
- **Cost**: $25 one-time
- **Effort**: 2-3 days
- **Pros**: Industry standard, silent updates
- **Cons**: Requires Play Services, Google account per device, tester limit

### F-Droid Self-Hosted
- **Cost**: Free or $5/month VPS
- **Effort**: 4-5 days
- **Pros**: Privacy-friendly, full control
- **Cons**: Requires server, F-Droid client app, complex setup

### Self-Hosted Update Server
- **Cost**: $5/month or free
- **Effort**: 3-4 days
- **Pros**: Maximum control, custom logic
- **Cons**: Custom code to maintain, security responsibility

### Device Administration
- **Cost**: Free (or $50/month if using MDM)
- **Effort**: 5-7 days
- **Pros**: True silent updates, enterprise-grade
- **Cons**: Factory reset required, very complex, overkill

---

## Next Steps

1. **Read the full evaluation**: See `AUTO_UPDATE_EVALUATION.md`
2. **Choose approach**: GitHub Releases is recommended
3. **Generate signing keystore**: `keytool -genkey -v -keystore release.keystore ...`
4. **Set up GitHub Secrets**: Store keystore and passwords
5. **Implement UpdateManager**: Check GitHub API, download, install
6. **Add GitHub Actions**: Automate APK building and release
7. **Test thoroughly**: Multiple devices, network failures
8. **Document for users**: How to enable auto-updates

---

## Sample GitHub Actions Workflow

```yaml
name: Release Build

on:
  push:
    branches: [main]
    paths:
      - 'app/src/**'
      - 'app/build.gradle'

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
      - name: Build Release APK
        run: ./gradlew assembleRelease
      - name: Sign APK
        uses: r0adkll/sign-android-release@v1
        with:
          releaseDirectory: app/build/outputs/apk/release
          signingKeyBase64: ${{ secrets.SIGNING_KEY }}
          alias: ${{ secrets.ALIAS }}
          keyStorePassword: ${{ secrets.KEY_STORE_PASSWORD }}
          keyPassword: ${{ secrets.KEY_PASSWORD }}
      - name: Create Release
        uses: softprops/action-gh-release@v1
        with:
          tag_name: v${{ github.run_number }}
          files: app/build/outputs/apk/release/app-release.apk
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

---

## FAQ

### Will this work without Play Store?
**Yes!** GitHub Releases works on any Android device, even without Play Services.

### Do I need a server?
**No!** GitHub provides free hosting and CDN for releases.

### Can users disable auto-updates?
**Yes!** Add settings for auto-update on/off, WiFi-only, battery threshold, etc.

### What if update fails?
Updates have retry logic and fail gracefully. The app continues using the current version.

### Is it secure?
Yes! Android verifies APK signatures automatically. Optional SHA256 checksums add extra security.

### How often does it check for updates?
Configurable - default is every 6 hours. Can be more or less frequent.

### Will it interrupt streaming?
**No!** You can configure it to only update when not streaming.

---

## Resources

- **Full Evaluation**: `AUTO_UPDATE_EVALUATION.md`
- **GitHub Actions**: https://docs.github.com/en/actions
- **Android FileProvider**: https://developer.android.com/reference/androidx/core/content/FileProvider
- **Signing Action**: https://github.com/r0adkll/sign-android-release
- **Release Action**: https://github.com/softprops/action-gh-release

---

**Created**: 2026-01-17  
**For**: IP_Cam Auto-Update Implementation  
**Recommendation**: GitHub Releases (free, simple, reliable)
