package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.codeInspection.ex.InspectionToolRegistrar
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * No inspection of the plugin names a YAML class in its own members.
 *
 * YAML is an optional dependency (`ymlConfig.xml`). To save the inspection profile, the platform
 * reflects on every inspection class (`getDeclaredMethods`, `getDeclaredFields`), private members
 * included: one of them typed with a YAML class makes that reflection throw NoClassDefFoundError
 * when the YAML plugin is disabled — "Save settings failed, please restart application", and the
 * profile is never saved. Reproduced in WebStorm with YAML disabled.
 *
 * YAML may still be used inside a method body (the visitor), which the JVM only resolves when
 * that code runs on a YAML file — something that cannot happen without the YAML plugin.
 */
class InspectionYamlIsolationTest : PlatformBaseTest() {

    @Test
    fun `no inspection names a YAML class in its members`() {
        val inspections = InspectionToolRegistrar.getInstance().createTools()
            .map { it.tool.javaClass }
            .filter { it.name.startsWith("com.ibrahimdans.") }
            .distinct()
        Assertions.assertTrue(inspections.size >= 8, "the plugin's inspections must be registered, found $inspections")

        val offending = inspections.flatMap { inspection ->
            classHierarchy(inspection).flatMap { type ->
                (type.declaredMethods.map { it.toGenericString() } + type.declaredFields.map { it.toGenericString() })
                    .filter { YAML_PACKAGE in it }
            }
        }
        Assertions.assertEquals(emptyList<String>(), offending)
    }

    /** [type] and its superclasses that belong to the plugin. */
    private fun classHierarchy(type: Class<*>): List<Class<*>> =
        generateSequence(type) { it.superclass }.takeWhile { it.name.startsWith("com.ibrahimdans.") }.toList()

    private companion object {
        const val YAML_PACKAGE = "org.jetbrains.yaml."
    }
}
