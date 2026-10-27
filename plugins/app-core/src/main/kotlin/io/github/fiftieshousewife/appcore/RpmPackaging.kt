package io.github.fiftieshousewife.appcore

import org.gradle.api.provider.Property
import java.net.URI

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
 */
abstract class RpmPackaging {
    /** Build `appRpm<Target>` for every Linux target. Default false, or `-PjavaApp.rpm=true`. */
    abstract val enabled: Property<Boolean>

    /** The package name, and the name of its directories, user and unit. Defaults to the app name. */
    abstract val packageName: Property<String>

    /** The RPM release: `1`, then `2` for a repackaging of the same version. Default `1`. */
    abstract val release: Property<String>

    /** One line for `dnf info`. Defaults to `<app> with its own runtime`. */
    abstract val summary: Property<String>

    /** An SPDX licence expression. Defaults to the app's `license`. */
    abstract val license: Property<String>
    abstract val vendor: Property<String>
    abstract val url: Property<String>

    /** The system account the service runs as, created on install if missing. Defaults to [packageName]. */
    abstract val user: Property<String>

    /** Install and manage a systemd unit. Default true; false for hosts without systemd. */
    abstract val systemd: Property<Boolean>

    /**
     * The YUM repository `appPublishRpm` uploads to with an HTTP PUT, e.g. an Artifactory RPM
     * repository, which then rebuilds its own `repodata/`. Defaults to `binaryRepo.repo.rpm` on the
     * configured BinaryRepository.
     */
    abstract val repositoryUrl: Property<URI>
}
