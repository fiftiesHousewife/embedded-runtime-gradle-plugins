package io.github.fiftieshousewife.appcore

import org.gradle.api.provider.Property

/**
 * `javaApp { rpm { … } }` / `nodeApp { rpm { … } }`: an RPM per Linux target, installed the way a
 * Red Hat family server expects (see `docs/packaging-standards.md`):
 *
 * ```
 * /opt/<name>/                     the app directory, read-only, owned by root
 * /etc/<name>/                     conf.defaults/ as %config(noreplace), plus an optional env file
 * /var/lib/<name>/  /var/log/<name>/   writable by the service account only
 * /usr/lib/systemd/system/<name>.service
 * ```
 *
 * `dnf install`, `dnf upgrade` and `dnf history undo` then install, upgrade and roll back the app.
 * The package is named after the app, its summary is the project's description, its licence and URL
 * the app's `license` and the `origin` git remote, and its release `1`.
 */
abstract class RpmPackaging {
    /** Build `appRpm<Target>` for every Linux target. Default false, or `-PjavaApp.rpm=true`. */
    abstract val enabled: Property<Boolean>

    /** The system account the service runs as, created on install if missing. Defaults to the app name. */
    abstract val user: Property<String>

    /** Install and manage a systemd unit. Default true; false for hosts without systemd. */
    abstract val systemd: Property<Boolean>
}
