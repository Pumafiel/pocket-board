package com.sinux.pocketboard.spellchecker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Dictionary {

    private final String[] words;

    public Dictionary(List<String> words) {
        this.words = words.toArray(new String[0]);
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

    public boolean contains(String word) {
        return word != null
                && java.util.Arrays.binarySearch(
                words,
                word
        ) >= 0;
    }

    public List<String> getWords() {
        List<String> result =
                new ArrayList<>(words.length);

        Collections.addAll(result, words);

        return result;
    }

    public String[] getArray() {
        return words.clone();
    }
}
