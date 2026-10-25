package io.github.fiftieshousewife.appcore.tasks

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import java.io.File

/** The fields of `META-INF/app.json` that identify an extracted app. */
internal data class AppManifest(val name: String, val version: String, val target: String) {
    companion object {
        fun read(home: File): AppManifest {
            val file = home.resolve("META-INF/app.json")
            if (!file.isFile) throw GradleException("$file missing: not an archive built by java-app or node-app")
            val json = JsonSlurper().parse(file) as Map<*, *>
            return AppManifest(field(json, "name", file), field(json, "version", file), field(json, "target", file))
        }

        private fun field(json: Map<*, *>, name: String, file: File) =
            json[name] as? String ?: throw GradleException("$file has no \"$name\"")
    }
}
