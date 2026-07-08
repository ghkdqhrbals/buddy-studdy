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

The iOS app composes avatars locally:

- Background color comes from the selected background item.
- Base uses existing bundled profile avatar images.
- Seeded outfit/accessory slots use bundled transparent PNG layers whose asset names match `avatar_items.asset_name`.
- `AvatarBuilderAssetRegistry` lists local image-backed item assets and is covered by iOS tests so DB seed rows cannot silently reference missing app assets.
- If a future DB row is delivered before the app bundles a matching image, the renderer falls back to lightweight SwiftUI layers based on slot, `assetName`, and color.
- The selected config is cached with the profile cache so profile UI stays consistent across tabs and launches.

## Extension Path

If photo upload or server-rendered avatar images are added later, keep the catalog shape. Add URL/image variants to `avatar_items` or a derived style endpoint while preserving `avatar_config` as the canonical user selection. For local builder items, add the DB row and a matching `Assets.xcassets/<asset_name>.imageset` before enabling it by default.
