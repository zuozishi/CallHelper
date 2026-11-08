package com.zuozishi.callhelper.domain

data class Personnel(
    val id: String, val phone: String, val name: String, val post: String,
    val topOrgName: String, val fullOrgName: String,
    val avatarUrl: String = "",
    val rowKey: String = id,
)

object AvatarAddress {
    fun normalize(value: String): String {
        val text = value.trim()
        return text.takeIf {
            it.length <= 2000 && runCatching { val uri = java.net.URI(it); uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null }.getOrDefault(false)
        }.orEmpty()
    }
}

object PhoneKey {
    fun normalize(value: String): String {
        val compact = value.trim().replace(Regex("[\\s()（）.\\-]"), "")
        val national = when {
            compact.startsWith("+86") && compact.length == 14 -> compact.drop(3)
            compact.startsWith("0086") && compact.length == 15 -> compact.drop(4)
            else -> compact
        }
        return national.takeIf { it.matches(Regex("\\+?[0-9]{3,20}")) } ?: ""
    }
}

class DirectorySnapshot(val people: List<Personnel>) {
    val organizations = OrganizationIndex(people)
    private val byPhone = people.groupBy { PhoneKey.normalize(it.phone) }
    val unitCount = people.map { it.topOrgName }.filter { it.isNotBlank() }.distinct().size
    val duplicatePhoneGroups = byPhone.count { it.key.isNotEmpty() && it.value.size > 1 }
    fun lookup(phone: String): List<Personnel> = PhoneKey.normalize(phone).takeIf { it.isNotEmpty() }?.let { byPhone[it] }.orEmpty()
    fun search(query: String): List<Personnel> {
        val q = query.trim()
        if (q.isEmpty()) return people
        val number = PhoneKey.normalize(q)
        return people.filter { p ->
            p.name.contains(q, true) || p.post.contains(q, true) || p.topOrgName.contains(q, true) ||
                p.fullOrgName.contains(q, true) || p.phone.contains(q) ||
                (number.isNotEmpty() && PhoneKey.normalize(p.phone).contains(number))
        }
    }
}
