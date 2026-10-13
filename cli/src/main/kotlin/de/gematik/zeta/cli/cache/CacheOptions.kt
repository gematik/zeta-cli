/*
 * Copyright (Change Date see Readme), gematik GmbH
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the
 * European Commission – subsequent versions of the EUPL (the "Licence").
 * You may not use this work except in compliance with the Licence.
 *
 * You find a copy of the Licence in the "Licence" file or at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied.
 * In case of changes by gematik GmbH find details in the "Readme" file.
 *
 * See the Licence for the specific language governing permissions and limitations under the Licence.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 */
package de.gematik.zeta.cli.cache

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Path

/**
 * Selects the shared [CacheDb] file and its bounds. Mixed into the commands that actually cache
 * something — today `zeta vsdm get` and `zeta serve` — rather than onto a base class, so commands
 * with nothing to cache do not advertise the option.
 *
 * There is no default path on purpose: cached payloads can carry personal data, so caching stays
 * off until someone names a location. A value is mandatory — an optional-value flag would swallow
 * the positional PoPP token in `zeta vsdm get --cache-db eyJ…`.
 */
internal class CacheOptions : OptionGroup(name = "Cache options") {

    val db: Path? by option(
        "--cache-db",
        metavar = "FILE",
        envvar = "ZETA_CACHE_DB",
        help = "Shared SQLite cache file. For VSDM reads it holds bundles so unchanged records are " +
            "revalidated instead of re-transferred. Off when unset; contents are stored " +
            "unencrypted — pick the location deliberately. (env: ZETA_CACHE_DB)",
    ).path(canBeFile = true, canBeDir = false)

    val maxEntries: Int by option(
        "--cache-max-entries",
        metavar = "N",
        envvar = "ZETA_CACHE_MAX_ENTRIES",
        help = "Entries to keep per cached kind; the least recently revalidated go first. " +
            "Default: 10000. (env: ZETA_CACHE_MAX_ENTRIES)",
    ).int().default(10_000)

    val maxAgeDays: Int by option(
        "--cache-max-age-days",
        metavar = "DAYS",
        envvar = "ZETA_CACHE_MAX_AGE_DAYS",
        help = "Drop entries nobody has revalidated in this long. Not an expiry — entries are " +
            "always revalidated against their source — but hygiene, so personal data does not " +
            "linger. Default: 180. (env: ZETA_CACHE_MAX_AGE_DAYS)",
    ).int().default(180)
}
