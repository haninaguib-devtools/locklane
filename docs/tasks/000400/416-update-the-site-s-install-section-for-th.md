# 416 — Update the site's install section for the per-user service and the uninstaller

Issue: #416

## Asked
The marketing site's install section still described the installer as it behaved before
#386 and #392: it told a visitor to start the server by hand afterwards
(`cd ~/.locklane && java -jar locklane.jar`), which is now wrong — `install.sh`
registers and starts a per-user service (a systemd user unit on Linux, a launchd agent
on macOS, falling back to a detached start elsewhere) that survives a reboot and
restarts after a crash, so the manual step would start a competing copy. The page also
said nothing about `~/.locklane/uninstall.sh`. Update `site/index.html` so the install
section matches what the installer actually does.

## Done when
- `site/index.html` no longer contains `java -jar locklane.jar` (`grep -c 'java -jar'`
  returns 0).
- The install section states that the installer starts the server and keeps it running
  across reboots and crashes, naming the per-user service in plain language, and notes
  the fallback for a host with neither service manager.
- The page mentions `~/.locklane/uninstall.sh` as the way to remove the service, and
  that it asks separately before deleting the install directory and its data.
- The existing note about Java 21+, the GitHub CLI, and `~/.locklane` still reads
  correctly alongside the new text.
- `./.t-workflow/scripts/consistency-check.sh` passes.
- Human judgment: the section reads as coherent paragraph flow, and the page still
  renders correctly.

## Explicitly not
- No change to `install.sh`, `update.sh`, or the generated `uninstall.sh` — site copy only.
- No redesign of the install section's layout or styling beyond what removing the
  "Then start it" block requires.
- No documentation changes outside `site/`.

## Decisions made along the way
- The "Then start it" block was replaced by two further `.note` paragraphs rather than a
  new component, so the section reads as one flow of prose under the command block and
  needs no new styling (Claude, 2026-08-30).
- Wording follows `install.sh`'s own closing output: "systemd user service" / "launchd
  agent", the reboot-and-crash promise, and the uninstaller's two separate questions.

## Deviations / notes
- `site/styles.css`: the `.run-command` rules became dead once the block they styled was
  removed, so they were deleted with it. Inside the issue's Scope ("`site/` assets only
  if the copy change needs them") — leaving unreferenced CSS behind would be the
  incomplete half of the same edit.
