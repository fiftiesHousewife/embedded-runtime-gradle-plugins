if [ -d /run/systemd/system ]; then
  systemctl daemon-reload >/dev/null 2>&1 || :
  if [ "$1" -ge 1 ]; then systemctl try-restart @NAME@.service >/dev/null 2>&1 || :; fi
fi
exit 0
