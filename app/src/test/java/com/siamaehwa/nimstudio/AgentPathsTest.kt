package com.siamaehwa.nimstudio

import com.siamaehwa.nimstudio.core.agent.AgentPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPathsTest {

    @Test
    fun normalizePlainRelativePaths() {
        assertEquals("app/src/Main.kt", AgentPaths.normalize("app/src/Main.kt"))
        assertEquals("app/src/Main.kt", AgentPaths.normalize("./app/src/Main.kt"))
        assertEquals("app/src/Main.kt", AgentPaths.normalize("app/src/Main.kt/"))
        assertEquals("", AgentPaths.normalize("."))
        assertEquals("", AgentPaths.normalize(""))
        assertEquals("a/b", AgentPaths.normalize("a\\b"))
    }

    @Test
    fun rejectEscapesAndAbsolutePaths() {
        assertNull(AgentPaths.normalize("/etc/passwd"))
        assertNull(AgentPaths.normalize("../secret.txt"))
        assertNull(AgentPaths.normalize("app/../../secret.txt"))
        assertNull(AgentPaths.normalize("app//Main.kt"))
    }

    @Test
    fun blockedFoldersAreRejected() {
        assertTrue(AgentPaths.isBlocked(".git/config"))
        assertTrue(AgentPaths.isBlocked("app/build/outputs/apk"))
        assertTrue(AgentPaths.isBlocked("node_modules/react/index.js"))
        assertFalse(AgentPaths.isBlocked("app/src/main/Main.kt"))
        assertFalse(AgentPaths.isBlocked("build.gradle.kts"))
    }

    @Test
    fun textFileDetection() {
        assertTrue(AgentPaths.isTextFile("Main.kt"))
        assertTrue(AgentPaths.isTextFile("README.md"))
        assertTrue(AgentPaths.isTextFile("gradle.properties"))
        assertTrue(AgentPaths.isTextFile("AndroidManifest.xml"))
        assertFalse(AgentPaths.isTextFile("icon.png"))
        assertFalse(AgentPaths.isTextFile("app.apk"))
        assertFalse(AgentPaths.isTextFile("no_extension"))
    }
}
