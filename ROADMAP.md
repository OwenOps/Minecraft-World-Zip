# World Zip — Roadmap

Tracks what is shipped and what is planned. Update this file whenever an item moves between
sections. Keep `AGENTS.md` as the index (it links here) and `.cursor/rules/worldzip.mdc` as the
zip/unzip decision record — don't duplicate details across all three.

## Shipped

- Zip / unzip a world folder from the Select World screen (zip-replace, unzip-replace).
- Safety: zip-slip (path containment + string checks), zip bomb (entry count / uncompressed size /
  compression ratio caps), single-`level.dat` validation, temp-file staging so a failed op never
  destroys the original.
- Select World UI: **Zip** button (between Delete and Recreate), zipped `.zip` worlds listed with a
  "Zipped" tag, Play unzips in place, Edit/Recreate disabled for zipped worlds.
- Tooltips on the disabled Zip button (why it's blocked) and on Edit/Recreate when the selection is a
  zipped world.
- Single-pass zip scan (`WorldArchive.peekWithLevelDat`): validating the layout and reading
  `level.dat` no longer opens the zip's central directory twice.
- Orphaned `*.zip.part` / `*.unzip.part` cleanup when the world list loads.
- Cancel button on the zip/unzip progress screen (`WorldZipProgressScreen`); cancellation aborts
  mid-file and cleans up the temp file/folder like any other failure.
- Confirmation dialog before zipping a single world (vanilla `ConfirmScreen` pattern).
- **Zip All**: header button next to the search box, confirms once, zips every eligible world
  sequentially with progress, skips ineligible ones, toasts a zipped/skipped summary.

## Planned

| # | Item | Why | Touches |
| - | --- | --- | --- |
| 1 | Real icon for zipped worlds | `ZippedWorldList` currently points at a path that intentionally doesn't exist, so the list always shows the placeholder icon | `WorldArchive`, `ZippedWorldList` |
| 2 | JUnit tests for `WorldArchive` | No automated coverage yet (a manual round-trip script caught the `.zip.part` validation bug this session); `WorldArchive` has zero Minecraft dependencies so it's easy to unit test standalone | new test source set + Gradle wiring |

Not planned unless requested: server-side/dedicated-server zip command (mod is 100% client-side
today — see `WorldZip.init()`), batch rename, drag-and-drop import.

## Decisions

### Zip All (item 7)

- **Where:** a button in the header, next to the search box — not the footer, since it's a global
  action (not tied to the selected entry) like Create, but the footer grid is already full.
- **Eligible worlds:** same rule as the per-world Zip button (`WorldZipSelectWorld.canZip`): real
  folder with `level.dat`, not locked, no existing `name.zip`. Already-zipped and invalid entries
  are silently skipped.
- **Flow:** confirm dialog (vanilla `ConfirmScreen` style, listing how many worlds will be zipped) →
  sequential background zip with progress ("Zipping 2 of 5: <name>") → summary toast at the end
  (zipped N, skipped M).
- **Cancel:** stops after the current world finishes (or is cleanly aborted, see below); worlds
  already zipped earlier in the batch stay zipped.

### Cancel (item 4/5)

- Cancellation is immediate, not "finish current file then stop": the copy loop checks a
  cancellation flag periodically and aborts mid-file. This is safe because zip/unzip already write
  to a temp path first — an aborted temp file/folder is just deleted, exactly like any other failure
  path already does.
- Applies to a single zip/unzip and to each step of Zip All.

## Open / not decided yet

- Whether Zip All needs its own "select which worlds" screen eventually, or whether "all eligible"
  is enough — shipped with "all eligible", revisit if it turns out too coarse.
