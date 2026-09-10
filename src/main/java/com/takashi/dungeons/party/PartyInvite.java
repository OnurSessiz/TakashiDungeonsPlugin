package com.takashi.dungeons.party;

import java.util.UUID;

/**
 * One outstanding "join my party".
 *
 * <p>The invite points at the {@link Party} object rather than at its id, so an invite to a party
 * that has since been disbanded is recognisable by identity — the manager checks that the party is
 * still live before honouring it. Holding an id would mean the same number could, in principle,
 * name something else later; holding the object means a dead party is simply not in the registry.
 *
 * @param party     the party being joined
 * @param inviter   who sent it — the leader at the time, which is not necessarily the leader now
 * @param target    who was invited
 * @param expiresAt wall-clock millis after which the invite is no longer valid
 */
public record PartyInvite(Party party, UUID inviter, UUID target, long expiresAt) {

    public boolean isExpired() {
        return System.currentTimeMillis() >= expiresAt;
    }

    /** Millis left, never negative — for the "expires in ..." line. */
    public long remainingMillis() {
        return Math.max(0L, expiresAt - System.currentTimeMillis());
    }
}
