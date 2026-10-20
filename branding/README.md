# Roomote identity

The original R silhouette combines the initial of Roomote with a circular remote control. The small terracotta center remains recognizable when the mark is reduced to launcher size. The themed icon uses the same silhouette and counter in one color.

## Assets

| File | Purpose |
| --- | --- |
| `roomote-mark.svg` | Navy mark on transparent background |
| `roomote-mark-light.svg` | Ivory mark on transparent background |
| `roomote-wordmark.svg` | Horizontal Roomote logo |
| `roomote-icon.svg` | Square icon master; no baked-in corner mask |
| `roomote-icon-512.png` | 512 × 512 Play store icon, RGBA PNG |
| `roomote-preview-1024.png` | 1024 × 1024 identity preview |
| `roomote-feature-1024x500.png` | 1024 × 500 Play feature graphic, opaque RGB PNG |

The wordmark and feature graphic use the device's Arial/Helvetica sans-serif font. The R symbol itself consists entirely of original vector geometry and contains no font or image dependency.

Android resources include the complete legacy drawable, API 26 adaptive layers, and API 33 monochrome layer. The adaptive foreground remains inside the central safe region of its 108 dp viewport so the mark survives circular and other launcher masks.

Palette: navy `#1F2B36`, terracotta `#A64B2E`, ivory `#F8F7F2`, peach `#FAE7DA`.

## Regenerate

Run `node branding/generate-branding.mjs` with the `sharp` Node package available. If it is installed outside this project, set `ROOMOTE_NODE_MODULES` to its containing `node_modules` directory. This script regenerates both the SVG/PNG deliverables and the Android vector/adaptive icon resources.
