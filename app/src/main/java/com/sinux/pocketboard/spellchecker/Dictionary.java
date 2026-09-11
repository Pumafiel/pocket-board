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

        String value =
                flags[index];

        return value != null
                ? value
                : "";
    }

    public boolean contains(
            String word) {

        return indexOf(word) >= 0;
    }

    public int indexOf(
            String word) {

        if (word == null ||
                word.isEmpty()) {
            return -1;
        }

        int result =
                Arrays.binarySearch(
                        words,
                        word
                );

        return result >= 0
                ? result
                : -1;
    }
}
