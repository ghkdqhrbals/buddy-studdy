# Avatar Builder Plan

## Scope

BuddyStudy uses a Reddit-style avatar builder as the default profile image system. Personal photo upload and on-the-fly image resizing remain a future extension, but the current implementation keeps the same data shape so image-backed avatar items can be added later.

## Data Model

- `avatar_categories` defines selectable slots such as `base`, `background`, `top`, `bottom`, `shoes`, `hat`, and `item`.
- `avatar_items` stores concrete assets for those slots. New hats, tops, bottoms, and shoes are added as data rows, not profile-screen branches.
- `user_avatar_items` stores user-specific unlocked items. Default items are exposed through `default_grant`.
- `users.avatar_mode` selects `BUILDER` or future modes.
- `users.avatar_config` stores the selected slot-to-item map, for example:

```json
{
  "base": "base-cat",
  "background": "background-teal",
  "top": "top-hoodie-blue",
  "bottom": "bottom-denim-pants",
  "shoes": "shoes-white-sneakers",
  "hat": "hat-beanie-navy",
  "item": "item-laptop"
}
```

## API

- `GET /api/v1/profile/avatar/catalog`
  Returns categories, currently available items, default config, and current config.
- `PATCH /api/v1/profile/avatar`
  Validates selected items against the authenticated user's available catalog and saves the selected config.
- `PATCH /api/v1/profile`
  Also accepts `avatarMode` and `avatarConfig` for profile-save flows.

## iOS Rendering

The iOS app composes avatars locally with a Reddit-style Snoo renderer:

- Background color comes from the selected background item.
- The visible character is a single neutral Snoo-like body with a consistent head, torso, arms, legs, and antenna silhouette.
- Base items such as `base-cat`, `base-fox`, `base-rabbit`, and `base-dog` are costume variants rendered as ears or head details on the same body proportions.
- Top, bottom, shoes, hat, and item selections are rendered into fixed body slots instead of overlaying completed avatar images, so clothes naturally attach to the body and cannot drift over the face.
- `AvatarBuilderVisualRegistry` lists the supported seeded catalog keys and is covered by iOS tests so DB seed rows cannot silently point at unsupported visual combinations.
- If a future DB row is delivered before the app supports a matching visual key, the renderer keeps the neutral Snoo body and simply omits that unsupported item instead of showing a misaligned asset.
- The selected config is cached with the profile cache so profile UI stays consistent across tabs and launches.

## Extension Path

If photo upload or server-rendered avatar images are added later, keep the catalog shape. Add URL/image variants to `avatar_items` or a derived style endpoint while preserving `avatar_config` as the canonical user selection. For local builder items, add the DB row and a matching Snoo-renderer key before enabling it by default.
