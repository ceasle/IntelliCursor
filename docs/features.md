# IntelliCursor features

IntelliCursor puts the Cursor agent in an IntelliJ IDEA tool window. It talks to the Cursor CLI you already have installed over the Agent Client Protocol (ACP), so the agent works on your project the same way it does in a terminal.

## Signing in

- **Sign in button.** Nothing starts when the IDE opens. Click **Sign in** in the tool window to log in to Cursor through your browser.
- **Remembers your login.** If the Cursor CLI is already signed in, the panel goes straight to the chat.
- **Connection status.** The header shows whether Cursor is starting, connected, waiting for sign-in, or has failed. A banner with **Retry** explains any connection error.
- **Send while starting.** You can type and send while the CLI is still starting. The message goes out as soon as Cursor is ready.

## Chat

- **Messenger-style bubbles.** Your messages appear on the right and Cursor's replies on the left, each with a timestamp.
- **Live progress.** While a reply is in progress, a status line shows what Cursor is doing ("Thinking…", "Writing…", the tool it is running) and how long it has taken, so a slow reply never looks frozen.
- **Thinking.** Cursor's reasoning goes in a collapsible section that shows "Thought for 4s" when done.
- **Tool activity.** Each file read, search, edit, terminal command and diagnostic appears as a card with its status, the files it touched and its output.
- **Plans.** Multi-step plans show as a checklist that updates as Cursor works through it.
- **Formatted replies.** Replies render Markdown: headings, lists, links and code blocks with syntax highlighting.
- **Code block actions.** Every code block has **Copy** and **Insert at caret** buttons.
- **Copy a reply.** A copy button sits under each finished reply.
- **Stop.** The send button turns into **Stop** while Cursor is working, and stops the reply without freezing the IDE.
- **Clear outcomes.** A finished reply shows whether it was stopped, cut off at the length limit, declined, or failed, and why.
- **Starter prompts.** An empty chat offers ready-made prompts such as "Explain how this project is structured", "Find bugs in the current file" and "Write tests for the current file".

## Composer

- **Auto-growing input.** Press **Enter** to send.
- **Current file.** The file open in the editor is attached automatically. Click the eye icon to leave it out.
- **@ to add files.** Type `@` to search the project and attach files to a message. Attached files show as chips, and you can click them to open the file.
- **/ for commands.** Type `/` for a filterable command list you can drive from the keyboard.
- **Mode picker.** Switch between **Agent**, which edits files and runs commands, **Plan**, which designs an approach without editing, and **Ask**, which answers questions read-only.
- **Model picker.** Choose any model your Cursor account offers.

## Slash commands

| Command | What it does |
| --- | --- |
| `/new`, `/clear` | Start a new chat |
| `/resume` | Open recent chats and pick one to continue |
| `/summarize` | Summarize this chat and continue in a fresh one to free up context |
| `/agent`, `/plan`, `/ask` | Switch mode |
| `/model` | Choose the model |
| `/mcp` | Manage MCP servers |
| `/run-everything` | Let Cursor run every tool without asking, or turn that off |
| `/changes` | Review uncommitted changes in the diff viewer |
| `/commit` | Ask Cursor to stage and commit the current changes |
| `/copy` | Copy Cursor's last reply |
| `/copy-conversation-id` | Copy this chat's ID |
| `/help` | Show every command |

Your Cursor skills and custom commands also appear in the list, alongside the built-in ones.

## Chat history

- **Recent chats.** The history button lists earlier chats in this project with when each was last used. Type to filter them.
- **Resume.** Pick a chat to reopen it with its full transcript and keep going where you left off.

## Approvals

- **Review before it runs.** Cursor asks before editing a file or running a shell command. You can allow it once, always allow it, or deny it.
- **Run everything.** Turn on Run everything to approve all tools automatically. A chip in the composer shows it is on, and you can click it to turn it off.
- **Questions and plans.** When Cursor asks a question or proposes a plan, the answer form opens in the IDE.

## MCP servers

- **Status at a glance.** The MCP button lists every server from your `mcp.json` with a colour-coded status: ready, needs sign-in, disabled or failed.
- **Manage servers.** For each server you can show its tools, sign in, or turn it on or off. Changes restart Cursor and resume your chat, so no context is lost.
- **Edit config.** **Edit mcp.json…** opens the project's `.cursor/mcp.json`, falling back to the global one.

## Editor integration

Right-click in the editor and open the **IntelliCursor** menu:

- **Send Selection to Cursor** puts the selected code, with its file and line range, into the composer.
- **Explain Selected Code** asks Cursor to explain the selection.
- **Refactor Selected Code** asks Cursor to suggest refactorings.
- **Review Cursor Diff** opens uncommitted changes in IntelliJ's diff viewer, so you can review the agent's edits.

## Look and feel

- **Native look.** Built from IntelliJ Swing components, with full light and dark theme support.
- **Responsive.** All CLI communication runs off the UI thread, so the IDE stays responsive.
- **Distinct icon.** The IC icon appears in the tool window bar, the editor menu and the plugin list.

## Requirements

- IntelliJ IDEA 2024.2 or newer (Community or Ultimate).
- The Cursor CLI (`cursor-agent` / `agent`), installed and signed in to a Cursor account.

IntelliCursor is an independent project. It is not made or endorsed by Cursor or Anysphere.
