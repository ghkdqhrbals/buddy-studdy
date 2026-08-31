package com.buddystudy.voice.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceTutorStudyRevisionIndexTest {
    private val original = VoiceTutorStudySnapshot(11, 10, "Original child", 3)

    @Test
    fun `legacy revision zero remains the question context until that node is explicitly revised`() {
        val updated = original.copy(topic = "Renamed child", difficulty = 8, revision = 1)
        val index = VoiceTutorStudyRevisionIndex(listOf(original, updated))
        assertEquals(original, index.resolve(11, 0))
        assertEquals(updated, index.resolve(11, 1))
        assertNull(index.resolve(11, 2))
        assertNull(index.resolve(11, Long.MAX_VALUE))
        assertEquals(1L, index.currentRevision)
        assertEquals(listOf(updated), index.currentView())
    }

    @Test
    fun `global epochs advance independently while each study uses its last applicable version`() {
        val sibling = VoiceTutorStudySnapshot(12, 10, "Other child", 5)
        val siblingUpdate = sibling.copy(difficulty = 7, revision = 1)
        val childUpdate = original.copy(difficulty = 6, revision = 2)
        val index = VoiceTutorStudyRevisionIndex(listOf(original, sibling, siblingUpdate, childUpdate))
        assertEquals(mapOf(11L to original, 12L to siblingUpdate), index.viewAt(1))
        assertEquals(mapOf(11L to childUpdate, 12L to siblingUpdate), index.viewAt(2))
    }

    @Test
    fun `conflicting same epoch fails closed instead of resurrecting an older level`() {
        val changed = original.copy(difficulty = 7, revision = 1)
        val index = VoiceTutorStudyRevisionIndex(listOf(original, changed, changed.copy(difficulty = 8)))
        assertEquals(original, index.resolve(11, 0))
        assertNull(index.resolve(11, 1))
        assertTrue(index.currentView().isEmpty())
        assertEquals(listOf(original), index.knownVersions(11))
    }

    @Test
    fun `identical repeated rows are not ambiguity but invalid newer metadata still blocks fallback`() {
        assertEquals(original, VoiceTutorStudyRevisionIndex(listOf(original, original)).resolve(11, 0))
        for (invalid in listOf(
            original.copy(topic = " ", revision = 1),
            original.copy(difficulty = 0, revision = 1),
            original.copy(difficulty = 11, revision = 1),
            original.copy(parentStudyId = 0, revision = 1),
        )) {
            assertNull(VoiceTutorStudyRevisionIndex(listOf(original, invalid)).resolve(11, 1))
        }
    }

    @Test
    fun `negative unknown and prehistory question epochs never acquire a guessed context`() {
        val future = original.copy(revision = 2)
        val index = VoiceTutorStudyRevisionIndex(listOf(future, original.copy(studyId = -1)))
        assertNull(index.resolve(11, -1))
        assertNull(index.resolve(11, 0))
        assertNull(index.resolve(11, 1))
        assertNull(index.resolve(999, 2))
        assertEquals(future, index.resolve(11, 2))
        assertTrue(index.viewAt(-1).isEmpty())
    }

    @Test
    fun `an explicit later unambiguous revision can recover after a corrupt epoch without altering earlier evidence`() {
        val conflict = original.copy(difficulty = 7, revision = 1)
        val recovered = original.copy(difficulty = 9, revision = 2)
        val index = VoiceTutorStudyRevisionIndex(listOf(original, conflict, conflict.copy(difficulty = 8), recovered))
        assertEquals(original, index.resolve(11, 0))
        assertNull(index.resolve(11, 1))
        assertEquals(recovered, index.resolve(11, 2))
    }
}
