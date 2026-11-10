package fr.geonature.occtax.features.settings.io

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import fr.geonature.auth.settings.domain.AuthSettings
import fr.geonature.auth.settings.io.AuthSettingsJsonReader
import fr.geonature.commons.util.nextBooleanOrElse
import fr.geonature.datasync.settings.io.DataSyncSettingsJsonReader
import fr.geonature.maps.settings.io.MapSettingsReader
import fr.geonature.occtax.features.settings.domain.AppSettings
import fr.geonature.occtax.features.settings.domain.InputDateSettings
import fr.geonature.occtax.features.settings.domain.InputSettings
import fr.geonature.occtax.features.settings.domain.NomenclatureSettings
import fr.geonature.occtax.features.settings.domain.PropertySettings
import fr.geonature.occtax.features.settings.error.AppSettingsException
import org.tinylog.Logger
import java.io.Reader
import java.io.StringReader
import java.io.StringWriter

/**
 * Default [JsonReader] about reading a `JSON` stream and build the corresponding [AppSettings]
 * metadata.
 *
 * @author S. Grimault
 */
class AppSettingsJsonReader(private val fromExistingAppSettings: AppSettings? = null) {

    /**
     * parse a `JSON` string to convert as [AppSettings].
     *
     * @param json the `JSON` string to parse
     *
     * @return a [AppSettings] instance from the `JSON` string
     * @throws AppSettingsException if something goes wrong
     */
    fun read(json: String): AppSettings {
        return read(StringReader(json))
    }

    /**
     * parse a `JSON` reader to convert as [AppSettings].
     *
     * @param reader the `Reader` to parse
     * @return a [AppSettings] instance from the `JSON` reader
     * @throws AppSettingsException if something goes wrong
     */
    fun read(reader: Reader): AppSettings {
        val jsonReader = JsonReader(reader)
        val observationRecord = read(jsonReader)
        jsonReader.close()

        return observationRecord
    }

    private fun read(reader: JsonReader): AppSettings {
        val builder = AppSettings.Builder()
        fromExistingAppSettings?.also { builder.from(it) }
        var authSettings: AuthSettings? = null
        var syncJson: String? = null

        runCatching {
            reader.beginObject()

            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "auth" -> {
                        authSettings = AuthSettingsJsonReader(fromExistingAppSettings?.authSettings).read(reader)
                        builder.authSettings(authSettings)
                    }
                    "area_observation_duration" -> builder.areaObservationDuration(reader.nextInt())
                    "input" -> builder.inputSettings(readInputSettings(reader).let {
                        if (it == InputSettings(dateSettings = InputDateSettings.DEFAULT) && fromExistingAppSettings?.inputSettings != it) (fromExistingAppSettings?.inputSettings
                            ?: it)
                        else it
                    })

                    "sync" -> syncJson = copyValueAsString(reader)
                    "map" -> builder.mapSettings(MapSettingsReader(fromExistingAppSettings?.mapSettings).read(reader))
                    "nomenclature" -> readNomenclatureSettings(reader)?.also { builder.nomenclatureSettings(it) }
                    else -> reader.skipValue()
                }
            }

            reader.endObject()

