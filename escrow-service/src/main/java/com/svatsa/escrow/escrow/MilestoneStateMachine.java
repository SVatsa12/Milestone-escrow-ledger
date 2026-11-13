package com.svatsa.escrow.escrow;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * State machine for milestone transitions.
 * Enforces the transition rules per AGENTS.md.
 *
 * DRAFT ──fund──▶ FUNDED ──submit──▶ SUBMITTED ──approve──▶ APPROVED ──release──▶ RELEASED
 *   │               │                    │         (auto-release after review window ─┘)
 *   │               │                    └──dispute──▶ DISPUTED ──resolve──▶ RESOLVED
 *   │               └──cancel (after dueAt + grace)──▶ CANCELLED ──refund──▶ REFUNDED
 *   └──cancel──▶ CANCELLED
 *
 * Static analysis: 9 states × 9 states = 81 pairs, of which only 10 are valid transitions.
 */
public class MilestoneStateMachine {

    private static final Map<MilestoneState, Set<MilestoneState>> VALID_TRANSITIONS;

    static {
        Map<MilestoneState, Set<MilestoneState>> map = new EnumMap<>(MilestoneState.class);

        // From DRAFT: only fund or cancel (before funding)
        map.put(MilestoneState.DRAFT, Set.of(
            MilestoneState.FUNDED,
            MilestoneState.CANCELLED
        ));

        // From FUNDED: submit or cancel (after grace period past due date)
        map.put(MilestoneState.FUNDED, Set.of(
            MilestoneState.SUBMITTED,
            MilestoneState.CANCELLED
        ));

        // From SUBMITTED: approve or dispute
        map.put(MilestoneState.SUBMITTED, Set.of(
            MilestoneState.APPROVED,
            MilestoneState.DISPUTED
        ));

        // From APPROVED: release (happens in same tx as approve, but model allows both)
        map.put(MilestoneState.APPROVED, Set.of(
            MilestoneState.RELEASED
        ));

        // From DISPUTED: resolve
        map.put(MilestoneState.DISPUTED, Set.of(
            MilestoneState.RESOLVED
        ));

        // From CANCELLED: refund (happens in same tx as cancel, but model allows both)
        map.put(MilestoneState.CANCELLED, Set.of(
            MilestoneState.REFUNDED
        ));

        // Terminal states: no transitions allowed
        map.put(MilestoneState.RELEASED, Collections.emptySet());
        map.put(MilestoneState.RESOLVED, Collections.emptySet());
        map.put(MilestoneState.REFUNDED, Collections.emptySet());

        VALID_TRANSITIONS = Collections.unmodifiableMap(map);
    }

    /**
     * Checks if a transition from 'from' to 'to' is valid.
     */
    public static boolean isValidTransition(MilestoneState from, MilestoneState to) {
        if (from == null || to == null) return false;
        Set<MilestoneState> allowed = VALID_TRANSITIONS.getOrDefault(from, Set.of());
        return allowed.contains(to);
    }

    /**
     * Returns all valid target states from the given state.
     */
    public static Set<MilestoneState> validTransitions(MilestoneState from) {
        if (from == null) return Set.of();
        return VALID_TRANSITIONS.getOrDefault(from, Set.of());
    }

    /**
     * Returns all state pairs (81 total).
     * Used for exhaustive testing.
     */
    public static Set<StatePair> allStatePairs() {
        Set<StatePair> pairs = new HashSet<>();
        for (MilestoneState from : MilestoneState.values()) {
            for (MilestoneState to : MilestoneState.values()) {
                pairs.add(new StatePair(from, to));
            }
        }
        return pairs;
    }

    /**
     * Record for a state pair (from, to) used in testing.
     */
    public record StatePair(MilestoneState from, MilestoneState to) {
        public boolean isValid() {
            return MilestoneStateMachine.isValidTransition(from, to);
        }
    }
}
