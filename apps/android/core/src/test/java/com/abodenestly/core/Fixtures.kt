package com.abodenestly.core

import com.abodenestly.contract.models.Activity
import com.abodenestly.contract.models.Household
import com.abodenestly.contract.models.Invite
import com.abodenestly.contract.models.Member
import com.abodenestly.contract.models.Profile
import com.abodenestly.core.api.MemberProfile
import com.abodenestly.core.api.MemberRow
import com.abodenestly.core.auth.AuthSession
import com.abodenestly.core.auth.LandingHousehold
import com.abodenestly.core.auth.Membership
import java.time.Instant
import java.time.OffsetDateTime
import com.abodenestly.sync.SyncEngine
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Records what the screen asked for (TESTING §7): counts `kick()` calls. */
class FakeSyncEngine : SyncEngine {
    val kicks = AtomicInteger()
    override fun kick() {
        kicks.incrementAndGet()
    }
}

object Fixtures {
    val userId: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000aa")
    val session = AuthSession(userId, "ada@example.com")

    fun id(n: Int): UUID = UUID.fromString("00000000-0000-4000-8000-%012d".format(n))

    fun profile(name: String = "Ada") = Profile(userId, name, null, OffsetDateTime.now(), OffsetDateTime.now())

    fun household(n: Int, name: String = "Test Home") = Household(
        id(n), name, userId, moduleShopping = true, modulePantry = true, moduleMaintenance = true,
        currency = "USD", createdAt = OffsetDateTime.now(), updatedAt = OffsetDateTime.now(), deletedAt = null, syncSeq = 1,
    )

    fun membership(
        n: Int,
        joinedDay: Long = n.toLong(),
        shopping: Boolean = true,
        pantry: Boolean = true,
        maintenance: Boolean = true,
    ) = Membership(id(n), Instant.EPOCH.plusSeconds(joinedDay * 86_400), LandingHousehold(id(n), shopping, pantry, maintenance))

    /** 19 Nov 2026, noon UTC: the same day in every zone from UTC-11 to UTC+11. */
    val expiry: OffsetDateTime = OffsetDateTime.parse("2026-11-19T12:00:00Z")

    fun invite(
        code: String = "abc123def456",
        id: UUID = UUID.randomUUID(),
        uses: Int = 0,
        maxUses: Int = 10,
        role: Invite.Role = Invite.Role.member,
        householdId: UUID = id(1),
    ) = Invite(
        id, householdId, code, role, userId, expiry, maxUses, uses,
        createdAt = OffsetDateTime.now(), updatedAt = OffsetDateTime.now(), deletedAt = null, syncSeq = 1,
    )

    /** An active member of [householdId]; `name = null` models a profile RLS hides. User ids are `id(n)`. */
    fun member(
        n: Int,
        name: String? = "Member $n",
        role: Member.Role = Member.Role.member,
        householdId: UUID = id(1),
    ) = MemberRow(
        Member(householdId, id(n), role, OffsetDateTime.now(), null, OffsetDateTime.now(), syncSeq = 1),
        name?.let { MemberProfile(id(n), it) },
    )

    /** A fixed "now" for the feed window, so the 30-day cut-off is exact. */
    val homeNow: OffsetDateTime = OffsetDateTime.parse("2026-11-17T12:00:00Z")

    /** A feed row created [ago] before [homeNow]. */
    fun activity(
        summary: String,
        ago: java.time.Duration,
        householdId: UUID = id(1),
        id: UUID = UUID.randomUUID(),
        module: Activity.Module = Activity.Module.shopping,
        deleted: Boolean = false,
    ): Activity {
        val at = homeNow.minus(ago)
        return Activity(
            id, householdId, actorId = null, module = module, entity = "list_item", entityId = UUID.randomUUID(),
            action = "checked", summary = summary, createdAt = at, updatedAt = at, deletedAt = if (deleted) at else null, syncSeq = 1,
        )
    }
}
