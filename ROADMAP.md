# World Zip — Roadmap

Tracks what is shipped and what is planned. Update this file whenever an item moves between
sections. Keep `AGENTS.md` as the index (it links here) and `.cursor/rules/worldzip.mdc` as the
zip/unzip decision record — don't duplicate details across all three.

## Shipped

- Zip / unzip a world folder from the Select World screen (zip-replace, unzip-replace).
- Safety: zip-slip, zip bomb, single-`level.dat` validation, temp-file staging.
- Select World UI: **Zip** / **Unzip** on the same footer button; **Zip All** / **Unzip All** in the
  header; zipped worlds listed with size + real `icon.png`; Play unzips then loads; Edit/Recreate off.
- Progress screen with byte bar, percent, and Cancel (mid-file abort, temp cleanup).
- Confirm before zip/unzip; extra wording above 2 GiB; toast with space saved.
- Zip All / Unzip All scan all of `saves/` (not the search filter). Failures named in the summary toast.
- Region files and other already-compressed types are stored, not re-deflated.
- Orphan `*.zip.part` / `*.unzip.part` cleanup; icon cache under `saves/.worldzip-icons/`.
- JUnit tests for `WorldArchive` (`:common:test`). French lang (`fr_fr.json`).

## Planned

Nothing queued. Not planned unless requested: server-side zip command, pick-which-worlds Zip All
screen, batch rename, drag-and-drop import.

## Decisions

See `.cursor/rules/worldzip.mdc`.
