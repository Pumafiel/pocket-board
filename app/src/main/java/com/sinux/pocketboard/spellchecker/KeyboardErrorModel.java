package com.sinux.pocketboard.spellchecker;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class KeyboardErrorModel {

    private static final int COST_EXACT = 0;

    private static final int COST_NEIGHBOR = 1;

    private static final int COST_DIAGONAL = 2;

    private static final int COST_FAR = 4;

    private static final int COST_UNKNOWN = 5;

    private static final Map<Character, Map<Character, Integer>>
            QWERTY_COSTS =
            new HashMap<>();

    static {

        /*
         * ---------------------------------------------------------
         * QWERTY
         * ---------------------------------------------------------
         *
         * Se modela la proximidad física aproximada de las teclas.
         *
         * Las distancias son deliberadamente simples:
         *
         *   0 = misma tecla
         *   1 = vecina directa
         *   2 = vecina diagonal
         *   4 = tecla alejada
         *   5 = relación desconocida
         *
         * El motor de corrección utilizará estos valores como parte
         * del scoring final.
         */

        addRow(
                "qwertyuiop"
        );

        addRow(
                "asdfghjkl"
        );

        addRow(
                "zxcvbnm"
        );

        /*
         * Vecinos adicionales entre filas.
         *
         * Se agregan explícitamente las relaciones más importantes
         * para evitar depender únicamente de la distancia de índice.
         */
        addNeighbors(
                "q",
                "w"
        );

        addNeighbors(
                "w",
                "qe"
        );

        addNeighbors(
                "e",
                "wr"
        );

        addNeighbors(
                "r",
                "et"
        );

        addNeighbors(
                "t",
                "ry"
        );

        addNeighbors(
                "y",
                "tu"
        );

        addNeighbors(
                "u",
                "yi"
        );

        addNeighbors(
                "i",
                "uo"
        );

        addNeighbors(
                "o",
                "ip"
        );

        addNeighbors(
                "p",
                "o"
        );

        addNeighbors(
                "a",
                "s"
        );

        addNeighbors(
                "s",
                "ad"
        );

        addNeighbors(
                "d",
                "sf"
        );

        addNeighbors(
                "f",
                "dg"
        );

        addNeighbors(
                "g",
                "fh"
        );

        addNeighbors(
                "h",
                "gj"
        );

        addNeighbors(
                "j",
                "hk"
        );

        addNeighbors(
                "k",
                "jl"
        );

        addNeighbors(
                "l",
                "k"
        );

        addNeighbors(
                "z",
                "x"
        );

        addNeighbors(
                "x",
                "zc"
        );

        addNeighbors(
                "c",
                "xv"
        );

        addNeighbors(
                "v",
                "cb"
        );

        addNeighbors(
                "b",
                "vn"
        );

        addNeighbors(
                "n",
                "bm"
        );

        addNeighbors(
                "m",
                "n"
        );

        /*
         * Relaciones diagonales entre filas.
         */

        addDiagonal(
                "q",
                "a"
        );

        addDiagonal(
                "w",
                "a"
        );

        addDiagonal(
                "w",
                "s"
        );

        addDiagonal(
                "e",
                "s"
        );

        addDiagonal(
                "e",
                "d"
        );

        addDiagonal(
                "r",
                "d"
        );

        addDiagonal(
                "r",
                "f"
        );

        addDiagonal(
                "t",
                "f"
        );

        addDiagonal(
                "t",
                "g"
        );

        addDiagonal(
                "y",
                "g"
        );

        addDiagonal(
                "y",
                "h"
        );

        addDiagonal(
                "u",
                "h"
        );

        addDiagonal(
                "u",
                "j"
        );

        addDiagonal(
                "i",
                "j"
        );

        addDiagonal(
                "i",
                "k"
        );

        addDiagonal(
                "o",
                "k"
        );

        addDiagonal(
                "o",
                "l"
        );

        addDiagonal(
                "p",
                "l"
        );

        addDiagonal(
                "a",
                "z"
        );

        addDiagonal(
                "a",
                "x"
        );

        addDiagonal(
                "s",
                "z"
        );

        addDiagonal(
                "s",
                "x"
        );

        addDiagonal(
                "s",
                "c"
        );

        addDiagonal(
                "d",
                "x"
        );

        addDiagonal(
                "d",
                "c"
        );

        addDiagonal(
                "d",
                "v"
        );

        addDiagonal(
                "f",
                "c"
        );

        addDiagonal(
                "f",
                "v"
        );

        addDiagonal(
                "f",
                "b"
        );

        addDiagonal(
                "g",
                "v"
        );

        addDiagonal(
                "g",
                "b"
        );

        addDiagonal(
                "g",
                "n"
        );

        addDiagonal(
                "h",
                "b"
        );

        addDiagonal(
                "h",
                "n"
        );

        addDiagonal(
                "h",
                "m"
        );

        addDiagonal(
                "j",
                "n"
        );

        addDiagonal(
                "j",
                "m"
        );

        addDiagonal(
                "k",
                "m"
        );
    }

    private KeyboardErrorModel() {
    }

    public static int getSubstitutionCost(
            char typed,
            char candidate,
            String languageTag) {

        char first =
                normalizeCharacter(
                        typed
                );

        char second =
                normalizeCharacter(
                        candidate
                );

        if (first == second) {
            return COST_EXACT;
        }

        /*
         * Las equivalencias lingüísticas no se resuelven aquí.
         * LanguageRules se encargará de ellas.
         */

        Map<Character, Integer> neighbors =
                QWERTY_COSTS.get(first);

        if (neighbors != null) {

            Integer cost =
                    neighbors.get(second);

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

        if (areKeyboardNeighbors(
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

        /*
         * Una letra repetida accidentalmente es un error bastante
         * probable al escribir rápido.
         */
        return COST_NEIGHBOR;
    }

    public static int getUnknownCost() {
        return COST_UNKNOWN;
    }

    private static char normalizeCharacter(
            char character) {

        return Character.toLowerCase(
                character
        );
    }

    private static void addRow(
            String row) {

        if (row == null ||
                row.length() < 2) {

            return;
        }

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

            if (i + 1 < row.length()) {

                addSymmetricCost(
                        current,
                        row.charAt(i + 1),
                        COST_NEIGHBOR
                );
            }
        }
    }

    private static void addNeighbors(
            String source,
            String neighbors) {

        if (source == null ||
                source.isEmpty() ||
                neighbors == null) {

            return;
        }

        char sourceCharacter =
                source.charAt(0);

        for (int i = 0;
             i < neighbors.length();
             i++) {

            addSymmetricCost(
                    sourceCharacter,
                    neighbors.charAt(i),
                    COST_NEIGHBOR
            );
        }
    }

    private static void addDiagonal(
            String first,
            String second) {

        if (first == null ||
                first.isEmpty() ||
                second == null ||
                second.isEmpty()) {

            return;
        }

        addSymmetricCost(
                first.charAt(0),
                second.charAt(0),
                COST_DIAGONAL
        );
    }

    private static void addSymmetricCost(
            char first,
            char second,
            int cost) {

        char normalizedFirst =
                normalizeCharacter(
                        first
                );

        char normalizedSecond =
                normalizeCharacter(
                        second
                );

        putCost(
                normalizedFirst,
                normalizedSecond,
                cost
        );

        putCost(
                normalizedSecond,
                normalizedFirst,
                cost
        );
    }

    private static void putCost(
            char first,
            char second,
            int cost) {

        Map<Character, Integer> neighbors =
                QWERTY_COSTS.computeIfAbsent(
                        first,
                        key -> new HashMap<>()
                );

        Integer existing =
                neighbors.get(second);

        if (existing == null ||
                cost < existing) {

            neighbors.put(
                    second,
                    cost
            );
        }
    }
}
