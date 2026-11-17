# Security policy

## Supported versions

Only the latest release gets security fixes. The IDE updates the plugin from the JetBrains Marketplace, and the command-line tools are published at the same version, so a fix ships as a new release rather than a backport.

| What | Where | Supported |
| --- | --- | --- |
| IDE plugin `com.ibrahimdans.i18n` | [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/30971-i18n-support-plus) | latest release |
| `i18n-check` command line | [GitHub releases](https://github.com/IBRAHIMDANS/i18nSupportPlus/releases/latest), npm `i18n-support-plus` | latest release |
| GitHub Action | `IBRAHIMDANS/i18nSupportPlus` (`action.yml`) | latest release |
| Docker image | `ghcr.io/ibrahimdans/i18n-check` | latest release |

## Reporting a vulnerability

**Do not open a public issue.** Report it privately through GitHub: [Report a vulnerability](https://github.com/IBRAHIMDANS/i18nSupportPlus/security/advisories/new) (the *Security* tab of the repository).

Please include:

- the affected component and version (plugin, `i18n-check`, Action or Docker image; IDE name and build for the plugin);
- what an attacker can do, and what they need first (a project to open, a translation file, an MCP client, network access…);
- the steps or a minimal project that reproduce it.

What happens next:

- an acknowledgement within **7 days**;
- an assessment, and a fix or a decision, within **30 days** for a confirmed issue; you are kept informed in the advisory;
- the fix ships in a release, then the advisory is published, crediting you unless you prefer otherwise.

This is a project maintained on spare time: there is no bug bounty.

## Scope

In scope, among others:

- code execution, file read or write outside the project, triggered by opening a project or a translation file;
- the MCP tools the plugin exposes to AI agents writing outside the translation files of the project, or without the IDE's confirmation where the IDE asks for one;
- a machine-translation API key leaving the IDE's password safe, or sent to another host than the engine it belongs to;
- texts sent to a translation engine the user did not configure;
- the GitHub Action, the Docker image or the npm package running code or reading secrets they should not.

Out of scope:

- vulnerabilities of the IDE, of the IntelliJ Platform or of the IDE's MCP server: report them to JetBrains at `security@jetbrains.com`;
- texts sent to a translation engine or an AI agent the user configured: this is the feature, see [Machine translation and AI agents](docs/translation.md);
- findings of automated scanners without a way to exploit them.
