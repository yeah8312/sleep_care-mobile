# Stitch Export: Onboarding

Project title: `Onboarding`
Project ID: `16218346004387712907`

This folder contains:

- `download.sh`: a ready-to-run `curl -L` export script for the 10 requested screens.
- `screens/manifest.json`: screen IDs, titles, download URLs, and dimensions.
- `design-system/nocturne-scholar.md`: exported design system spec text.
- `design-system/theme.json`: exported design system theme tokens.
- `screens/*.png` and `screens/*.html`: downloaded screenshots and exported HTML for the 10 requested screens.

Download status:

- A direct `curl -L` export succeeded in this session on `2026-04-04`.
- The `screens/` folder now contains the downloaded PNG and HTML files for the 10 requested screens.
- Screen `02-home-dashboard.*` was refreshed on `2026-04-13` to match the updated Stitch screen `cc52df3ed3764e578eadf6ba6ab03638` (`Home Dashboard with Study Timer`).

Design system note:

- Stitch exposed the design system as structured theme data for asset `assets/3b70cc54ba9d4901bcaf5e1efd67afc3`.
- It did not expose a separate screenshot or HTML bundle for the design-system instance ID `asset-stub-assets-3b70cc54ba9d4901bcaf5e1efd67afc3-1775054917951`, so this export stores the design tokens and markdown spec locally instead.
