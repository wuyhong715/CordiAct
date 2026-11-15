# Floating workbench / progressive viewport edges

Approved 2026-11-15. Warm white content, quiet purple navigation, compact workspace tree.

## Rendering
- Haze 1.6.10 (https://chrisbanes.github.io/haze/1.6.10/usage/) provides maintained Compose background sampling and progressive blur. Never blur foreground text.
- ExperienceFrame owns the systemBars/IME union once. Source is only the pager; chrome and fade layers are siblings and cannot recapture themselves. Measured chrome dimensions become scroll content padding, not viewport clipping.
- Settings uses the same source/sibling arrangement; both columns retain scrollable first/last padding.
- FrostedSurface captures only the backing. An API 33 AGSL shader offsets the backing by at most 1px near edges; input/cursor/buttons stay outside its graphics layer. API31/32 retains blur without refraction; older devices use Haze's opaque readability fallback. This fallback is not claimed as equivalent blur.
- Preview stays inset within chrome because embedded WebViews/surfaces are not guaranteed to participate in Compose layer capture. Chat and trace scroll behind chrome.
- Progressive edge overlays have no pointer input. Haze automatic input downsampling and performance-oriented progressive blur limit cost.

## Verification
Reproduce Kotlin compilation: `sh /workspace/spike/local_compile.sh --console=plain` (no APK).
CI: Android build workflow + repository CI + UI quality workflow (component tests/package).
Minimal existing component tests cover union IME displacement, top-bar stability, full-height content underneath composer, and 48dp tab rail.

Device acceptance still requires checking API33+ visual sampling/refraction, long scrolling during streaming, keyboard open/close, last-item reachability, dark mode, large text and reduced motion. Compilation and screenshot checks alone do not establish frame rate or interactive behavior.
