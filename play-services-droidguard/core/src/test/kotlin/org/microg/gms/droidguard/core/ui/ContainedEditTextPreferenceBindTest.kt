/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import androidx.preference.PreferenceViewHolder
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.droidguard.core.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

/**
 * Pins the ContainedEditTextPreference binding contract: the preference wires
 * preference_material_with_widget_below (item) + preference_edit_widget
 * (widget_frame payload carrying the android.R.id.edit EditText), and
 * onBindViewHolder applies text/hint/editable onto that EditText, tags it with
 * the preference instance, and consumes the one-shot requestFocus flag.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContainedEditTextPreferenceBindTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun inflatedItemView(): ViewGroup {
        // The item layout (preference_material_with_widget_below) uses
        // ?android:attr/listPreferredItem* theme attrs that do not resolve
        // under the bare unit-test theme; the binding contract under test is
        // the widget layout supplying an android.R.id.edit EditText, so the
        // widget is inflated into a plain container standing in for
        // widget_frame.
        val item = android.widget.LinearLayout(context)
        LayoutInflater.from(context).inflate(R.layout.preference_edit_widget, item, true)
        return item
    }

    private fun bind(pref: ContainedEditTextPreference): EditText {
        val item = inflatedItemView()
        val holder = PreferenceViewHolder.createInstanceForTests(item)
        pref.onBindViewHolder(holder)
        return holder.itemView.findViewById(android.R.id.edit)
    }

    @Test
    fun constructor_assignsLayoutAndWidgetResources() {
        val pref = ContainedEditTextPreference(context)
        assertEquals(R.layout.preference_material_with_widget_below, pref.layoutResource)
        assertEquals(R.layout.preference_edit_widget, pref.widgetLayoutResource)
    }

    @Test
    fun onBindViewHolder_locatesNestedEditText_andAppliesState() {
        val pref = ContainedEditTextPreference(context).apply {
            text = "seed-value"
            hint = "hint-x"
            editable = false
        }
        val edit = bind(pref)
        assertNotNull(edit)
        assertEquals("seed-value", edit.text.toString())
        assertEquals("hint-x", edit.hint.toString())
        assertFalse(edit.isEnabled)
        assertSame(pref, edit.tag)
    }

    @Test
    fun textChangedListener_receivesPostBindEdits() {
        val seen = AtomicReference<String>()
        val pref = ContainedEditTextPreference(context)
        val edit = bind(pref)
        pref.textChangedListener = { seen.set(it) }
        edit.setText("typed-value")
        assertEquals("typed-value", seen.get())
    }

    @Test
    fun editRequestFocus_flagConsumedByBind() {
        val pref = ContainedEditTextPreference(context)
        pref.editRequestFocus()
        bind(pref)
        val f = ContainedEditTextPreference::class.java.getDeclaredField("requestFocus")
        f.isAccessible = true
        assertFalse(f.getBoolean(pref))
    }

    @Test
    fun editableDefaultTrue_bindsEnabledEditText() {
        val pref = ContainedEditTextPreference(context)
        val edit = bind(pref)
        assertTrue(pref.editable)
        assertTrue(edit.isEnabled)
    }
}
