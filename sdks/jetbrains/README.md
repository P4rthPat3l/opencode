# OpenCode JetBrains Plugin

JetBrains Tool Window integration for the `p4rth-opencode` fork.

## Development

```bash
gradle test
gradle buildPlugin
gradle runIde
```

The plugin uses a managed fork runtime by default. For local development, open Settings → Tools → OpenCode and set an external fork runtime path.

See `../../docs/jetbrains-plugin.md` for architecture, runtime distribution, storage isolation, bridge protocol, and release notes.
