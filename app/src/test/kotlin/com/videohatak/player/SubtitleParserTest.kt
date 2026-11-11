package com.videohatak.player

import com.videohatak.player.core.subtitle.model.SubtitleFormat
import com.videohatak.player.core.subtitle.parser.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SubtitleParser] verifying SRT and WebVTT parsing,
 * Arabic text handling, HTML markup sanitization, and timestamp synchronization.
 */
class SubtitleParserTest {

    @Test
    fun parseSrt_standardFile_parsesAllCuesCorrectly() {
        val srtContent = """
            1
            00:00:01,000 --> 00:00:04,500
            مرحباً بكم في تطبيق فيديوهاتك
            
            2
            00:00:05,200 --> 00:00:08,800
            مشغل الفيديو المحلي الأكثر تطوراً
        """.trimIndent()

        val cues = SubtitleParser.parse(srtContent, SubtitleFormat.SRT)

        assertEquals(2, cues.size)
        
        // Cue 1
        assertEquals(1, cues[0].index)
        assertEquals(1000L, cues[0].startTimeMs)
        assertEquals(4500L, cues[0].endTimeMs)
        assertEquals("مرحباً بكم في تطبيق فيديوهاتك", cues[0].text)

        // Cue 2
        assertEquals(2, cues[1].index)
        assertEquals(5200L, cues[1].startTimeMs)
        assertEquals(8800L, cues[1].endTimeMs)
        assertEquals("مشغل الفيديو المحلي الأكثر تطوراً", cues[1].text)
    }

    @Test
    fun parseWebVtt_withHeaderAndStyles_parsesCuesCorrectly() {
        val vttContent = """
            WEBVTT - VideoHatak Subtitle Demo
            
            STYLE
            ::cue {
                color: #FFFFFF;
            }
            
            00:01.000 --> 00:03.500
            Welcome to VideoHatak Player
            
            00:00:04.000 --> 00:00:07.500
            Enjoy <i>high-definition</i> playback
        """.trimIndent()

        val cues = SubtitleParser.parse(vttContent, SubtitleFormat.WEBVTT)

        assertEquals(2, cues.size)

        // Short timestamp mm:ss.fff
        assertEquals(1000L, cues[0].startTimeMs)
        assertEquals(3500L, cues[0].endTimeMs)
        assertEquals("Welcome to VideoHatak Player", cues[0].text)

        // Full timestamp hh:mm:ss.fff and HTML stripping
        assertEquals(4000L, cues[1].startTimeMs)
        assertEquals(7500L, cues[1].endTimeMs)
        assertEquals("Enjoy high-definition playback", cues[1].text)
    }

    @Test
    fun parseSubtitle_stripsHtmlAndFormattingTags() {
        val srtContent = """
            1
            00:00:01,000 --> 00:00:03,000
            <font color="#ffff00"><b>نص عريض وملون</b></font>
            
            2
            00:00:04,000 --> 00:00:06,000
            <i>نص مائل</i> مع <u>تسطير</u> و {y:i}علامات أخرى
        """.trimIndent()

        val cues = SubtitleParser.parse(srtContent, SubtitleFormat.SRT)

        assertEquals(2, cues.size)
        assertEquals("نص عريض وملون", cues[0].text)
        assertEquals("نص مائل مع تسطير و علامات أخرى", cues[1].text)
    }

    @Test
    fun parseSubtitle_handlesArabicRtlWithTashkeelAndBOM() {
        // UTF-8 BOM representation + Arabic text with diacritics (tashkeel)
        val bom = "\uFEFF"
        val srtContent = bom + """
            1
            00:00:00,500 --> 00:00:03,000
            بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ
            
            2
            00:00:03,500 --> 00:00:06,500
            فِيدْيُوهَاتُكَ — كَمَا تُحِبُّ
        """.trimIndent()

        val cues = SubtitleParser.parse(srtContent, SubtitleFormat.SRT)

        assertEquals(2, cues.size)
        assertEquals("بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ", cues[0].text)
        assertEquals("فِيدْيُوهَاتُكَ — كَمَا تُحِبُّ", cues[1].text)
    }

