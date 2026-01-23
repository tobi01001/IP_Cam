# Quick Reference: Version Management

## What Was Implemented

✅ **Automated version bump** on merge to main (1.2 → 1.3)  
✅ **Beta labels** for non-main branches (v1.2-beta vs v1.2)  
✅ **No self-triggering** workflows (multiple safeguards)  
✅ **Single source of truth** (version.properties file)

## Key Files

| File | Purpose |
|------|---------|
| `version.properties` | Stores VERSION_NAME and VERSION_CODE |
| `app/build.gradle` | Reads version from properties file |
| `app/src/main/java/com/ipcam/BuildInfo.kt` | Adds beta label for non-main branches |
| `.github/workflows/version-bump.yml` | Auto-increments version on merge |

## How to Use

### Normal Development
1. Create feature branch: `git checkout -b feature/my-feature`
2. Make changes and build - shows **v1.2-beta**
3. Create PR and merge to main
4. **Workflow automatically bumps version to 1.3**
5. Next build from main shows **v1.3** (no beta)

### Manual Version Change
Edit `version.properties`:
```properties
VERSION_NAME=2.0
VERSION_CODE=10
```
Commit with `[skip ci]` to prevent auto-increment.

### Disable Auto-Increment
Include `[skip ci]` in commit message or disable workflow in `.github/workflows/version-bump.yml`

## Version Display

| Location | Example |
|----------|---------|
| Feature branch | `v1.2-beta (copilot/automate-version-management@abc123)` |
| Main branch | `v1.2 (main@abc123)` |
| Android App | Bottom footer in MainActivity |
| Web UI | Footer section |
| HTTP API | `/status` endpoint JSON |

## Workflow Safety

The workflow won't trigger infinite loops because:
- `[skip ci]` tag in commit messages
- Checks for "Auto version bump" in message
- Ignores changes to workflow files
- Conditional job execution

## Testing

✅ Build tested: `./gradlew assembleDebug` - Success  
✅ Version reads from properties file correctly  
✅ Beta label logic validated  
✅ Workflow YAML syntax validated  
✅ Version increment logic tested (1.2 → 1.3)

## Documentation

- `AUTOMATED_VERSION_MANAGEMENT.md` - Full documentation
- `IMPLEMENTATION_SUMMARY_VERSION_AUTOMATION.md` - Implementation summary
- `VERSION_SYSTEM.md` - Original version system docs

## Ready to Merge

This PR is ready to merge. The workflow will automatically activate after the first merge to main.

## Questions?

If you have questions or need modifications, please comment on the PR.
