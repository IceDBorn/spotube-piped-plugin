package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.host_apis.SystemInformationAPI
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val REGION_KEY = "piped.region"

/** Stored value for "follow the system", the default. */
internal const val REGION_AUTO = ""
internal const val REGION_GLOBAL = "GLOBAL"

/** Countries YouTube publishes music charts for, by ISO code, with the name the chart titles use. */
internal val CHART_COUNTRIES: Map<String, String> = linkedMapOf(
    "AR" to "Argentina", "AU" to "Australia", "AT" to "Austria", "BE" to "Belgium", "BO" to "Bolivia",
    "BR" to "Brazil", "CA" to "Canada", "CL" to "Chile", "CO" to "Colombia", "CR" to "Costa Rica",
    "CZ" to "Czechia", "DK" to "Denmark", "DO" to "Dominican Republic", "EC" to "Ecuador", "EG" to "Egypt",
    "SV" to "El Salvador", "EE" to "Estonia", "FI" to "Finland", "FR" to "France", "DE" to "Germany",
    "GT" to "Guatemala", "HN" to "Honduras", "HK" to "Hong Kong", "HU" to "Hungary", "IS" to "Iceland",
    "IN" to "India", "ID" to "Indonesia", "IE" to "Ireland", "IL" to "Israel", "IT" to "Italy",
    "JP" to "Japan", "KE" to "Kenya", "LU" to "Luxembourg", "MY" to "Malaysia", "MX" to "Mexico",
    "NL" to "Netherlands", "NZ" to "New Zealand", "NI" to "Nicaragua", "NG" to "Nigeria", "NO" to "Norway",
    "PA" to "Panama", "PY" to "Paraguay", "PE" to "Peru", "PH" to "Philippines", "PL" to "Poland",
    "PT" to "Portugal", "RO" to "Romania", "RU" to "Russia", "SA" to "Saudi Arabia", "RS" to "Serbia",
    "SG" to "Singapore", "ZA" to "South Africa", "KR" to "South Korea", "ES" to "Spain", "SE" to "Sweden",
    "CH" to "Switzerland", "TW" to "Taiwan", "TZ" to "Tanzania", "TH" to "Thailand", "TR" to "Turkey",
    "UG" to "Uganda", "UA" to "Ukraine", "AE" to "United Arab Emirates", "GB" to "United Kingdom",
    "US" to "United States", "UY" to "Uruguay", "VN" to "Vietnam", "ZW" to "Zimbabwe",
)

