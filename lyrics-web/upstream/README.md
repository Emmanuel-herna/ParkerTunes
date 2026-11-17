# Upstream AM Lyrics lockfile

The original AM Lyrics 1.9.1 source package supplied a Bun v0 binary lockfile (`bun.lockb`). It belongs to the upstream package manifest, which includes the library's full development toolchain.

ParkerTunes currently builds the component with the smaller, pinned manifest in `../package.json` and npm in GitHub Actions. That manifest is intentionally different from the upstream one, so the upstream lockfile must not be placed beside it or used with `bun install --frozen-lockfile` unless the matching upstream manifest and build setup are restored.

Place the supplied original binary at:

```
lyrics-web/upstream/bun.lockb
```

This keeps the exact upstream lockfile in the repository without making the current verified Android build consume an incompatible dependency lock.
