package com.abodenestly.core

import com.abodenestly.contract.models.Profile
import com.abodenestly.core.auth.AuthSession
import com.abodenestly.core.auth.LandingHousehold
import com.abodenestly.core.auth.Membership
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

object Fixtures {
    val userId: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000aa")
    val session = AuthSession(userId, "ada@example.com")

    fun id(n: Int): UUID = UUID.fromString("00000000-0000-4000-8000-%012d".format(n))

    fun profile(name: String = "Ada") = Profile(userId, name, null, OffsetDateTime.now(), OffsetDateTime.now())

    fun membership(
        n: Int,
        joinedDay: Long = n.toLong(),
        shopping: Boolean = true,
        pantry: Boolean = true,
        maintenance: Boolean = true,
    ) = Membership(id(n), Instant.EPOCH.plusSeconds(joinedDay * 86_400), LandingHousehold(id(n), shopping, pantry, maintenance))
}