// Time zone to country for the countries above, from tzdata zone.tab, plus common legacy aliases.
private val ZONE_COUNTRIES: Map<String, String> by lazy {
    (
    "Asia/Dubai=AE;America/Argentina/Buenos_Aires=AR;America/Argentina/Cordoba=AR;America/Argentina/Salta=AR;Americ" +
    "a/Argentina/Jujuy=AR;America/Argentina/Tucuman=AR;America/Argentina/Catamarca=AR;America/Argentina/La_Rioja=AR" +
    ";America/Argentina/San_Juan=AR;America/Argentina/Mendoza=AR;America/Argentina/San_Luis=AR;America/Argentina/Ri" +
    "o_Gallegos=AR;America/Argentina/Ushuaia=AR;Europe/Vienna=AT;Australia/Lord_Howe=AU;Antarctica/Macquarie=AU;Aus" +
    "tralia/Hobart=AU;Australia/Melbourne=AU;Australia/Sydney=AU;Australia/Broken_Hill=AU;Australia/Brisbane=AU;Aus" +
    "tralia/Lindeman=AU;Australia/Adelaide=AU;Australia/Darwin=AU;Australia/Perth=AU;Australia/Eucla=AU;Europe/Brus" +
    "sels=BE;America/La_Paz=BO;America/Noronha=BR;America/Belem=BR;America/Fortaleza=BR;America/Recife=BR;America/A" +
    "raguaina=BR;America/Maceio=BR;America/Bahia=BR;America/Sao_Paulo=BR;America/Campo_Grande=BR;America/Cuiaba=BR;" +
    "America/Santarem=BR;America/Porto_Velho=BR;America/Boa_Vista=BR;America/Manaus=BR;America/Eirunepe=BR;America/" +
    "Rio_Branco=BR;America/St_Johns=CA;America/Halifax=CA;America/Glace_Bay=CA;America/Moncton=CA;America/Goose_Bay" +
    "=CA;America/Blanc-Sablon=CA;America/Toronto=CA;America/Iqaluit=CA;America/Atikokan=CA;America/Winnipeg=CA;Amer" +
    "ica/Resolute=CA;America/Rankin_Inlet=CA;America/Regina=CA;America/Swift_Current=CA;America/Edmonton=CA;America" +
    "/Cambridge_Bay=CA;America/Inuvik=CA;America/Vancouver=CA;America/Creston=CA;America/Dawson_Creek=CA;America/Fo" +
    "rt_Nelson=CA;America/Whitehorse=CA;America/Dawson=CA;Europe/Zurich=CH;America/Santiago=CL;America/Coyhaique=CL" +
    ";America/Punta_Arenas=CL;Pacific/Easter=CL;America/Bogota=CO;America/Costa_Rica=CR;Europe/Prague=CZ;Europe/Ber" +
    "lin=DE;Europe/Busingen=DE;Europe/Copenhagen=DK;America/Santo_Domingo=DO;America/Guayaquil=EC;Pacific/Galapagos" +
    "=EC;Europe/Tallinn=EE;Africa/Cairo=EG;Europe/Madrid=ES;Africa/Ceuta=ES;Atlantic/Canary=ES;Europe/Helsinki=FI;E" +
    "urope/Paris=FR;Europe/London=GB;America/Guatemala=GT;Asia/Hong_Kong=HK;America/Tegucigalpa=HN;Europe/Budapest=" +
    "HU;Asia/Jakarta=ID;Asia/Pontianak=ID;Asia/Makassar=ID;Asia/Jayapura=ID;Europe/Dublin=IE;Asia/Jerusalem=IL;Asia" +
    "/Kolkata=IN;Atlantic/Reykjavik=IS;Europe/Rome=IT;Asia/Tokyo=JP;Africa/Nairobi=KE;Asia/Seoul=KR;Europe/Luxembou" +
    "rg=LU;America/Mexico_City=MX;America/Cancun=MX;America/Merida=MX;America/Monterrey=MX;America/Matamoros=MX;Ame" +
    "rica/Chihuahua=MX;America/Ciudad_Juarez=MX;America/Ojinaga=MX;America/Mazatlan=MX;America/Bahia_Banderas=MX;Am" +
    "erica/Hermosillo=MX;America/Tijuana=MX;Asia/Kuala_Lumpur=MY;Asia/Kuching=MY;Africa/Lagos=NG;America/Managua=NI" +
    ";Europe/Amsterdam=NL;Europe/Oslo=NO;Pacific/Auckland=NZ;Pacific/Chatham=NZ;America/Panama=PA;America/Lima=PE;A" +
    "sia/Manila=PH;Europe/Warsaw=PL;Europe/Lisbon=PT;Atlantic/Madeira=PT;Atlantic/Azores=PT;America/Asuncion=PY;Eur" +
    "ope/Bucharest=RO;Europe/Belgrade=RS;Europe/Kaliningrad=RU;Europe/Moscow=RU;Europe/Simferopol=UA;Europe/Kirov=R" +
    "U;Europe/Volgograd=RU;Europe/Astrakhan=RU;Europe/Saratov=RU;Europe/Ulyanovsk=RU;Europe/Samara=RU;Asia/Yekateri" +
    "nburg=RU;Asia/Omsk=RU;Asia/Novosibirsk=RU;Asia/Barnaul=RU;Asia/Tomsk=RU;Asia/Novokuznetsk=RU;Asia/Krasnoyarsk=" +
    "RU;Asia/Irkutsk=RU;Asia/Chita=RU;Asia/Yakutsk=RU;Asia/Khandyga=RU;Asia/Vladivostok=RU;Asia/Ust-Nera=RU;Asia/Ma" +
    "gadan=RU;Asia/Sakhalin=RU;Asia/Srednekolymsk=RU;Asia/Kamchatka=RU;Asia/Anadyr=RU;Asia/Riyadh=SA;Europe/Stockho" +
    "lm=SE;Asia/Singapore=SG;America/El_Salvador=SV;Asia/Bangkok=TH;Europe/Istanbul=TR;Asia/Taipei=TW;Africa/Dar_es" +
    "_Salaam=TZ;Europe/Kyiv=UA;Africa/Kampala=UG;America/New_York=US;America/Detroit=US;America/Kentucky/Louisville" +
    "=US;America/Kentucky/Monticello=US;America/Indiana/Indianapolis=US;America/Indiana/Vincennes=US;America/Indian" +
    "a/Winamac=US;America/Indiana/Marengo=US;America/Indiana/Petersburg=US;America/Indiana/Vevay=US;America/Chicago" +
    "=US;America/Indiana/Tell_City=US;America/Indiana/Knox=US;America/Menominee=US;America/North_Dakota/Center=US;A" +
    "merica/North_Dakota/New_Salem=US;America/North_Dakota/Beulah=US;America/Denver=US;America/Boise=US;America/Pho" +
    "enix=US;America/Los_Angeles=US;America/Anchorage=US;America/Juneau=US;America/Sitka=US;America/Metlakatla=US;A" +
    "merica/Yakutat=US;America/Nome=US;America/Adak=US;Pacific/Honolulu=US;America/Montevideo=UY;Asia/Ho_Chi_Minh=V" +
    "N;Africa/Johannesburg=ZA;Africa/Harare=ZW" +        ";Asia/Calcutta=IN;Europe/Kiev=UA;Asia/Saigon=VN;America/Buenos_Aires=AR;Asia/Istanbul=TR"
    ).split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
}

