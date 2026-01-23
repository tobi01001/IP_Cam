# APK Signing Setup for Auto Updates

This guide explains how to set up APK signing for the GitHub Actions workflow to enable automatic updates.

## Why Signing Matters

Android requires all APK updates to be signed with the **same keystore** as the original installation. If you sign an update with a different key, Android will refuse to install it. This is a critical security feature.

## One-Time Setup

### Step 1: Generate Release Keystore

If you don't already have a release keystore, create one:

```bash
keytool -genkey -v -keystore release.keystore -alias release -keyalg RSA -keysize 2048 -validity 10000
```

**Important prompts:**
- Enter keystore password (remember this!)
- Re-enter password
- Enter your name, organization, city, state, country
- Confirm information
- Enter key password (can be same as keystore password)

**⚠️ CRITICAL: Backup this keystore file and passwords securely!**
- Without this keystore, you cannot release updates
- Store in a password manager and secure backup location
- If you lose it, users will need to uninstall and reinstall the app

### Step 2: Test Your Keystore Locally

Before adding to GitHub, verify it works:

```bash
# Build release APK
./gradlew assembleRelease

# Sign it manually
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore release.keystore \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  release

# Verify signature
jarsigner -verify -verbose -certs app/build/outputs/apk/release/app-release-unsigned.apk
```

Should see: "jar verified"

### Step 3: Convert Keystore to Base64

GitHub Actions needs the keystore as a base64-encoded secret:

```bash
# On Linux/Mac
base64 release.keystore | tr -d '\n' > release.keystore.base64

# On Windows (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore")) | Out-File -Encoding ASCII release.keystore.base64
```

### Step 4: Add GitHub Secrets

Go to your repository on GitHub:

1. Click **Settings** → **Secrets and variables** → **Actions**
2. Click **New repository secret**
3. Add these four secrets:

| Secret Name | Value | Example |
|-------------|-------|---------|
| `SIGNING_KEY` | Contents of `release.keystore.base64` file | `MIIKXgIBAz...` (very long) |
| `KEY_STORE_PASSWORD` | Keystore password you entered | `MySecurePass123!` |
| `ALIAS` | Key alias (default: `release`) | `release` |
| `KEY_PASSWORD` | Key password (usually same as keystore) | `MySecurePass123!` |

**Security notes:**
- ✅ GitHub encrypts these secrets - they cannot be read by anyone
- ✅ Secrets are only exposed to workflow runs
- ⚠️ Never commit the keystore file to git
- ⚠️ Never put passwords in code or workflow files

### Step 5: Verify Workflow

The workflow (`.github/workflows/release.yml`) is already configured. It will:

1. **Trigger** on pushes to `main` branch (when PRs are merged)
2. **Build** the release APK with `./gradlew assembleRelease`
3. **Sign** the APK using your secrets
4. **Create** a GitHub Release with:
   - Tag: `v{BUILD_NUMBER}` (e.g., `v20260123140000`)
   - Name: `v{VERSION_NAME} (Build {BUILD_NUMBER})`
   - APK file attached
   - Release notes from commit message

## Testing the Workflow

### Option 1: Trigger Manually (First Time)

1. Make a small code change (e.g., add a comment)
2. Commit and push to a branch
3. Create a PR
4. Merge the PR to `main`
5. Watch the Actions tab for the workflow run

### Option 2: Test Signing Locally First

Before pushing to GitHub, test the entire signing process:

```bash
# 1. Build release APK
./gradlew clean assembleRelease

# 2. Sign with your keystore
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore release.keystore \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  release

# 3. Verify signature
jarsigner -verify app/build/outputs/apk/release/app-release-unsigned.apk

# 4. Install on device
adb install -r app/build/outputs/apk/release/app-release-unsigned.apk
```

If this works locally, it will work in GitHub Actions.

## Troubleshooting

### Error: "Keystore was tampered with, or password was incorrect"

**Cause:** Wrong `KEY_STORE_PASSWORD` or `ALIAS`

**Fix:**
1. Verify your keystore password: `keytool -list -v -keystore release.keystore`
2. Enter password - if it lists your key, password is correct
3. Check the alias name in the output
4. Update GitHub secrets with correct values

### Error: "Failed to sign APK"

**Cause:** Invalid base64 encoding or corrupted keystore

**Fix:**
1. Re-encode keystore: `base64 release.keystore | tr -d '\n' > release.keystore.base64`
2. Copy the **entire** contents (it's very long, ~5000 characters)
3. Update `SIGNING_KEY` secret in GitHub
4. Retry the workflow

### Error: "Cannot install update - signature mismatch"

**Cause:** Different keystore used for current installation vs. update

**Fix:**
1. Uninstall the app completely
2. Install the new release from GitHub
3. All future updates will work (if using the same keystore)

### Error: "BUILD_NUMBER not found"

**Cause:** BuildConfig.java not generated during build

**Fix:**
- This is normal for first build
- Workflow falls back to timestamp-based version
- Subsequent builds will use correct BUILD_NUMBER

## Best Practices

### Keystore Security

✅ **Do:**
- Store keystore in a password manager (1Password, LastPass, etc.)
- Keep encrypted backup in cloud storage (Google Drive, Dropbox)
- Document the passwords in a secure location
- Use strong, unique passwords (16+ characters)

❌ **Don't:**
- Commit keystore to git (add to `.gitignore`)
- Share keystore via email or chat
- Use simple passwords like "password" or "123456"
- Store keystore on shared drives without encryption

### Version Management

The build system automatically handles versioning:
- `BUILD_NUMBER`: Timestamp-based (e.g., `20260123140000`)
- `VERSION_NAME`: From `version.properties` (e.g., `1.2`)
- Release tag: `v{BUILD_NUMBER}`
- Release name: `v{VERSION_NAME} (Build {BUILD_NUMBER})`

Every build has a unique, monotonically increasing BUILD_NUMBER.

### Release Management

**Automatic releases:**
- Every merge to `main` creates a new release
- Users can update via in-app update feature
- Or download manually from GitHub Releases page

**Manual releases:**
- To skip auto-release, add `[skip ci]` to commit message
- Example: `git commit -m "Update docs [skip ci]"`

**Delete old releases:**
- Releases are free but take up repository storage
- Consider deleting very old releases (keep last 10-20)
- Latest release is always used for update checks

## Summary Checklist

Before enabling auto-updates, ensure:

- [ ] Generated release keystore (`release.keystore`)
- [ ] Tested signing locally with `jarsigner`
- [ ] Converted keystore to base64
- [ ] Added all 4 GitHub secrets (`SIGNING_KEY`, `KEY_STORE_PASSWORD`, `ALIAS`, `KEY_PASSWORD`)
- [ ] Backed up keystore and passwords securely
- [ ] Tested workflow by merging a PR
- [ ] Verified release was created on GitHub
- [ ] Downloaded and installed APK from release
- [ ] Tested in-app update feature

## Support

If you encounter issues:

1. Check GitHub Actions logs: Repository → Actions → Failed workflow → View logs
2. Verify all 4 secrets are added correctly
3. Test signing locally first
4. Check that keystore passwords are correct
5. Ensure base64 encoding is complete (no truncation)

For more help, see:
- [Android signing documentation](https://developer.android.com/studio/publish/app-signing)
- [GitHub Actions secrets](https://docs.github.com/en/actions/security-guides/encrypted-secrets)
- [keytool documentation](https://docs.oracle.com/javase/8/docs/technotes/tools/unix/keytool.html)
