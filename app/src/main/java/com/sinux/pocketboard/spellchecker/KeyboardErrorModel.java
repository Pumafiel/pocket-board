package com.sinux.pocketboard.spellchecker;

import java.util.HashMap;
import java.util.Map;

public final class KeyboardErrorModel {

    /*
     * Lower cost = more likely typing error.
     *
     * 0 = exact character
     * 1 = direct horizontal/near key
     * 2 = diagonal/adjacent key
     * 3 = plausible but farther key
     * 4 = unknown/far substitution
     * 5 = deliberately unavailable / invalid
     */
    private static final int COST_EXACT = 0;
    private static final int COST_NEIGHBOR = 1;
    private static final int COST_DIAGONAL = 2;
    private static final int COST_NEAR = 3;
    private static final int COST_FAR = 4;
    private static final int COST_UNKNOWN = 5;

    private static final Map<Character, Map<Character, Integer>>
            COSTS =
            new HashMap<>();

    static {

        /*
         * --------------------------------------------------------
         * QWERTY rows
         * --------------------------------------------------------
         *
         * Horizontal neighbours are the strongest physical
         * keyboard relationship.
         */

        addRow("qwertyuiop");
        addRow("asdfghjkl");
        addRow("zxcvbnm");

        /*
         * --------------------------------------------------------
         * Upper/lower row diagonals
         * --------------------------------------------------------
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
         * --------------------------------------------------------
         * Home row -> bottom row
         * --------------------------------------------------------
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
         * --------------------------------------------------------
         * One-step extended relationships
         * --------------------------------------------------------
         *
         * These are intentionally weaker than immediate neighbours.
         *
         * They help rank candidates without allowing arbitrary
         * characters to receive the same score as a real key error.
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
            return COST_FAR;
        }

        Integer cost =
                neighbours.get(second);

        if (cost != null) {
            return cost;
        }

        /*
         * Unknown relationships are intentionally more expensive
         * than all known physical relationships.
         */
        return COST_FAR;
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
     * There is no physical key involved in an insertion/deletion,
     * so these methods deliberately return a neutral typing-error
     * cost.
     *
     * The CorrectionEngine applies the actual base insertion/deletion
     * cost and uses these only when it has a reason to distinguish
     * repeated-character errors.
     */

    public static int getInsertionCost(
            char character,
            String languageTag) {

        return COST_FAR;
    }

    public static int getDeletionCost(
            char character,
            String languageTag) {

        return COST_FAR;
    }

    /*
     * ============================================================
     * TRANSPOSITION
     * ============================================================
     *
     * Adjacent letters that are physically close are more likely
     * to be transposed by fast typing.
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

        /*
         * A transposition is still a plausible typing error even
         * when the two characters are not adjacent on the keyboard,
         * but it should not be cheaper than a real physical
         * neighbour relationship.
         */
        return COST_NEAR;
    }

    /*
     * ============================================================
     * REPEATED CHARACTER
     * ============================================================
     *
     * Double letters are a very common typing error:
     *
     *     helo  -> hello
     *     comming -> coming
     */

    public static int getRepetitionCost(
            char character,
            String languageTag) {

        return COST_NEIGHBOR;
    }

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
