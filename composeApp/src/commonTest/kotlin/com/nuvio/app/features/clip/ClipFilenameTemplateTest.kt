package com.nuvio.app.features.clip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The template is user-editable text that becomes a filename, so the cases that
 * matter are the ones where a user types something the filesystem would refuse.
 */
class ClipFilenameTemplateTest {

    private fun render(template: String, title: String = "Severance S02E05") =
        ClipFilenameTemplate.render(
            template = template,
            title = title,
            startMs = 3_723_000L,
            endMs = 3_731_000L,
            dateLabel = "2026-08-26",
            timeLabel = "14-03-21",
        )

    @Test
    fun `default template reproduces the pre-template naming`() {
        assertEquals("Severance S02E05 1h02m03s-1h02m11s", render(ClipFilenameTemplate.Default))
    }

    @Test
    fun `every token resolves`() {
        assertEquals(
            "Severance S02E05 1h02m03s 1h02m11s 8s 2026-08-26 14-03-21",
            render("{title} {start} {end} {duration} {date} {time}"),
        )
    }

    @Test
    fun `path separators and reserved characters cannot escape the clips folder`() {
        val name = render("{title}", title = "../../etc/passwd: 100%")
        assertTrue('/' !in name, name)
        assertTrue(':' !in name, name)
        assertEquals("etc passwd 100", name)
    }

    @Test
    fun `a blank template falls back to the default rather than an empty name`() {
        assertEquals(render(ClipFilenameTemplate.Default), render("   "))
    }

    @Test
    fun `a template of nothing but punctuation still yields a usable name`() {
        assertEquals(render(ClipFilenameTemplate.Default), render("///"))
    }

    @Test
    fun `an unknown token is left visible instead of silently dropped`() {
        // The braces are stripped as punctuation; what matters is that the
        // token's name survives, so a typo is visible in the preview.
        assertEquals("Severance S02E05 episode", render("{title} {episode}"))
    }

    @Test
    fun `a title containing a token is not itself substituted`() {
        assertEquals("start 1h02m03s", render("{title} {start}", title = "{start}"))
    }

    @Test
    fun `a name that would be hidden on unix is not`() {
        assertEquals("hidden", ClipFilenameTemplate.sanitize(".hidden"))
    }

    @Test
    fun `duration rounds rather than truncates`() {
        assertEquals("8s", formatClipFileDurationLabel(7_900L))
        assertEquals("1m00s", formatClipFileDurationLabel(59_800L))
    }
}
