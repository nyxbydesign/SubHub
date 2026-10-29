# Achievement palette refresh

Approved direction, 2026-10-08: the existing icons were already about 90% right. Preserve their gothic designs and primarily change the colors. Use the darker plum-purple first-block badge as the collection's canonical palette reference.

All 58 active achievements keep their individual artwork and resource names. Export separate 192 x 192 transparent lossless WebP files, including the four Whisper badges previously stored as PNG. The app renders these resources at its existing sizes and retains its locked and concealed presentation states.

## Shared prompt

Recolor image 1, the existing SubHub achievement badge. Preserve its original subject, distinctive motif, gothic silhouette, circle/rings, filigree, tier ornamentation, composition, object count, and metallic dimensional detail. Image 2 is only the canonical dark plum-purple gothic color and style reference; do not copy its shield subject or composition.

Match blackened plum-purple metal, deep aubergine recesses, dark rich violet enamel, muted plum/mauve highlights, and restrained purple gems. Replace gold, bronze, bright silver, cyan, and orange accents with purple-plum tinted metal. Main palette: near-black aubergine `#160D1D`, deep plum `#35203F`, muted purple `#583565`, plum violet `#754986`. Use narrow dusty mauve `#9B6EA8` highlights for readable edges.

Keep the overall appearance dark, moody, and gothic. Preserve tier distinctions through the existing geometry and decorations. Avoid pale silver rims, gold, orange, cyan, rainbow tiers, large bright glow, redesigning, flattening, removing ornamentation, adding new objects, and text. Keep the complete original silhouette centered with transparent padding. Deliver one standalone icon per call.

## Reference roles and verification

Each original resource is its own identity and edit target. The recolored first-block resource anchors only the palette and rendering style. The icon-specific prompt suffix supplies the corresponding achievement ID from `AchievementManager`.

`scripts/render_achievement_badge_catalog.py` validates all 58 IDs, unique output hashes, dimensions, and transparent corners, then renders the full catalog from the final app resources. Review that catalog and a 48-pixel comparison against the app's dark surface for subject preservation, palette consistency, and readability.
