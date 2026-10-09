package io.sdkman.state.security

import java.util.UUID

const val BCRYPT_COST = 12

const val ROLE_ADMIN = "admin"

const val ROLE_VENDOR = "vendor"

const val ROLE_COMMUNITY = "community"

val COMMUNITY_VENDOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
