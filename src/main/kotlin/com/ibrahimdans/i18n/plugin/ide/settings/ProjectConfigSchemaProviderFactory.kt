package com.ibrahimdans.i18n.plugin.ide.settings

import com.intellij.json.JsonFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/**
 * Gives the project's `i18n-check.json` the schema of [ProjectConfigSchema]: completion of the setting
 * names and values, and a warning on a name the file does not take or a value of the wrong type.
 * Declared in `jsonConfig.xml`, which loads only with the JSON plugin.
 */
class ProjectConfigSchemaProviderFactory : JsonSchemaProviderFactory, DumbAware {

    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(Provider(project))

    private class Provider(private val project: Project) : JsonSchemaFileProvider {

        /** The file [Settings] reads: `i18n-check.json` at the project's root, not one further down. */
        override fun isAvailable(file: VirtualFile): Boolean =
            file.name == ProjectConfigFile.PATH && file.parent != null && file.parent == project.guessProjectDir()

        override fun getName(): String = ProjectConfigFile.PATH

        override fun getSchemaFile(): VirtualFile = SCHEMA

        override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
    }

    private companion object {
        /** Built once: the settings the file takes do not change while the IDE runs. */
        val SCHEMA: VirtualFile by lazy {
            LightVirtualFile("i18n-check.schema.json", JsonFileType.INSTANCE, ProjectConfigSchema.text()).apply {
                isWritable = false
            }
        }
    }
}
