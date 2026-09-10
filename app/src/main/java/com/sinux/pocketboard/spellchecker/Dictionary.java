package com.sinux.pocketboard.spellchecker;

import java.util.Arrays;

public final class Dictionary {

    private final String[] words;
    private final String[] flags;

    public Dictionary(
            String[] words,
            String[] flags) {

        this.words =
                words != null
                        ? words
                        : new String[0];

        this.flags =
                flags != null
                        ? flags
                        : new String[0];
    }

    public boolean isEmpty() {
        return words.length == 0;
    }

    public int size() {
        return words.length;
    }

    public String get(int index) {
        return words[index];
    }

    public String getFlags(int index) {
        if (index < 0 ||
                index >= flags.length) {

            return "";
        }

        return flags[index];
    }

    public boolean contains(String word) {
        return word != null
                && Arrays.binarySearch(
                        words,
                        word
                ) >= 0;
    }

    public int indexOf(String word) {
        if (word == null) {
            return -1;
        }

        return Arrays.binarySearch(
                words,
                word
        );
    }
}