private fun countryOfZone(zone: String): String? = when {
    zone.startsWith("US/") -> "US"
    zone.startsWith("Canada/") -> "CA"
    else -> ZONE_COUNTRIES[zone]
}

/** The chart region: the user's pick in the plugin settings, or a guess from the system time zone. */
class RegionSetting(
    private val store: EntityStore,
    private val systemInfo: SystemInformationAPI?,
) {

    suspend fun stored(): String =
        (store.get(REGION_KEY) as? JsonPrimitive)?.contentOrNull?.takeIf { it == REGION_GLOBAL || it in CHART_COUNTRIES }
            ?: REGION_AUTO

    suspend fun set(code: String) {
        val value = code.takeIf { it == REGION_GLOBAL || it in CHART_COUNTRIES } ?: REGION_AUTO
        if (value == REGION_AUTO) store.remove(REGION_KEY) else store.put(REGION_KEY, JsonPrimitive(value))
    }

    /** Country code for "Auto", or null when the system gives nothing usable. */
    fun detected(): String? {
        // TODO: Spotube has a Region setting (UserSettings.country) but does not pass it to plugins yet:
        //  SystemInformationAPI.getLocale() is hardcoded to "en-US". Once it returns the user's setting, read the
        //  country from getLocale() first (e.g. "el-GR" -> "GR") and keep the time zone only as the fallback.
        val zone = runCatching { systemInfo?.getTimeZone() }.getOrNull() ?: return null
        return countryOfZone(zone)?.takeIf { it in CHART_COUNTRIES }
    }

    /** ISO code of the charts to show, or [REGION_GLOBAL]. */
    suspend fun resolved(): String = when (val code = stored()) {
        REGION_AUTO -> detected() ?: REGION_GLOBAL
        else -> code
    }
}
