#!/bin/sh
value=$(cat /run/doro-audio/battery-capacity 2>/dev/null || echo --)
case "$value" in
  ''|*[!0-9]*) icon=battery-missing-symbolic ;;
  [0-9]|1[0-9]) icon=battery-empty-symbolic ;;
  2[0-9]|3[0-9]) icon=battery-caution-symbolic ;;
  4[0-9]|5[0-9]|6[0-9]|7[0-9]) icon=battery-good-symbolic ;;
  *) icon=battery-full-symbolic ;;
esac
printf '<icon>%s</icon><txt> %s%%</txt><tool>电池电量：%s%%</tool>\n' "$icon" "$value" "$value"
