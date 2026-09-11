package com.sinux.pocketboard.spellchecker;

import java.util.HashMap;
import java.util.Map;

public final class KeyboardErrorModel {

    /*
     * Lower cost = more likely typing error.
     *
     * 0 = exact character
     * 1 = direct horizontal neighbour
     * 2 = diagonal / adjacent key
     * 3 = nearby but weaker physical relationship
     * 5 = unknown / no keyboard evidence
     *
     * IMPORTANT:
     *
     * There is intentionally no COST_FAR = 4.
     *
     * CorrectionEngine uses getUnknownCost() as the boundary
     * between real keyboard evidence and an unrelated substitution.
     *
     * Therefore an unrelated character MUST NOT return a value
     * below COST_UNKNOWN.
     */

    private static final int COST_EXACT = 0;
    private static final int COST_NEIGHBOR = 1;
    private static final int COST_DIAGONAL = 2;
    private static final int COST_NEAR = 3;
    private static final int COST_UNKNOWN = 5;

    private static final Map<Character, Map<Character, Integer>>
            COSTS =
            new HashMap<>();

    static {

        /*
         * ========================================================
         * QWERTY ROWS
         * ========================================================
         *
         * Horizontal neighbours are the strongest physical
         * keyboard relationship.
         */

        addRow("qwertyuiop");
        addRow("asdfghjkl");
        addRow("zxcvbnm");

        /*
         * ========================================================
         * UPPER / HOME ROW DIAGONALS
         * ========================================================
         */

        addDiagonal('q', 'a');

        addDiagonal('w', 'a');
        addDiagonal('w', 's');

        addDiagonal('e', 's');
        addDiagonal('e', 'd');

        addDiagonal('r', 'd');
        addDiagonal('r', 'f');

        addDiagonal('t', 'f');
        addDiagonal('t', 'g');

        addDiagonal('y', 'g');
        addDiagonal('y', 'h');

        addDiagonal('u', 'h');
        addDiagonal('u', 'j');

        addDiagonal('i', 'j');
        addDiagonal('i', 'k');

        addDiagonal('o', 'k');
        addDiagonal('o', 'l');

        addDiagonal('p', 'l');

        /*
         * ========================================================
         * HOME ROW / BOTTOM ROW DIAGONALS
         * ========================================================
         */

        addDiagonal('a', 'z');
        addDiagonal('a', 'x');

        addDiagonal('s', 'z');
        addDiagonal('s', 'x');
        addDiagonal('s', 'c');

        addDiagonal('d', 'x');
        addDiagonal('d', 'c');
        addDiagonal('d', 'v');

        addDiagonal('f', 'c');
        addDiagonal('f', 'v');
        addDiagonal('f', 'b');

        addDiagonal('g', 'v');
        addDiagonal('g', 'b');
        addDiagonal('g', 'n');

        addDiagonal('h', 'b');
        addDiagonal('h', 'n');
        addDiagonal('h', 'm');

        addDiagonal('j', 'n');
        addDiagonal('j', 'm');

        addDiagonal('k', 'm');

        /*
         * ========================================================
         * WEAKER NEARBY RELATIONSHIPS
         * ========================================================
         *
         * These are weaker than immediate neighbours/diagonals.
         *
         * They can help distinguish a plausible keyboard typo from
         * a completely unrelated substitution, but they must never
         * become as strong as an actual adjacent key.
         */

        addNear('q', 's');

        addNear('w', 'd');

        addNear('e', 'f');

        addNear('r', 'g');

        addNear('t', 'h');

        addNear('y', 'j');

        addNear('u', 'k');

        addNear('i', 'l');

        addNear('a', 'x');

        addNear('s', 'v');

        addNear('d', 'b');

        addNear('f', 'n');

        addNear('g', 'm');

        addNear('z', 'c');

        addNear('x', 'v');

        addNear('c', 'b');

        addNear('v', 'n');

        addNear('b', 'm');
    }

    private KeyboardErrorModel() {
    }

    /*
     * ============================================================
     * SUBSTITUTION
     * ============================================================
     */

    public static int getSubstitutionCost(
            char typed,
            char candidate,
            String languageTag) {

        char first =
                Character.toLowerCase(
                        typed
                );

        char second =
                Character.toLowerCase(
                        candidate
                );

        if (first == second) {
            return COST_EXACT;
        }

        Map<Character, Integer> neighbours =
                COSTS.get(first);

        if (neighbours == null) {
            return COST_UNKNOWN;
        }

        Integer cost =
                neighbours.get(second);

        if (cost != null) {
            return cost;
        }

        /*
         * There is no physical relationship between these keys.
         *
         * This MUST return COST_UNKNOWN rather than a lower value,
         * otherwise CorrectionEngine would incorrectly classify
         * arbitrary substitutions as keyboard evidence.
         */
        return COST_UNKNOWN;
    }