    @Test
    fun parseSubtitle_handlesOverlappingCues() {
        val srtContent = """
            1
            00:00:01,000 --> 00:00:05,000
            الترجمة الأساسية (المتحدث الأول)
            
            2
            00:00:03,000 --> 00:00:07,000
            الترجمة المتزامنة (المتحدث الثاني)
        """.trimIndent()

        val cues = SubtitleParser.parse(srtContent, SubtitleFormat.SRT)

        assertEquals(2, cues.size)

        // At 2000ms: only cue 1 is active
        val activeAt2s = SubtitleParser.findActiveCues(cues, 2000L)
        assertEquals(1, activeAt2s.size)
        assertEquals("الترجمة الأساسية (المتحدث الأول)", activeAt2s[0].text)

        // At 4000ms: both cues overlap and are active
        val activeAt4s = SubtitleParser.findActiveCues(cues, 4000L)
        assertEquals(2, activeAt4s.size)

        // At 6000ms: only cue 2 is active
        val activeAt6s = SubtitleParser.findActiveCues(cues, 6000L)
        assertEquals(1, activeAt6s.size)
        assertEquals("الترجمة المتزامنة (المتحدث الثاني)", activeAt6s[0].text)

        // At 8000ms: none active
        val activeAt8s = SubtitleParser.findActiveCues(cues, 8000L)
        assertTrue(activeAt8s.isEmpty())
    }

    @Test
    fun findActiveCues_seekingEdgeCases() {
        val cues = listOf(
            SubtitleParser.parse(
                """
                1
                00:00:05,000 --> 00:00:10,000
                منتصف الفيديو
                """.trimIndent(),
                SubtitleFormat.SRT
            ).first()
        )

        // Before start
        assertTrue(SubtitleParser.findActiveCues(cues, 0L).isEmpty())
        assertTrue(SubtitleParser.findActiveCues(cues, 4999L).isEmpty())

        // Exact start boundary
        val atStart = SubtitleParser.findActiveCues(cues, 5000L)
        assertEquals(1, atStart.size)

        // Exact end boundary
        val atEnd = SubtitleParser.findActiveCues(cues, 10000L)
        assertEquals(1, atEnd.size)

        // After end
        assertTrue(SubtitleParser.findActiveCues(cues, 10001L).isEmpty())
        assertTrue(SubtitleParser.findActiveCues(cues, 25000L).isEmpty())
    }

    @Test
    fun dualTrackIndependence_returnsIndependentActiveCues() {
        // Track 1: Arabic
        val arabicTrackContent = """
            1
            00:00:01,000 --> 00:00:04,000
            صباح الخير
        """.trimIndent()

        // Track 2: English
        val englishTrackContent = """
            1
            00:00:01,000 --> 00:00:04,000
            Good morning
        """.trimIndent()

        val arabicCues = SubtitleParser.parse(arabicTrackContent, SubtitleFormat.SRT)
        val englishCues = SubtitleParser.parse(englishTrackContent, SubtitleFormat.SRT)

        val activeArabic = SubtitleParser.findActiveCues(arabicCues, 2000L)
        val activeEnglish = SubtitleParser.findActiveCues(englishCues, 2000L)

        assertEquals("صباح الخير", activeArabic.firstOrNull()?.text)
        assertEquals("Good morning", activeEnglish.firstOrNull()?.text)
    }

    @Test
    fun detectFormat_detectsByExtensionAndContent() {
        assertEquals(SubtitleFormat.SRT, SubtitleParser.detectFormat("movie.srt", null))
        assertEquals(SubtitleFormat.WEBVTT, SubtitleParser.detectFormat("movie.vtt", null))
        assertEquals(SubtitleFormat.WEBVTT, SubtitleParser.detectFormat("unknown.txt", "WEBVTT header content"))
        assertEquals(SubtitleFormat.SRT, SubtitleParser.detectFormat("unknown.txt", "1\n00:00:01,000 --> 00:00:02,000\nText"))
    }
}
