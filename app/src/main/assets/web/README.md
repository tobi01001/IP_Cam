# Web UI Assets

This directory contains the separated web interface files for the IP Camera server.

## File Structure

- **index.html** - Main HTML structure with template placeholders (391 lines)
  - Contains the complete page structure with 6 tabs (Live Stream, Camera Controls, Stream Settings, RTSP, Server Management, API Reference)
  - Uses `{{variable}}` syntax for dynamic content substitution
  - References external CSS and JS files for modularity

- **styles.css** - All CSS styling (97 lines)
  - Modern gradient background and card-based layout
  - Responsive design with mobile/tablet media queries
  - Material Design-inspired components (tabs, buttons, badges)
  - Fullscreen video support

- **script.js** - All JavaScript functionality (847 lines)
  - Tab switching and UI interactions
  - Server-Sent Events (SSE) for real-time updates
  - API calls to all server endpoints
  - Stream management and controls
  - Connection monitoring with auto-refresh

## Template Variables

The following template variables are replaced at runtime in `index.html`:

- `{{displayName}}` - Device name or "IP Camera Server"
- `{{versionString}}` - App version (from BuildInfo)
- `{{buildString}}` - Build information (from BuildInfo)
- `{{connectionDisplay}}` - Active/max connections (e.g., "2/32")

## How It Works

1. **Loading**: `HttpServer.loadAsset()` loads files from the assets directory
2. **Template Substitution**: `HttpServer.substituteTemplateVariables()` replaces `{{variable}}` placeholders with actual values
3. **Serving**: Files are served via Ktor routes:
   - `GET /` or `GET /index.html` → Serves the template-processed HTML
   - `GET /styles.css` → Serves the CSS file directly
   - `GET /script.js` → Serves the JavaScript file directly

## Benefits of Separation

✅ **Maintainability** - Each file focuses on a single concern (structure, style, behavior)
✅ **Readability** - No more 1300+ line embedded strings in Kotlin code
✅ **Modern Development** - Standard web development workflow (HTML/CSS/JS)
✅ **Easy Updates** - Modify UI without touching Kotlin server code
✅ **Version Control** - Better diffs and merge conflict resolution
✅ **No Build Step** - Assets are automatically bundled with APK

## Making Changes

### To update the UI design:
Edit `styles.css` - no server code changes needed

### To add/modify features:
Edit `script.js` - add new functions or modify existing ones

### To change page structure:
Edit `index.html` - modify the DOM structure

### To add new dynamic values:
1. Add template variable to `index.html`: `{{newVariable}}`
2. Update `HttpServer.serveIndexPage()` to include the new variable in the `variables` map

## Testing

After making changes:
1. Build the APK: `./gradlew assembleDebug`
2. Install on device
3. Open web UI in browser: `http://<device-ip>:8080/`
4. Verify changes work correctly across all tabs
5. Test on mobile and desktop browsers for responsive design

## Future Enhancements

With this separated structure, future improvements can include:
- Modern CSS frameworks (Tailwind, Bootstrap)
- JavaScript frameworks (Vue.js, React, Alpine.js)
- Build process with minification and bundling
- TypeScript for type-safe JavaScript
- SCSS/LESS for advanced CSS features
- Web components for reusable UI elements
