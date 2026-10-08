# SubHub social feature collages

Ready to share as files; nothing has been posted to a social account.

| Format | PNG | Editable SVG |
|---|---|---|
| Square, 1080 × 1080 | [Download](subhub-social-square.png) | [Source](subhub-social-square.svg) |
| Portrait, 1080 × 1350 | [Download](subhub-social-portrait.png) | [Source](subhub-social-portrait.svg) |
| Desktop wide, 1920 × 1080 | [Download](subhub-social-wide.png) | [Source](subhub-social-wide.svg) |

The square and portrait compositions show Censor, Tribute in the center, and Subliminal Messages on the right. The desktop version uses five screens: Censor, Limits, Tribute, Subliminal Messages, and Pack Maker, with Tribute still centered.

All use genuine screenshots from the [current UI gallery](../screenshots/ui-map/README.md). Screens are proportionally scaled inside phone frames, not reconstructed or generated. UI text and controls remain part of the original captures. The logo comes from the app's existing vector artwork; the headline follows the README's branding.

## Regenerate

Requires Node.js and `sharp`. From the repository root, with `sharp` available to Node:

```sh
node scripts/render_social_collage.cjs
```

Alternatively, pass `--sharp-module /absolute/path/to/sharp`. Optional `--screenshots` and `--output` directories let you render into a separate working directory without replacing these assets. The SVG sources embed their screenshots and remain portable without external image links.

No live-detector performance, unbreakable enforcement, or successful payment claim is implied by these UI compositions.
