/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.chainhelper

import android.content.Context
import android.os.Parcelable

/**
 * Stand-in DroidGuard VM class for HandleProxyFactory tests: the factory only
 * needs a class carrying the (Context, Parcelable) constructor that
 * HandleProxy's reflective instantiation targets.
 */
class FakeProvisionVm(val context: Context, val data: Parcelable)
