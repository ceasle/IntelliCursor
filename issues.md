# Known issues

Open issues found while preparing the 1.0.0 release. None of them blocks the plugin from working.

## Deprecated IntelliJ APIs

JetBrains' Plugin Verifier marks 1.0.0 as compatible with every IntelliJ IDEA version from 2024.2 to 2026.3 EAP. It reports 7 calls to deprecated APIs in 2026.3 EAP, 6 in 2026.1–2026.2, 5 in 2024.3–2025.3 and 4 in 2024.2. Deprecated APIs still work, but JetBrains may remove them in a later release, so they should be replaced before then.

| Deprecated API | Uses | Location |
|---|---|---|
| `JComponent.reshape(int, int, int, int)` | 4 | `ui/CursorUi.kt:311` and `ui/CursorUi.kt:320`. Both overrides call `super.reshape` to re-lay out wrapped text when the width changes. |
| `ActionUtil.invokeAction(...)` | 1 | `ui/CursorAgentPanel.kt:322`, in `runIdeAction`, which runs an IDE action from the chat panel |
| `ReadAction.run(ThrowableRunnable)` | 1 | `ui/CursorAgentPanel.kt:393`. Collects project files for the `@` file picker. |
| `FileEditorManager.openFile(VirtualFile, boolean)` | 1 | `ui/ChatPopups.kt:236`. Opens `mcp.json` from `/mcp`. |

Possible replacements, to confirm against each API's `@deprecated` note in the target platform version:

- `reshape`: override `setBounds(x, y, width, height)` instead. Swing routes all bounds changes through it.
- `ActionUtil.invokeAction`: `ActionManager.getInstance().tryToExecute(...)` or the current `ActionUtil` helper.
- `ReadAction.run`: `ReadAction.compute { ... }`, or `readAction { ... }` from a coroutine.
- `FileEditorManager.openFile(file, focus)`: `OpenFileDescriptor(project, file).navigate(true)`, or `FileEditorManager.openFile(file)`.

The full results are on the version page in Marketplace: **Versions → 1.0.0 → Compatibility verification**.

## Marketplace description can't be edited while 1.0.0 is under review

The Marketplace description comes from `plugin.xml` ("Use data from plugin.xml"). Edits made on the website reverted after saving, so the Marketplace page still shows the 1.0.0 description without the Getting started section and the GitHub link. The current `plugin.xml` already has both. The next version upload will update the Marketplace description.

## Build

- The repository has no Gradle wrapper. Contributors need a local Gradle 9.0+ installation, because IntelliJ Platform Gradle Plugin 2.19.0 requires Gradle 9. Adding a wrapper (`gradle wrapper`) would pin the version.
- Gradle prints "Deprecated Gradle features were used in this build, making it incompatible with Gradle 10". Run `gradle buildPlugin --warning-mode all` to see which features.

## Plugin signing

1.0.0 was uploaded unsigned. JetBrains signs plugins after approval. To sign later releases yourself, generate a key and certificate. Then set `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD`, and add a `signing { }` block to `intellijPlatform` in `build.gradle.kts`.
