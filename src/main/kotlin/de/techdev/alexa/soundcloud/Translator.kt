package de.techdev.alexa.soundcloud

import java.text.MessageFormat
import java.util.*

class Translator(private val locale: Locale) {

    private val bundle = ResourceBundle.getBundle("messages", locale)

    fun getTranslation(key: String): String = getStringAsUTF8(key)

    fun getTranslation(key: String, vararg foo: Any): String {
        val formatter = MessageFormat(getStringAsUTF8(key), locale)
        return formatter.format(foo)
    }

    /**
     * ResourceBundle assumes read Strings to be ISO encoded, so we convert it to UTF-8.
     */
    private fun getStringAsUTF8(key: String): String = String(bundle.getString(key).toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
}