            // 'sync' may also hold the auth settings (e.g. 'sync.gn_application_id'): read it twice
            syncJson?.also { sync ->
                if (authSettings == null) {
                    runCatching {
                        AuthSettingsJsonReader(fromExistingAppSettings?.authSettings).read(JsonReader(StringReader(sync)))
                    }.onSuccess { builder.authSettings(it) }
                        .onFailure { it.message?.also { message -> Logger.warn { message } } }
                }

                builder.dataSyncSettings(DataSyncSettingsJsonReader(fromExistingAppSettings?.dataSyncSettings).read(JsonReader(StringReader(sync))))
            }
        }.onFailure {
            it.message?.also { Logger.warn { it } }

            throw AppSettingsException.JsonParseException(it.message)
        }

        return builder.build()
    }

    private fun copyValueAsString(reader: JsonReader): String {
        val out = StringWriter()
        val writer = JsonWriter(out)
        writer.isLenient = true
        var depth = 0

        do {
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> { reader.beginObject(); writer.beginObject(); depth++ }
                JsonToken.END_OBJECT -> { reader.endObject(); writer.endObject(); depth-- }
                JsonToken.BEGIN_ARRAY -> { reader.beginArray(); writer.beginArray(); depth++ }
                JsonToken.END_ARRAY -> { reader.endArray(); writer.endArray(); depth-- }
                JsonToken.NAME -> writer.name(reader.nextName())
                JsonToken.STRING -> writer.value(reader.nextString())
                JsonToken.NUMBER -> writer.value(NumberString(reader.nextString()))
                JsonToken.BOOLEAN -> writer.value(reader.nextBoolean())
                JsonToken.NULL -> { reader.nextNull(); writer.nullValue() }
                else -> reader.skipValue()
            }
        } while (depth > 0)

        writer.flush()

        return out.toString()
    }

    private class NumberString(private val value: String) : Number() {
        override fun toByte() = value.toDouble().toInt().toByte()
        override fun toDouble() = value.toDouble()
        override fun toFloat() = value.toFloat()
        override fun toInt() = value.toDouble().toInt()
        override fun toLong() = value.toDouble().toLong()
        override fun toShort() = value.toDouble().toInt().toShort()
        override fun toString() = value
    }

    private fun readInputSettings(reader: JsonReader): InputSettings {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()

            return InputSettings(dateSettings = InputDateSettings.DEFAULT)
        }

        var inputDateSettings: InputDateSettings? = null

        reader.beginObject()

        while (reader.hasNext()) {
            when (reader.nextName()) {
                "date" -> inputDateSettings = readInputDateSettings(reader)
                else -> reader.skipValue()
            }
        }

        reader.endObject()

        return InputSettings(dateSettings = inputDateSettings ?: InputDateSettings.DEFAULT)
    }

    private fun readInputDateSettings(reader: JsonReader): InputDateSettings? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()

            return null
        }

        var withEndDate = false
        var withHours = false

        reader.beginObject()

        while (reader.hasNext()) {
            when (reader.nextName()) {
                "enable_end_date" -> withEndDate = reader.nextBooleanOrElse { false }
                "enable_hours" -> withHours = reader.nextBooleanOrElse { false }
                else -> reader.skipValue()
            }
        }

        reader.endObject()

        return InputDateSettings(
            startDateSettings = if (withHours) InputDateSettings.DateSettings.DATETIME else InputDateSettings.DateSettings.DATE,
            endDateSettings = if (withEndDate) InputDateSettings.DateSettings.DATE.let {
                if (withHours) InputDateSettings.DateSettings.DATETIME else it
            } else null
        ).also {
            Logger.info { "input date settings loaded ('start': ${it.startDateSettings?.name?.lowercase()}, 'end': ${it.endDateSettings?.name?.lowercase()})" }
        }
    }

    private fun readNomenclatureSettings(reader: JsonReader): NomenclatureSettings? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()

            return null
        }

        var saveDefaultValues = false
        var withAdditionalFields = true
        val information = mutableListOf<PropertySettings>()
        val counting = mutableListOf<PropertySettings>()

        reader.beginObject()

        while (reader.hasNext()) {
            when (reader.nextName()) {
                "save_default_values" -> saveDefaultValues = reader.nextBooleanOrElse { false }
                "additional_fields" -> withAdditionalFields = reader.nextBooleanOrElse { true }
                "information" -> information.addAll(readPropertySettingsAsList(reader))
                "counting" -> counting.addAll(readPropertySettingsAsList(reader))
                else -> reader.skipValue()
            }
        }

        reader.endObject()

        return NomenclatureSettings(
            saveDefaultValues = saveDefaultValues,
            withAdditionalFields = withAdditionalFields,
            information = information,
            counting = counting
        )
    }

    private fun readPropertySettingsAsList(reader: JsonReader): List<PropertySettings> {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()

            return emptyList()
        }

        val propertySettingsList = mutableListOf<PropertySettings>()

        reader.beginArray()

        while (reader.hasNext()) {
            runCatching {
                readPropertySettings(reader)?.also {
                    propertySettingsList.add(it)
                }
            }.onFailure {
                it.message?.also {
                    Logger.warn { it }
                }
            }
        }

        reader.endArray()

        return propertySettingsList
    }

    private fun readPropertySettings(reader: JsonReader): PropertySettings? {
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()

                var key: String? = null
                var visible = true
                var default = true

                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "key" -> key = reader.nextString()
                        "visible" -> visible = reader.nextBoolean()
                        "default" -> default = reader.nextBoolean()
                        else -> reader.skipValue()
                    }
                }

                reader.endObject()

                if (key.isNullOrEmpty()) {
                    return null
                }

                return PropertySettings(
                    key,
                    visible,
                    default
                )
            }

            JsonToken.STRING -> PropertySettings(
                reader.nextString(),
                visible = true,
                default = true
            )

            else -> {
                reader.skipValue()
                null
            }
        }
    }
}