/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.media

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import com.android.systemui.R
import com.android.systemui.util.animation.TransitionLayout

private const val TAG = "MediaViewHolder"

/**
 * Parent class for different media player views
 */
abstract class MediaViewHolder constructor(itemView: View) {
    val player = itemView as TransitionLayout

    // Player information
    val appIcon = itemView.requireViewById<ImageView>(R.id.icon)
    val titleText = itemView.requireViewById<TextView>(R.id.header_title)
    val artistText = itemView.requireViewById<TextView>(R.id.header_artist)

    // Output switcher
    val seamless = itemView.requireViewById<ViewGroup>(R.id.media_seamless)
    val seamlessIcon = itemView.requireViewById<ImageView>(R.id.media_seamless_image)
    val seamlessText = itemView.requireViewById<TextView>(R.id.media_seamless_text)
    val seamlessButton = itemView.requireViewById<View>(R.id.media_seamless_button)

    // Seekbar views
    val seekBar = itemView.requireViewById<SeekBar>(R.id.media_progress_bar)
    open val elapsedTimeView: TextView? = null
    open val totalTimeView: TextView? = null

    // Settings screen
    val longPressText = itemView.requireViewById<TextView>(R.id.remove_text)
    val cancel = itemView.requireViewById<View>(R.id.cancel)
    val dismiss = itemView.requireViewById<ViewGroup>(R.id.dismiss)
    val dismissLabel = dismiss.getChildAt(0)
    val settings = itemView.requireViewById<View>(R.id.settings)
    val settingsText = itemView.requireViewById<TextView>(R.id.settings_text)

    init {
        (player.background as IlluminationDrawable).let {
            it.registerLightSource(seamless)
            it.registerLightSource(cancel)
            it.registerLightSource(dismiss)
            it.registerLightSource(settings)
        }
    }

    abstract fun getAction(id: Int): ImageButton

    fun marquee(start: Boolean, delay: Long) {
        val longPressTextHandler = longPressText.getHandler()
        if (longPressTextHandler == null) {
            Log.d(TAG, "marquee while longPressText.getHandler() is null", Exception())
            return
        }
        longPressTextHandler.postDelayed({ longPressText.setSelected(start) }, delay)
    }
}