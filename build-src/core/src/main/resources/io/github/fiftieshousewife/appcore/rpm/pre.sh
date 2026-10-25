getent group @USER@ >/dev/null || groupadd -r @USER@
getent passwd @USER@ >/dev/null || \
  useradd -r -g @USER@ -d /var/lib/@NAME@ -s /sbin/nologin -c "@NAME@ service account" @USER@
exit 0
