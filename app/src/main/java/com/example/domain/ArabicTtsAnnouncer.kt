package com.example.domain

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class ArabicTtsAnnouncer(context: Context) {
  private var tts: TextToSpeech? = null
  private var ready = false
  private var lastSpokenKey: String? = null

  init {
    tts =
      TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
          val arLocale = Locale.forLanguageTag("ar")
          val res = tts?.setLanguage(arLocale)
          ready =
            res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
        }
      }
  }

  fun speakIfNew(key: String, arabicMessage: String) {
    if (!ready || key == lastSpokenKey || arabicMessage.isBlank()) return
    lastSpokenKey = key
    tts?.speak(arabicMessage, TextToSpeech.QUEUE_FLUSH, null, key)
  }

  fun stop() {
    tts?.stop()
  }

  fun shutdown() {
    tts?.stop()
    tts?.shutdown()
    tts = null
    ready = false
  }
}
