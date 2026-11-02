package io.github.fiftieshousewife.appcore

import org.gradle.api.Named
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * One container image the plugin writes the Dockerfile for, `Dockerfile.<name>` in the context, in a Dockerfile's
 * own words:
 *
 * ```
 * javaApp {
 *     images {
 *         register("ubi-micro") {
 *             from(ubiMicro)
 *             env("TZ", "Europe/London")
 *             label("summary", "Order service")
 *         }
 *         register("jre") {
 *             from(ubiMicro)
 *             runtimeOnly()
 *         }
 *     }
 * }
 * ```
 *
 * `install` is the part a Dockerfile makes hard on UBI micro, which has no package manager: the packages are
 * locked per CPU in `rpms.lock.yaml` (`appImagePackagesLock`), fetched by the build from the binary repository
 * and installed into the image with `rpm`, so the image build reaches no repository and runs nothing for
 * another CPU. A Java image on micro gets zlib, which its launcher loads, without asking. So does everything
 * else an image of the app needs: the runtime, the libraries and the app in layers of their own, the OCI and
 * Red Hat labels, `/licenses`, a writable `/var/lib/app`, `HOME` and `LANG`, a non-root user, the port, the
 * health check and the entrypoint.
 */
abstract class ImageSpec
@Inject
constructor(private val name: String) : Named {
    override fun getName() = name

    /** Red Hat's UBI 9 micro, pinned by the plugin release to one rebuild's tag and digest. */
    val ubiMicro: String get() = PluginVersion.ubiMicro

    /** Red Hat's UBI 9 minimal, pinned the same way: the default. */
    val ubiMinimal: String get() = "${PluginVersion.dockerBaseImage}@${PluginVersion.dockerBaseDigest}"

    /** `FROM`: an image reference, digest included for a reproducible build. Default [ubiMinimal]. */
    abstract val base: Property<String>

    fun from(image: String) = base.set(image)

    /** RPMs to add to the base, by name; their dependencies must be in the base or installed too. */
    abstract val packages: ListProperty<String>

    fun install(vararg names: String) = packages.addAll(*names)

    abstract val environment: MapProperty<String, String>

    /** `ENV`, after the plugin's own (`APP_*`, `HOME`, `LANG=C.UTF-8`), which it can override. */
    fun env(name: String, value: String) = environment.put(name, value)

    abstract val labels: MapProperty<String, String>

    /** `LABEL`, after the plugin's own (OCI, and Red Hat's `name`, `vendor`, `summary`…), which it can override. */
    fun label(name: String, value: String) = labels.put(name, value)

    abstract val ports: ListProperty<Int>

    /** `EXPOSE`, replacing the default 8080 (`APP_PORT`, which the first port also sets). */
    fun expose(vararg port: Int) = ports.set(port.toList())

    /** `USER`: default `1001`, in group 0 for OpenShift; empty for none, as OpenShift assigns one. */
    abstract val user: Property<String>

    fun user(user: String) = this.user.set(user)

    /** False for an image of the runtime alone: no app layer, port or health check; the runtime is its command. */
    abstract val app: Property<Boolean>

    fun runtimeOnly() = app.set(false)
}
