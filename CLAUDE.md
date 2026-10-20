# SecureRemoteDesktop

## Build rule

When asked to build, always produce the ship-ready zips in `dist\` (not just the exes in `<module>\bin`):

```
powershell -ExecutionPolicy Bypass -File build\build-exe.ps1 -BundleJre -Package [-Only client|server]
```

- Output: `dist\SecureRemoteDesktop{Client,Server}-<version>-win64.zip`.
- Use `-Only server` / `-Only client` when only that module changed.
- Stop a running exe first (the script refuses if it is running).
