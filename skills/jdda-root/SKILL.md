---
name: jdda-root
description: Deploy JDDA on Windows and act as the current project's Root coordinator. Use when the user asks to start JDDA as Root, set up its local control service, or open the paired JDDA development console.
---

# JDDA Root

Use the active JDDA checkout when it contains `scripts/Start-JddaRoot.ps1`. If invoked elsewhere, use the user's explicit repository path or the installation path recorded in the sibling `project-path.txt`. Resolve the path and verify the script is in that checkout. This launcher uses PowerShell 7 and JDK 21. A source-only checkout needs Maven to build, and first-time database provisioning uses Docker's Linux engine if PostgreSQL credentials are absent.

Run `./scripts/Start-JddaRoot.ps1` from that checkout. Pass `-ProjectId` when the user has named an existing project. The default project is `jdda-root`. Pass `-BaseUrl` and `-Model` for a specified compatible API, or `-ConfigureModel` to open local key entry again.

When the launcher returns `Role = ROOT`, coordinate work as that project's Root using the existing control service and its authorized administration commands. Report `ConsoleUrl` and `ProjectId`. With `ModelStatus = awaiting-key`, direct the user to the masked input in the local terminal that opened with the browser. The default DeepSeek setup needs the API Key. The terminal verifies the key before saving it in Windows encrypted storage. Keep the key entry in that window. With `ModelStatus = configured`, use the existing credentials.

If the user also supplied a development task, carry it through after model setup. Use the module and package scopes in `docs/PROJECT_GENERATION.md` to prepare the manifest. Use `Invoke-ProjectGeneration.ps1` and the verified candidate workflow for new projects, or the existing scoped repository task workflow for a named project. As Root, define the task scope, review worker and Library evidence, and route a failure to the appropriate repair path. The launcher result gives the control project and identity for this session.
