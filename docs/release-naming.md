# LinuxHub Release Naming

## Current GNOME desktop

- Desktop family: `Myth`
- Display name: `LinuxHub`
- Android application ID: `com.linuxhub.myth`
- Current version name: `Myth-Prometheus`

`Myth` identifies the GNOME desktop family. `Prometheus` identifies this
release. The public version name combines them as `Myth-Prometheus`; the app
display name remains `LinuxHub`.

## Android fields

- `applicationId` uses the scheme `com.linuxhub.<desktop-family>`.
- The `app` segment and numeric `v2` suffix are no longer used in the
  application ID.
- `versionName` is an English release name and contains no numeric version.
- `versionCode` remains an internal, monotonically increasing integer. It is
  required by Android for upgrade ordering and is not shown as the public
  version.

## Release names

LinuxHub release names use Greek mythology. New releases use a different god
or mythological figure name. The order is tracked separately; alphabetical or
mythological order does not determine Android upgrade order.

Current version:

```text
Myth-Prometheus
```

The next name has not been assigned yet. Do not append `Alpha`, `Beta`, or a
numeric suffix to a public release name. Preview builds, if needed later, use a
separate explicit channel name.

## Other desktop families

Other desktop families are not implemented yet. Their `Myth`-style family
name, application ID, and release naming rules are intentionally undecided.
Do not create packages or aliases for them until their desktop route is ready.