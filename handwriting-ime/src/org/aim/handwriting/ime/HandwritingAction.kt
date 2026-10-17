package org.aim.handwriting.ime

import android.content.Context
import org.aim.handwriting.FakeRecognizer
import org.aim.handwriting.HandwritingRecognizer
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action

/**
 * Picks the recognizer for a locale. Real engines will load models from
 * [Context.getFilesDir] only; the keyboard never downloads anything, and a
 * missing model surfaces as RecognizerException.ModelMissing.
 * Phase 1: always the fake engine, so the pad can be exercised end to end.
 */
object HandwritingEngines {
    fun create(context: Context, locale: String): HandwritingRecognizer =
        FakeRecognizer(readyLocales = setOf(locale))
}

val HandwritingAction = Action(
    icon = R.drawable.ic_handwriting,
    name = R.string.action_handwriting_title,
    simplePressImpl = null,
    persistentState = null,
    windowImpl = { manager, _ ->
        val locale = manager.getActiveLocales().firstOrNull()?.toLanguageTag() ?: "en"
        HandwritingActionWindow(
            manager = manager,
            recognizer = HandwritingEngines.create(manager.getContext(), locale),
            locale = locale,
        )
    },
)
