#!/bin/sh
set -eu

base=/var/cache/doro-offline
manifest="$base/vendor-packages.txt"
packages="$base/packages"

test -s "$manifest"
test -d "$packages"

# User applications are an overlay, matching the 1.1.911 bootstrap model.
# Their dependencies are installed by the system archive first; this archive
# deliberately does not mutate dpkg's database.
while IFS= read -r package || [ -n "$package" ]; do
    [ -n "$package" ] || continue
    case "$package" in
        */*|*..*|.*)
            printf '非法 vendor 包名: %s\n' "$package" >&2
            exit 1
            ;;
    esac
    deb="$packages/$package"
    test -f "$deb" || {
        printf '缺少 vendor 包: %s\n' "$deb" >&2
        exit 1
    }
    # dpkg-deb's extractor replaces top-level merged-/usr links such as lib ->
    # usr/lib when a package contains ./lib/... and then dynamic commands can
    # no longer start. Feed the data tarball to GNU tar without that behavior.
    dpkg-deb --fsys-tarfile "$deb" | tar --keep-directory-symlink --same-owner -xpf - -C /
done < "$manifest"

ln -sfn /opt/google/chrome/google-chrome /usr/local/bin/google-chrome

# dpkg-deb -x does not run maintainer-script triggers, so refresh the shared
# caches explicitly after every application file is present.
mkdir -p /etc/ca-certificates /etc/ssl/certs
find /usr/share/ca-certificates -type f -name '*.crt' \
    | sed 's#^/usr/share/ca-certificates/##' \
    | LC_ALL=C sort > /etc/ca-certificates.conf
test -s /etc/ca-certificates.conf
if command -v update-ca-certificates >/dev/null 2>&1; then
    update-ca-certificates --fresh
else
    printf '缺少 update-ca-certificates\n' >&2
    exit 1
fi
test -s /etc/ssl/certs/ca-certificates.crt
if [ -x /usr/local/bin/doro-refresh-launchers ]; then
    /usr/local/bin/doro-refresh-launchers
else
    printf '缺少统一桌面入口修补器: /usr/local/bin/doro-refresh-launchers\n' >&2
    exit 1
fi
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database -q /usr/share/applications >/dev/null 2>&1 || :
fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
    gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor >/dev/null 2>&1 || :
fi
if command -v glib-compile-schemas >/dev/null 2>&1; then
    glib-compile-schemas /usr/share/glib-2.0/schemas >/dev/null 2>&1 || :
fi
if command -v update-mime-database >/dev/null 2>&1; then
    update-mime-database /usr/share/mime >/dev/null 2>&1 || :
fi
if command -v gdk-pixbuf-query-loaders >/dev/null 2>&1; then
    cache=/usr/lib/aarch64-linux-gnu/gdk-pixbuf-2.0/2.10.0/loaders.cache
    gdk-pixbuf-query-loaders > "$cache" 2>/dev/null || :
fi
if command -v fc-cache >/dev/null 2>&1; then
    fc-cache -fs >/dev/null 2>&1 || :
fi
if command -v ldconfig >/dev/null 2>&1; then
    ldconfig
fi
