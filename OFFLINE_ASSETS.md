# Offline assets

Large offline archives are intentionally excluded from Git. A clean source checkout must obtain or generate them before building the debug APK.

## Required archives

- `app/src/main/assets/offline/bootstrap-arm64.tar`
- `app/src/main/assets/offline/vendor-apps-arm64.tar`
- `app/src/main/assets/rootfs/ubuntu-base-24.04.4-base-arm64.tar`

The generated package manifest and lock file are tracked:

- `app/src/main/assets/offline/bootstrap-packages.txt`
- `tools/offline/offline-bootstrap.lock.json`
- `tools/offline/package-roots.txt`

## Bootstrap pool

The single package pool replaces the old `system-base`, `system-tools`, `cli-extra`, `spark-store-deps`, `gpu-tools`, and factory-fix layers.

Current generated result:

- Root package declarations: 503
- Resolved packages: 583
- Archive size: 342,487,040 bytes
- SHA256: `374f1223ab619a153fbacfaf8e30fea474785a5a153f3215be58565e2e6e8f4a`

Rebuild the pool with:

```bash
python3 tools/offline/build_offline_bootstrap.py --rebuild-tar
```

The generator resolves the complete Ubuntu 24.04 arm64 dependency closure from the pinned noble/updates/security indices, locks exact versions and hashes, and only reuses a cached DEB when name and version match exactly. The TAR is deterministic; run the command twice and compare SHA256 before distributing it.

## Vendor applications

`vendor-apps-arm64.tar` is gzip-compressed and contains the Firefox application tree and desktop entry. Its runtime libraries belong to the bootstrap pool, not to a second patch layer.

Current SHA256 (compressed asset):

`2baeb0e5767633698d4d225f29ee78c2e9afc5a7bc4ccefd5c0d4ee8276aebd7`

## Distribution

The APK build reads these archives directly from `app/src/main/assets/`. For release distribution, publish the generated assets separately or package them deliberately in a release artifact; do not silently rely on a clean source checkout containing them.

No release signing is configured in this branch.
