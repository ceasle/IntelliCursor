# Contributing to IntelliCursor

Thanks for helping improve IntelliCursor. This guide covers how to get the plugin building, make a change and send it for review.

## Set up

1. Fork the repository on GitHub, then clone your fork:
   ```bash
   git clone https://github.com/<your-username>/IntelliCursor.git
   cd IntelliCursor
   ```
2. Install **JDK 21** and **Gradle 9.0+**. The repository has no Gradle wrapper, so use your local `gradle`.
3. Install the **Cursor CLI** and sign in (`agent login`). You need it to try the plugin in a sandbox IDE.
4. Check that everything builds and the tests pass:
   ```bash
   gradle test
   ```

## Create a branch

Branch from `main` and give the branch a short, descriptive name:

```bash
git checkout -b fix/permission-dialog-focus
```

## Make your change

Find the right place for the change:

| Change | Where |
| --- | --- |
| ACP messages or JSON-RPC handling | `protocol/`, `client/` |
| Launching or talking to the Cursor CLI | `transport/`, `service/CursorCli.kt` |
| Session lifecycle, connection and sign-in | `service/CursorSessionManager.kt` |
| Chat panel, composer, slash commands, popups | `ui/` |
| Editor right-click actions | `actions/` and `META-INF/plugin.xml` |
| Plugin metadata, tool window and action registration | `src/main/resources/META-INF/plugin.xml` |

Guidelines:

- **Keep each pull request focused** on one fix or feature. Don't mix refactors or formatting changes into a functional change.
- **Match the surrounding code**: Kotlin, IntelliJ Platform Swing components (`JBLabel`, `JBUI`, `JBColor`) and the existing naming and comment style.
- **Never block the UI thread.** CLI and process I/O runs in the background, and UI updates go back through `invokeLater`.
- **Don't add dependencies** unless the change really needs them. If it does, explain why in the PR.
- **Respect the Cursor CLI's behavior.** If you rely on an ACP method or CLI subcommand, check it against a real `agent acp` session, not just the spec.
- **Don't commit secrets**, personal paths or account details, such as test logins or local `JAVA_HOME` paths.

## Test and verify

1. Run the unit tests:
   ```bash
   gradle test
   ```
   Add or update tests in `src/test/kotlin/` for the code you changed. Tests use JUnit 5 and AssertJ.
2. Build the plugin:
   ```bash
   gradle buildPlugin
   ```
3. Try the change in a sandbox IDE:
   ```bash
   gradle runIde
   ```
   Open a project, open the **IntelliCursor** tool window and test the flow you changed. Check both a light and a dark theme for UI changes. If you touched startup or sign-in, also test a signed-out CLI.

## Document behavior changes

If your change adds, removes or changes something a user can see or do, such as a feature, slash command, editor action or requirement, update the matching docs in the same PR:

- `README.md` for setup, usage and requirements
- `docs/features.md` for the feature list
- the `<description>` in `plugin.xml`, if the change affects the plugin listing

## Submit a pull request

1. Commit with a clear message. The history uses [Conventional Commits](https://www.conventionalcommits.org/), for example `fix(ui): keep composer focus after sending`.
2. Push your branch to your fork, then open a pull request against `main`.
3. In the description, say what changed, why, and how you tested it. Include screenshots for UI changes.
4. Respond to review feedback by pushing more commits to the same branch.

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE).
