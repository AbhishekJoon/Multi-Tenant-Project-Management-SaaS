# Demo and screenshot workflow

Run the isolated stack and `npm run test:e2e` as documented in the root README. The main real-browser journey creates a synthetic team, private project, member, assigned task, completed board card and comment; then captures `workspace-desktop.png` and `workspace-mobile.png` here. Screenshots contain test-only data and are safe to include in a portfolio. Do not capture real customer workspaces or email tokens.

Manual walkthrough: register → open local Mailpit and verify email → create organization → invite a second account → accept using the emailed link → create a private project → verify the second account cannot open it → add the account as Contributor → create/assign a task → drag it to Done → reload → add a comment → inspect notifications, activity, analytics and security audit → create a second organization and verify isolation.

No shared demo password is committed. Use accounts created in your own local environment. Test traces under `frontend/test-results` are ignored because they can contain session data.
