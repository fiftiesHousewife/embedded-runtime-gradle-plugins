if [ "$1" -eq 0 ] && [ -d /run/systemd/system ]; then
  systemctl disable --now @NAME@.service >/dev/null 2>&1 || :
fi
exit 0
