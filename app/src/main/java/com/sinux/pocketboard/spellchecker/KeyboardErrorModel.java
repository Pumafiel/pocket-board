package com.sinux.pocketboard.spellchecker;

import java.util.HashMap;
import java.util.Map;

public final class KeyboardErrorModel {

    private static final int COST_EXACT = 0;
    private static final int COST_NEIGHBOR = 1;
    private static final int COST_DIAGONAL = 2;
    private static final int COST_FAR = 4;
    private static final int COST_UNKNOWN = 5;

    private static final Map<Character, Map<Character, Integer>>
            COSTS =
            new HashMap<>();

    static {
        addRow("qwertyuiop");
        addRow("asdfghjkl");
        addRow("zxcvbnm");

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
    }

    private KeyboardErrorModel() {
    }

    public static int getSubstitutionCost(
            char typed,
            char candidate,
            String languageTag) {

        char a =
                Character.toLowerCase(
                        typed
                );

        char b =
                Character.toLowerCase(
                        candidate
                );

        if (a == b) {
            return COST_EXACT;
        }

        Map<Character, Integer> neighbors =
                COSTS.get(a);

        if (neighbors != null) {
            Integer cost =
                    neighbors.get(b);

            if (cost != null) {
                return cost;
            }
        }

        return COST_FAR;
    }

    public static boolean areKeyboardNeighbors(
            char first,
            char second) {

        return getSubstitutionCost(
                first,
                second,
                null
        ) <= COST_DIAGONAL;
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

    public static int getTranspositionCost(
            char first,
            char second,
            String languageTag) {

        if (areDirectNeighbors(
                first,
                second
        )) {
            return COST_NEIGHBOR;
        }

        return COST_DIAGONAL;
    }

    public static int getRepetitionCost(
            char character,
            String languageTag) {

        return COST_NEIGHBOR;
    }

    public static int getUnknownCost() {
        return COST_UNKNOWN;
    }

    private static void addRow(
            String row) {

        for (
                int i = 0;
                i < row.length();
                i++
        ) {
            char current =
                    row.charAt(i);

            if (i > 0) {
                addSymmetricCost(
                        current,
                        row.charAt(i - 1),
                        COST_NEIGHBOR
                );
            }

            if (i + 1 < row.length()) {
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
