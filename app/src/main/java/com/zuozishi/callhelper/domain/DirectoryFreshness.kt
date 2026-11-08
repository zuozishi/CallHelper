package com.zuozishi.callhelper.domain

/** Only a known successful update can be described as more than seven days old. */
internal fun isDirectoryUpdateOverdue(updatedAt: Long, now: Long): Boolean =
    updatedAt > 0 && now > updatedAt && now - updatedAt > 7L * 24 * 60 * 60 * 1000
