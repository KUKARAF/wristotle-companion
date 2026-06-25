// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.codes

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * zxing-android-embedded's default capture activity goes landscape;
 * `setOrientationLocked` only pins whatever it launched in. This subclass exists
 * solely so the manifest can fix it to `screenOrientation="portrait"`. Wire it
 * via `ScanOptions.setCaptureActivity(PortraitCaptureActivity::class.java)`.
 */
class PortraitCaptureActivity : CaptureActivity()
