/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.droidguard.core.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pins the ContainedEditTextPreference resource + state contract:
 *  - the constructor assigns preference_material_with_widget_below as the item
 *    layout and preference_edit_widget as the widget layout — the latter
 *    carries the android.R.id.edit EditText onBindViewHolder binds against
 *    (JVM-harness boundary: layout inflation needs includeAndroidResources,
 *    which this module does not enable, so the wiring is pinned at the
 *    resource-id level)
 *  - text/hint/editable store verbatim and textChangedListener is replaceable
 *  - editRequestFocus() raises the private one-shot requestFocus flag and
 *    calls notifyChanged()
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContainedEditTextPreferenceBindTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun constructor_assignsLayoutAndWidgetResources() {
        val pref = ContainedEditTextPreference(context)
        assertEquals(R.layout.preference_material_with_widget_below, pref.layoutResource)
        assertEquals(R.layout.preference_edit_widget, pref.widgetLayoutResource)
    }

    @Test
    fun textHintEditable_storeVerbatim() {
        val pref = ContainedEditTextPreference(context)
        pref.text = "seed-value"
        pref.hint = "hint-x"
        pref.editable = false
        assertEquals("seed-value", pref.text)
        assertEquals("hint-x", pref.hint)
        assertFalse(pref.editable)
        pref.editable = true
        assertTrue(pref.editable)
    }

    @Test
    fun textChangedListener_isReplaceable() {
        val called = AtomicBoolean(false)
        val pref = ContainedEditTextPreference(context)
        pref.textChangedListener = { called.set(true) }
        assertNotNull(pref.textChangedListener)
        pref.textChangedListener("x")
        assertTrue(called.get())
    }

    @Test
    fun editRequestFocus_setsOneShotFlag() {
        val pref = ContainedEditTextPreference(context)
        val f = ContainedEditTextPreference::class.java.getDeclaredField("requestFocus")
        f.isAccessible = true
        assertFalse(f.getBoolean(pref))
        pref.editRequestFocus()
        assertTrue(f.getBoolean(pref))
    }
}
