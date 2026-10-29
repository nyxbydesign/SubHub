# Achievement icon system

The catalog contains 57 Sub badges and 17 Dom badges. Every active achievement has its own illustrated resource; the retained `marathon` and `mega_marathon` resources use the same visual system. All 76 assets are separate 192 x 192 transparent lossless WebP files under `app/src/main/res/drawable-nodpi/achievement_badge_*.webp`.

## Shape and rendering

Use bold geometric subjects with large negative spaces and a restrained enamel bevel. The subject defines the silhouette: doorways for sessions, time instruments for accumulated time, connected calendar leaves for streaks, books for text filters, barriers and architecture for censoring, and expanding sound ribbons for Whispers. Dom roster badges use groups and branching networks; combined time, sessions and payments use instruments, archives and treasury forms. Tier progression changes composition, scale and structure rather than only color or ornament.

The shared palette is near-black aubergine `#160D1D`, deep plum `#35203F`, violet `#754986` and dusty mauve `#B68DC6` highlight faces. Broad light faces and dark recesses must remain distinct when the app renders locked badges in grayscale. Keep the top-left lighting, crisp alpha edges, generous transparent padding and optical scale consistent. Avoid generic circular medallions, repeated ornamental frames, filigree, tiny jewelry and text inside the art.

The first-block icon is the style and palette anchor. It is a shield crossed by a censor bar; siblings share its rendering without copying that subject. Deliver each subject separately. Evaluate the full set at 192 pixels and at the 48-pixel presentation size, in color and in the locked grayscale treatment.

`scripts/render_achievement_badge_catalog.py` checks active ID coverage, unique hashes, dimensions and transparent corners, then renders the catalog from app resources. The app retains separate earned, locked and concealed states.

## Dom goals and earned history

Dom goals reflect aggregation across local and paired Subs. Roster milestones require 2, 5, 10 and 25 Subs; combined session time requires 100, 500, 2,500 and 10,000 hours; sessions require 250 and 2,500. Paid milestones require 100, 1,000, 10,000 and 100,000 in one currency. Current Hardcore milestones require 2, 5 and 10 Subs with fresh confirmed status. EUR and USD are never added together.

Existing earned badges remain earned when goals rise. Their original unlock and derived historical date receipts remain intact. A one-time goal receipt records the earlier requirement; the catalog marks those badges as earned earlier and the detail view states the earlier goal. Current progress continues to show observed totals against the current goal. Future unlocks and historical eligibility use the raised goals. Lifetime facts without a supported event date remain undated.
