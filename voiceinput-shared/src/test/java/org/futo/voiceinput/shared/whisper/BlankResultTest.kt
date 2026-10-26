package org.futo.voiceinput.shared.whisper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Regression tests for the blank-result filter on the ACTUAL production
 * helper ([isBlankResult]) used by both AudioRecognizer call sites
 * (partialResult at AudioRecognizer.kt:538 and final result at :567).
 *
 * Neither call site lowercases; only the final path trims. The helper must
 * therefore be case- and whitespace-robust itself (locale-independent).
 */
class BlankResultTest {

    @Test
    fun lowercaseMarkersAreBlank() {
        listOf(
            "you",
            "(bell dings)",
            "(blank audio)",
            "(beep)",
            "(bell)",
            "(music)",
            "(music playing)",
            "♪",
            "♪♪"
        ).forEach { assertTrue("expected blank: <$it>", isBlankResult(it)) }
    }

    @Test
    fun uppercaseMarkersAreBlank_exactUserSymptom() {
        // Exact reported symptom: uppercase model output escaped the filter.
        listOf(
            "[BLANK_AUDIO]",
            "(BLANK AUDIO)",
            "(BELL DINGS)",
            "(BEEP)",
            "(BELL)",
            "(MUSIC)",
            "(MUSIC PLAYING)",
            "YOU"
        ).forEach { assertTrue("expected blank: <$it>", isBlankResult(it)) }
    }

    @Test
    fun mixedCaseMarkersAreBlank() {
        listOf(
            "[Blank_Audio]",
            "(Blank Audio)",
            "(Bell Dings)",
            "(Beep)",
            "You",
            "yOu"
        ).forEach { assertTrue("expected blank: <$it>", isBlankResult(it)) }
    }

    @Test
    fun generatedPermutationsAreBlank() {
        // Bracket/paren + space/underscore permutations are pre-generated;
        // every case variant must still match.
        listOf(
            "(blank_audio)",
            "[blank audio]",
            "[blank_audio]",
            "[BLANK AUDIO]",
            "(BELL_DINGS)",
            "[bell dings]",
            "[MUSIC_PLAYING]",
            "(music_playing)"
        ).forEach { assertTrue("expected blank: <$it>", isBlankResult(it)) }
    }

    @Test
    fun surroundingWhitespaceIsBlank() {
        // Final call path trims before calling, but the partial-result path
        // passes raw model text; the helper stays robust either way.
        listOf(
            "  [BLANK_AUDIO]  ",
            "\t(blank audio)\n",
            " YOU ",
            "  (music)  "
        ).forEach { assertTrue("expected blank: <$it>", isBlankResult(it)) }
    }

    @Test
    fun genuineSpeechIsPreserved() {
        listOf(
            "hello world",
            "you are great",
            "I love music",
            "Blank audio recording tips for podcasts",
            "the bell dings loudly in the tower",
            "you",
            // sanity: single marker word with extra content is NOT blank
            "you you you said what"
        ).forEach {
            if (it == "you") {
                assertTrue("expected blank: <$it>", isBlankResult(it))
            } else {
                assertFalse("must NOT be blank: <$it>", isBlankResult(it))
            }
        }
    }

    @Test
    fun bracketProseIsPreserved() {
        listOf(
            "[please hold]",
            "(to be announced)",
            "[laughter]",
            "(coughing)",
            "[inaudible 00:12]"
        ).forEach { assertFalse("must NOT be blank: <$it>", isBlankResult(it)) }
    }

    @Test
    fun turkishLocaleStillFilters() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale("tr", "TR"))
        try {
            // 'I' lowercases to dotless 'ı' in Turkish; Locale.ROOT avoids that.
            assertTrue(isBlankResult("[BLANK_AUDIO]"))
            assertTrue(isBlankResult("(BELL DINGS)"))
            assertTrue(isBlankResult("YOU"))
            assertFalse(isBlankResult("hello world"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
