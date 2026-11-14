package com.svatsa.escrow.escrow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for MilestoneStateMachine.
 *
 * Tests all 81 state pairs (9x9) to verify the state transition rules.
 * Only 10 transitions are valid; the rest must be rejected.
 */
@DisplayName("MilestoneStateMachine Unit Test")
class MilestoneStateMachineTest {

    @Test
    @DisplayName("should have exactly 81 total state pairs")
    void testAllStatePairs() {
        var allPairs = MilestoneStateMachine.allStatePairs();
        assertThat(allPairs).hasSize(81);
    }

    @Test
    @DisplayName("should have exactly 9 valid transitions")
    void testValidTransitionCount() {
        var allPairs = MilestoneStateMachine.allStatePairs();
        long validCount = allPairs.stream()
            .filter(MilestoneStateMachine.StatePair::isValid)
            .count();

        assertThat(validCount).isEqualTo(9);
    }

    @Test
    @DisplayName("DRAFT can transition to FUNDED and CANCELLED")
    void testDraftTransitions() {
        assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.DRAFT, MilestoneState.FUNDED))
            .isTrue();
        assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.DRAFT, MilestoneState.CANCELLED))
            .isTrue();

        // DRAFT cannot transition to other states
        assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.DRAFT, MilestoneState.SUBMITTED))
            .isFalse();
        assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.DRAFT, MilestoneState.APPROVED))
            .isFalse();
        assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.DRAFT, MilestoneState.RELEASED))
            .isFalse();
    }

    @Test
    @DisplayName("FUNDED can transition to SUBMITTED and CANCELLED")
    void testFundedTransitions() {
        assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.FUNDED, MilestoneState.SUBMITTED))
            .isTrue();
        assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.FUNDED, MilestoneState.CANCELLED))
            .isTrue();

        // FUNDED cannot transition to other states
        assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.FUNDED, MilestoneState.DRAFT))
            .isFalse();
        assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.FUNDED, MilestoneState.APPROVED))
            .isFalse();
    }

    @Test
    @DisplayName("SUBMITTED can transition to APPROVED and DISPUTED")
    void testSubmittedTransitions() {
        assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.SUBMITTED, MilestoneState.APPROVED))
            .isTrue();
        assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.SUBMITTED, MilestoneState.DISPUTED))
            .isTrue();

        // SUBMITTED cannot transition to other states
        assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.SUBMITTED, MilestoneState.FUNDED))
            .isFalse();
        assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.SUBMITTED, MilestoneState.RELEASED))
            .isFalse();
    }

    @Test
    @DisplayName("APPROVED can transition only to RELEASED")
    void testApprovedTransitions() {
        assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.APPROVED, MilestoneState.RELEASED))
            .isTrue();

        // APPROVED cannot transition to other states
        assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.APPROVED, MilestoneState.SUBMITTED))
            .isFalse();
        assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.APPROVED, MilestoneState.DISPUTED))
            .isFalse();
    }

    @Test
    @DisplayName("DISPUTED can transition only to RESOLVED")
    void testDisputedTransitions() {
        assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.DISPUTED, MilestoneState.RESOLVED))
            .isTrue();

        // DISPUTED cannot transition to other states
        assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.DISPUTED, MilestoneState.SUBMITTED))
            .isFalse();
        assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.DISPUTED, MilestoneState.CANCELLED))
            .isFalse();
    }

    @Test
    @DisplayName("CANCELLED can transition only to REFUNDED")
    void testCancelledTransitions() {
        assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.CANCELLED, MilestoneState.REFUNDED))
            .isTrue();

        // CANCELLED cannot transition to other states
        assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.CANCELLED, MilestoneState.FUNDED))
            .isFalse();
        assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.CANCELLED, MilestoneState.DISPUTED))
            .isFalse();
    }

    @Test
    @DisplayName("terminal states (RELEASED, RESOLVED, REFUNDED) have no valid transitions")
    void testTerminalStates() {
        // RELEASED is terminal
        for (MilestoneState state : MilestoneState.values()) {
            assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.RELEASED, state))
                .isFalse();
        }

        // RESOLVED is terminal
        for (MilestoneState state : MilestoneState.values()) {
            assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.RESOLVED, state))
                .isFalse();
        }

        // REFUNDED is terminal
        for (MilestoneState state : MilestoneState.values()) {
            assertThat(MilestoneStateMachine.isValidTransition(MilestoneState.REFUNDED, state))
                .isFalse();
        }
    }

    @Test
    @DisplayName("validTransitions returns correct set for each state")
    void testValidTransitionsSet() {
        assertThat(MilestoneStateMachine.validTransitions(MilestoneState.DRAFT))
            .containsExactlyInAnyOrder(MilestoneState.FUNDED, MilestoneState.CANCELLED);

        assertThat(MilestoneStateMachine.validTransitions(MilestoneState.FUNDED))
            .containsExactlyInAnyOrder(MilestoneState.SUBMITTED, MilestoneState.CANCELLED);

        assertThat(MilestoneStateMachine.validTransitions(MilestoneState.SUBMITTED))
            .containsExactlyInAnyOrder(MilestoneState.APPROVED, MilestoneState.DISPUTED);

        assertThat(MilestoneStateMachine.validTransitions(MilestoneState.APPROVED))
            .containsExactlyInAnyOrder(MilestoneState.RELEASED);

        assertThat(MilestoneStateMachine.validTransitions(MilestoneState.DISPUTED))
            .containsExactlyInAnyOrder(MilestoneState.RESOLVED);

        assertThat(MilestoneStateMachine.validTransitions(MilestoneState.CANCELLED))
            .containsExactlyInAnyOrder(MilestoneState.REFUNDED);

        assertThat(MilestoneStateMachine.validTransitions(MilestoneState.RELEASED))
            .isEmpty();

        assertThat(MilestoneStateMachine.validTransitions(MilestoneState.RESOLVED))
            .isEmpty();

        assertThat(MilestoneStateMachine.validTransitions(MilestoneState.REFUNDED))
            .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RELEASED", "RESOLVED", "REFUNDED"})
    @DisplayName("terminal states reject all self-transitions")
    void testTerminalStateSelfTransitions(String stateName) {
        MilestoneState state = MilestoneState.valueOf(stateName);
        assertThat(MilestoneStateMachine.isValidTransition(state, state))
            .isFalse();
    }

    /**
     * P5: every one of the 81 (from, to) pairs is allowed if and only if it is in the AGENTS.md
     * state diagram. The expected set is written out here independently of the implementation's
     * table, so the test can't just agree with itself.
     */
    @Test
    @DisplayName("all 81 pairs: allowed exactly when the AGENTS.md diagram allows them")
    void everyPairMatchesTheAgentsDiagram() {
        java.util.Set<String> diagram = java.util.Set.of(
            "DRAFT->FUNDED", "DRAFT->CANCELLED",
            "FUNDED->SUBMITTED", "FUNDED->CANCELLED",
            "SUBMITTED->APPROVED", "SUBMITTED->DISPUTED",
            "APPROVED->RELEASED",
            "DISPUTED->RESOLVED",
            "CANCELLED->REFUNDED");
        int checked = 0;
        for (MilestoneState from : MilestoneState.values()) {
            for (MilestoneState to : MilestoneState.values()) {
                assertThat(MilestoneStateMachine.isValidTransition(from, to))
                    .as("%s -> %s", from, to)
                    .isEqualTo(diagram.contains(from + "->" + to));
                checked++;
            }
        }
        assertThat(checked).isEqualTo(81);
    }
}