    /*
     * ============================================================
     * NEIGHBOUR TESTS
     * ============================================================
     */

    public static boolean areKeyboardNeighbors(
            char first,
            char second) {

        int cost =
                getSubstitutionCost(
                        first,
                        second,
                        null
                );

        return cost <= COST_DIAGONAL;
    }

    public static boolean areDirectNeighbors(
            char first,
            char second) {

        return getSubstitutionCost(
                first,
                second,
                null
        ) == COST_NEIGHBOR;
    }

    /*
     * ============================================================
     * INSERTION / DELETION
     * ============================================================
     *
     * There is no direct physical-key substitution involved in
     * insertion/deletion.
     *
     * CorrectionEngine owns the actual insertion/deletion cost.
     *
     * These methods therefore deliberately return UNKNOWN.
     */

    public static int getInsertionCost(
            char character,
            String languageTag) {

        return COST_UNKNOWN;
    }

    public static int getDeletionCost(
            char character,
            String languageTag) {

        return COST_UNKNOWN;
    }

    /*
     * ============================================================
     * TRANSPOSITION
     * ============================================================
     *
     * Adjacent characters that are physically close are more
     * plausible transposition errors.
     *
     * This method still provides a weak physical signal for a
     * transposition, even when the two keys are not direct
     * neighbours.
     */

    public static int getTranspositionCost(
            char first,
            char second,
            String languageTag) {

        if (first == second) {
            return COST_EXACT;
        }

        int substitutionCost =
                getSubstitutionCost(
                        first,
                        second,
                        languageTag
                );

        if (substitutionCost ==
                COST_NEIGHBOR) {

            return COST_NEIGHBOR;
        }

        if (substitutionCost <=
                COST_DIAGONAL) {

            return COST_DIAGONAL;
        }

        if (substitutionCost ==
                COST_NEAR) {

            return COST_NEAR;
        }

        /*
         * No physical relationship.
         *
         * Return UNKNOWN so CorrectionEngine can fall back to its
         * normal transposition cost.
         */
        return COST_UNKNOWN;
    }

    /*
     * ============================================================
     * REPEATED CHARACTER
     * ============================================================
     *
     * Repeated-character mistakes are handled primarily by
     * CorrectionEngine's edit model.
     *
     * This value represents a common typing-error signal rather
     * than a physical keyboard distance.
     */

    public static int getRepetitionCost(
            char character,
            String languageTag) {

        return COST_NEIGHBOR;
    }

    /*
     * ============================================================
     * UNKNOWN COST
     * ============================================================
     *
     * CorrectionEngine uses this as the boundary:
     *
     *     cost < UNKNOWN
     *
     * means real keyboard/language evidence.
     *
     *     cost >= UNKNOWN
     *
     * means no evidence.
     */

    public static int getUnknownCost() {
        return COST_UNKNOWN;
    }

    /*
     * ============================================================
     * KEYBOARD BUILDING
     * ============================================================
     */

    private static void addRow(
            String row) {

        for (int i = 0;
             i < row.length();
             i++) {

            char current =
                    row.charAt(i);

            if (i > 0) {

                addSymmetricCost(
                        current,
                        row.charAt(i - 1),
                        COST_NEIGHBOR
                );
            }

            if (i + 1 <
                    row.length()) {

                addSymmetricCost(
                        current,
                        row.charAt(i + 1),
                        COST_NEIGHBOR
                );
            }
        }
    }

    private static void addDiagonal(
            char first,
            char second) {

        addSymmetricCost(
                first,
                second,
                COST_DIAGONAL
        );
    }

    private static void addNear(
            char first,
            char second) {

        addSymmetricCost(
                first,
                second,
                COST_NEAR
        );
    }

    private static void addSymmetricCost(
            char first,
            char second,
            int cost) {

        char a =
                Character.toLowerCase(
                        first
                );

        char b =
                Character.toLowerCase(
                        second
                );

        if (a == b) {
            return;
        }

        COSTS
                .computeIfAbsent(
                        a,
                        ignored ->
                                new HashMap<>()
                )
                .merge(
                        b,
                        cost,
                        Math::min
                );

        COSTS
                .computeIfAbsent(
                        b,
                        ignored ->
                                new HashMap<>()
                )
                .merge(
                        a,
                        cost,
                        Math::min
                );
    }
}
