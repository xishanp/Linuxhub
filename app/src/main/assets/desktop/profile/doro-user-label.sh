#!/bin/sh
user_name="${USER:-$(id -un 2>/dev/null)}"
printf '<icon>avatar-default</icon><txt>%s</txt><tool>%s</tool>\n' "$user_name" "$user_name"
