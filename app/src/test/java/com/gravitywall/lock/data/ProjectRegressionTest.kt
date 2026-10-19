package com.gravitywall.lock.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ProjectRegressionTest {
    @Test fun newProjectDoesNotReuseAnExistingNumberAfterDeletion() {
        val root = Files.createTempDirectory("gravitywall-test").toFile()
        try {
            File(root, "项目1").mkdir()
            File(root, "项目3").mkdir()
            val original = File(root, "项目3/project.json").apply { writeText("keep") }
            val name = Project.uniqueName("项目3", root)
            assertEquals("项目3(2)", name)
            assertEquals("keep", original.readText())
            assertFalse(File(root, name).exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun repeatedImportsChooseTheNextUnusedDirectory() {
        val root = Files.createTempDirectory("gravitywall-test").toFile()
        try {
            listOf("海报", "海报(2)").forEach { File(root, it).mkdir() }
            assertEquals("海报(3)", Project.uniqueName("海报", root))
        } finally { root.deleteRecursively() }
    }

    @Test fun invalidProjectNamesCannotEscapeTheProjectsDirectory() {
        listOf("", "  ", ".", "..", "../outside", "a/b", "a\\b", "bad\nname").forEach {
            assertFalse(it, Project.isValidName(it))
        }
        assertTrue(Project.isValidName("项目 (2).新版"))
    }

    @Test fun archiveRoundTripPreservesMasksAndReverseParallax() {
        val target = ProjectLayer(id = "target", name = "人物", image = "subject.png",
            minX = -90f, maxX = 120f, coefY = -2f, visible = false)
        val mask = ProjectLayer(id = "mask", name = "窗口", maskShape = "rect",
            maskTargetId = target.id, maskRadius = 30f, minX = -24f, maxX = 24f)
        val project = Project("原项目", mutableListOf(target, mask), baseLayerId = target.id)
        val restored = Project.fromJson(project.toJson())!!
        assertEquals(project.toJson(), restored.toJson())
        assertArrayEquals(floatArrayOf(-90f, 120f, 60f, -60f),
            restored.layers[0].resolvedMotion(30f), 0f)
        assertArrayEquals(floatArrayOf(-24f, 24f, 0f, 0f),
            restored.layers[1].resolvedMotion(30f), 0f)
    }

    @Test fun savedRuntimeConfigRetainsAppliedProjectAndSettings() {
        val config = RuntimeConfig(project = "/sdcard/Download/gravitywall/projects/A/project.json",
            fullScaleDeg = 16f, smooth = 0.6f, amplitude = 0.08f, unlockHideDelayMs = 850)
        val json = org.json.JSONObject(config.toJson())
        assertEquals(config.project, json.getString("project"))
        assertEquals(16.0, json.getDouble("fullScaleDeg"), 0.001)
        assertEquals(0.6, json.getDouble("smooth"), 0.001)
        assertEquals(0.08, json.getDouble("amplitude"), 0.001)
        assertEquals(850, json.getInt("unlockHideDelayMs"))
    }
}
