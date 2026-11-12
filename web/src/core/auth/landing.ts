import type { Household, Member } from '@abodenestly/contract';

export type LandingHousehold = Pick<
  Household,
  'id' | 'module_shopping' | 'module_pantry' | 'module_maintenance'
>;

/** An active (not left, household not deleted) membership with its household flags. */
export type Membership = Pick<Member, 'household_id' | 'joined_at'> & {
  household: LandingHousehold;
};

/** First enabled module in the order shopping → pantry → maintenance; Home when all are off. */
export function startTab(household: LandingHousehold): string {
  if (household.module_shopping) return '/shopping';
  if (household.module_pantry) return '/pantry';
  if (household.module_maintenance) return '/maintenance';
  return '/home';
}

/** Only same-origin app paths; never the sign-in/callback screens (would loop) or `//host`. */
export function safeNext(next: string | null | undefined): string | null {
  if (!next || !next.startsWith('/') || next.startsWith('//') || next.includes('\\')) return null;
  const pathname = next.split(/[?#]/)[0] ?? '';
  if (pathname === '/signin' || pathname === '/auth' || pathname.startsWith('/auth/')) return null;
  return next;
}

/** The last used household if still active, else the earliest joined. */
export function pickHousehold(memberships: Membership[], lastHouseholdId: string | null): Membership | null {
  const last = memberships.find((m) => m.household_id === lastHouseholdId);
  if (last) return last;
  return [...memberships].sort((a, b) => a.joined_at.localeCompare(b.joined_at))[0] ?? null;
}

export function landingPath(input: {
  memberships: Membership[];
  lastHouseholdId: string | null;
  next: string | null;
}): string {
  const { memberships, lastHouseholdId, next } = input;
  const chosen = pickHousehold(memberships, lastHouseholdId);
  if (!chosen) {
    // An invite link must survive sign-in for someone with no household yet; other targets need one.
    const target = safeNext(next);
    return target?.startsWith('/join/') ? target : '/welcome';
  }
  return safeNext(next) ?? startTab(chosen.household);
}
