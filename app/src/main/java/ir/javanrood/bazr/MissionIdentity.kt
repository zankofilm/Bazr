package ir.javanrood.bazr

import org.json.JSONObject

private val genericMissionTitles = setOf("", "ماموریت بازرسی", "مأموریت بازرسی", "بازرسی", "دستگاه")

fun missionOrganizationName(mission: MissionEntity): String {
    val root = runCatching { JSONObject(mission.payload) }.getOrNull()
    return missionOrganizationName(root, mission.title)
}

fun missionOrganizationName(root: JSONObject?, fallbackTitle: String = ""): String {
    if (root != null) {
        val directKeys = listOf(
            "orgName", "organizationName", "organization_name", "orgTitle", "org_title",
            "officeName", "agencyName", "departmentName", "deviceName"
        )
        directKeys.forEach { key ->
            val value = root.optString(key).trim()
            if (value.isNotBlank() && value !in genericMissionTitles) return value
        }
        listOf("organization", "org", "office", "agency", "department").forEach { key ->
            val obj = root.optJSONObject(key) ?: return@forEach
            val value = obj.optString("name", obj.optString("title")).trim()
            if (value.isNotBlank() && value !in genericMissionTitles) return value
        }
        val title = root.optString("title").trim()
        if (title.isNotBlank() && title !in genericMissionTitles) return title
    }
    val cleanFallback = fallbackTitle.trim()
    return if (cleanFallback.isNotBlank() && cleanFallback !in genericMissionTitles) cleanFallback else "نام اداره ثبت نشده"
}
