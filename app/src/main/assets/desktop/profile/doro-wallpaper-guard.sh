#!/bin/sh

# Xfce creates a new monitor key when an output appears (for example
# monitorbuiltin-headless) and initializes it with Xfce's mouse wallpaper.  The
# static profile cannot know that name ahead of time, so inherit LinuxHub's
# wallpaper only while the monitor value is empty or still an Xfce default.
# A user-selected path is never changed.

wallpaper=/usr/share/backgrounds/xfce/doro-ubuntu.png
i=0
while [ "$i" -lt 120 ]; do
    keys=$(xfconf-query -c xfce4-desktop -l 2>/dev/null |
        grep -E '^/backdrop/screen[0-9]+/monitor[^/]+/workspace[0-9]+/last-image$' || true)
    for key in $keys; do
        value=$(xfconf-query -c xfce4-desktop -p "$key" 2>/dev/null || true)
        filename=${value##*/}
        case "$filename" in
            ''|xfce-shapes.svg|xfce-stripes.png|xfce-teal.jpg)
                xfconf-query -c xfce4-desktop -p "$key" -s "$wallpaper" >/dev/null 2>&1 || true
                echo "inherit $key <- $wallpaper"
                ;;
        esac
    done
    i=$((i + 1))
    sleep 0.5
done
