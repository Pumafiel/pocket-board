package com.sinux.pocketboard.spellchecker;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Words the keyboard has learned from what the user actually types.
 *
 * <p>This is NOT Android's "Add to dictionary" (that one writes to the
 * system user dictionary, which this app does not read). It is a small
 * private file of our own, shared by the keyboard and the spellchecker
 * service, so both stop flagging or "correcting" the same words.
 *
 * <h3>How a word becomes known</h3>
 * <ul>
 *   <li>Every time the user commits a word that is NOT in the
 *       dictionary and keeps it as typed, its counter goes up
 *       ({@link #noteTyped}). From {@link #LEARN_THRESHOLD} times on,
 *       the word is known. A typo is rarely typed identically three
 *       times; a name or a slang word is.</li>
 *   <li>When the user undoes an autocorrection with Backspace, the
 *       original word is known immediately ({@link #keepAsTyped}).</li>
 * </ul>
 *
 * <h3>Where it is stored</h3>
 * {@code learned-words.tsv} in the app's device-protected files
 * directory (the same storage the app's settings use). One line per
 * word: {@code word<TAB>count<TAB>lastUsedMillis}. The app declares no
 * internet permission, so nothing is sent anywhere. It survives app
 * updates signed with the same key and is deleted with the app. Words are stored lower-case and apply to every
 * language, since names, brands and slang are not language specific.
 *
 * <h3>Limits</h3>
 * At most {@link #MAX_ENTRIES} entries. When full, words that were
 * never learned (still below the threshold) go first, oldest first;
 * then the learned ones nobody has used for the longest time. Words
 * seen only a few times and then abandoned expire after 90 days.
 */
public final class LearnedWords {

    private static final String TAG = "LearnedWords";

    /**
     * How many times a word must be kept as typed to count as known.
     */
    public static final int LEARN_THRESHOLD = 3;

    public static final int MAX_ENTRIES = 3000;

    private static final int MIN_WORD_LENGTH = 2;
    private static final int MAX_WORD_LENGTH = 40;

    private static final long PENDING_MAX_AGE_MS =
            90L * 24L * 60L * 60L * 1000L;

    /*
     * Counter changes are kept in memory and written in batches (and
     * when the input session ends); only events that matter right
     * away (a word becoming known, an undo, a clear) write at once.
     */
    private static final int FLUSH_EVERY_CHANGES = 20;

    private static final String FILE_NAME = "learned-words.tsv";
    private static final String HEADER = "#POCKETBOARD-LEARNED-1";

    private static LearnedWords instance;

    private final File file;

    private final Map<String, Entry> entries = new HashMap<>();

    private boolean dirty;
    private int unsavedChanges;

    private static final class Entry {

        int count;
        long lastUsed;

        Entry(int count, long lastUsed) {
            this.count = count;
            this.lastUsed = lastUsed;
        }
    }

    /**
     * One instance per process. The keyboard and the spellchecker
     * service both run in the app's main process, so they share it.
     */
    public static synchronized LearnedWords get(Context context) {

        if (instance == null) {

            instance = new LearnedWords(
                    context.getApplicationContext()
            );
        }

        return instance;
    }

    static synchronized void resetForTests() {
        instance = null;
    }

    private LearnedWords(Context context) {

        file = new File(
                context
                        .createDeviceProtectedStorageContext()
                        .getFilesDir(),
                FILE_NAME
        );

        load();
    }

    /*
     * ============================================================
     * QUERIES
     * ============================================================
     */

    /**
     * True when the word has been learned (kept as typed at least
     * {@link #LEARN_THRESHOLD} times, or kept after an undo).
     */
    public synchronized boolean contains(String word) {

        Entry entry = entries.get(normalize(word));

        return entry != null
                && entry.count >= LEARN_THRESHOLD;
    }

    public synchronized int learnedCount() {

        int learned = 0;

        for (Entry entry : entries.values()) {

            if (entry.count >= LEARN_THRESHOLD) {
                learned++;
            }
        }

        return learned;
    }

    /**
     * Learned words close enough to {@code input} to be offered as
     * completions or corrections: words that start with it, or that
     * are one edit away (two for longer inputs). Most used first.
     * The correction engine decides the final order.
     */
    public synchronized List<String> findCandidates(
            String input,
            int limit) {

        String typed = normalize(input);

        if (typed.length() < MIN_WORD_LENGTH
                || limit <= 0) {

            return Collections.emptyList();
        }

        int maxDistance =
                typed.length() >= 6
                        ? 2
                        : 1;

        List<Map.Entry<String, Entry>> hits = new ArrayList<>();

        for (Map.Entry<String, Entry> item : entries.entrySet()) {

            Entry entry = item.getValue();

            if (entry.count < LEARN_THRESHOLD) {
                continue;
            }

            String word = item.getKey();

            if (word.equals(typed)) {
                continue;
            }

            boolean completion = word.startsWith(typed);

            boolean nearby =
                    !completion
                            && typed.length() >= 3
                            && Math.abs(
                            word.length() - typed.length()
                    ) <= maxDistance
                            && withinDistance(
                            word,
                            typed,
                            maxDistance
                    );

            if (completion || nearby) {
                hits.add(item);
            }
        }

        Collections.sort(
                hits,
                (first, second) -> {

                    int byCount = Integer.compare(
                            second.getValue().count,
                            first.getValue().count
                    );

                    return byCount != 0
                            ? byCount
                            : first.getKey().compareTo(
                            second.getKey()
                    );
                }
        );

        List<String> result = new ArrayList<>();

        for (Map.Entry<String, Entry> hit : hits) {

            if (result.size() >= limit) {
                break;
            }

            result.add(hit.getKey());
        }

        return result;
    }

    /*
     * ============================================================
     * LEARNING
     * ============================================================
     */

    /**
     * Whether a word is the kind of thing worth learning: letters
     * (with an apostrophe or hyphen inside), 2 to 40 characters, no
     * digits and no letter repeated three times in a row ("holaaaa").
     * Numbers, codes and keyboard mashing are never learned.
     */
    public static boolean isLearnable(CharSequence word) {

        String value = normalize(word);

        int length = value.length();

        if (length < MIN_WORD_LENGTH
                || length > MAX_WORD_LENGTH) {

            return false;
        }

        if (!Character.isLetter(value.charAt(0))
                || !Character.isLetter(value.charAt(length - 1))) {

            return false;
        }

        int letters = 0;
        int repeated = 1;

        for (int i = 0; i < length; i++) {

            char c = value.charAt(i);

            boolean letter = Character.isLetter(c);

            if (!letter && c != '\'' && c != '-') {
                return false;
            }

            if (letter) {
                letters++;
            }

            if (i > 0) {

                char previous = value.charAt(i - 1);

                if (!letter && !Character.isLetter(previous)) {
                    return false;
                }

                repeated =
                        c == previous
                                ? repeated + 1
                                : 1;

                if (repeated >= 3) {
                    return false;
                }
            }
        }

        return letters >= MIN_WORD_LENGTH;
    }

    /**
     * The user kept {@code word} as typed. Returns true if this
     * made it a known word.
     */
    public synchronized boolean noteTyped(String word) {

        if (!isLearnable(word)) {
            return false;
        }

        String key = normalize(word);

        long now = System.currentTimeMillis();

        Entry entry = entries.get(key);

        if (entry == null) {

            entry = new Entry(0, now);
            entries.put(key, entry);
        }

        boolean wasKnown = entry.count >= LEARN_THRESHOLD;

        entry.count++;
        entry.lastUsed = now;

        boolean becameKnown =
                !wasKnown
                        && entry.count >= LEARN_THRESHOLD;

        if (becameKnown) {

            evictIfNeeded();
            saveNow();

        } else {

            markChanged();
        }

        return becameKnown;
    }

    /**
     * The user undid an autocorrection: they meant this word, so it
     * is known from now on without waiting for more repetitions.
     */
    public synchronized boolean keepAsTyped(String word) {

        if (!isLearnable(word)) {
            return false;
        }

        String key = normalize(word);

        long now = System.currentTimeMillis();

        Entry entry = entries.get(key);

        if (entry == null) {

            entry = new Entry(0, now);
            entries.put(key, entry);
        }

        entry.count = Math.max(entry.count, LEARN_THRESHOLD);
        entry.lastUsed = now;

        evictIfNeeded();
        saveNow();

        return true;
    }

    public synchronized void clear() {

        entries.clear();

        dirty = false;
        unsavedChanges = 0;

        if (file.exists() && !file.delete()) {
            Log.e(TAG, "Could not delete " + file);
        }
    }

    /**
     * Writes pending counter changes. Called when an input session
     * ends.
     */
    public synchronized void flush() {

        if (dirty) {
            saveNow();
        }
    }

    /*
     * ============================================================
     * STORAGE
     * ============================================================
     */

    private void markChanged() {

        dirty = true;
        unsavedChanges++;

        if (unsavedChanges >= FLUSH_EVERY_CHANGES) {
            saveNow();
        }
    }

    private void load() {

        if (!file.exists()) {
            return;
        }

        long now = System.currentTimeMillis();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(file),
                        StandardCharsets.UTF_8
                )
        )) {

            String line;

            while ((line = reader.readLine()) != null) {

                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }

                String[] parts = line.split("\t");

                if (parts.length < 3) {
                    continue;
                }

                String word = normalize(parts[0]);

                if (!isLearnable(word)) {
                    continue;
                }

                try {

                    int count = Integer.parseInt(parts[1]);
                    long lastUsed = Long.parseLong(parts[2]);

                    boolean stalePending =
                            count < LEARN_THRESHOLD
                                    && now - lastUsed
                                    > PENDING_MAX_AGE_MS;

                    if (count > 0 && !stalePending) {
                        entries.put(word, new Entry(count, lastUsed));
                    }

                } catch (NumberFormatException ignored) {
                    // Skip a damaged line, keep the rest.
                }
            }

        } catch (IOException e) {
            Log.e(TAG, "Could not read " + file, e);
        }

        evictIfNeeded();
    }

    private void saveNow() {

        File temporary = new File(
                file.getParentFile(),
                FILE_NAME + ".tmp"
        );

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(
                        new FileOutputStream(temporary),
                        StandardCharsets.UTF_8
                )
        )) {

            writer.write(HEADER);
            writer.write('\n');

            for (Map.Entry<String, Entry> item : entries.entrySet()) {

                writer.write(item.getKey());
                writer.write('\t');
                writer.write(Integer.toString(item.getValue().count));
                writer.write('\t');
                writer.write(Long.toString(item.getValue().lastUsed));
                writer.write('\n');
            }

        } catch (IOException e) {

            Log.e(TAG, "Could not write " + temporary, e);

            return;
        }

        if (!temporary.renameTo(file)) {

            // Some filesystems refuse to replace an existing file.
            if (file.exists() && !file.delete()) {
                Log.e(TAG, "Could not replace " + file);
                return;
            }

            if (!temporary.renameTo(file)) {
                Log.e(TAG, "Could not move " + temporary);
                return;
            }
        }

        dirty = false;
        unsavedChanges = 0;
    }

    private void evictIfNeeded() {

        if (entries.size() <= MAX_ENTRIES) {
            return;
        }

        List<Map.Entry<String, Entry>> all =
                new ArrayList<>(entries.entrySet());

        // Never-learned words first, then by oldest use.
        Collections.sort(
                all,
                (first, second) -> {

                    boolean firstLearned =
                            first.getValue().count >= LEARN_THRESHOLD;

                    boolean secondLearned =
                            second.getValue().count >= LEARN_THRESHOLD;

                    if (firstLearned != secondLearned) {
                        return firstLearned ? 1 : -1;
                    }

                    return Long.compare(
                            first.getValue().lastUsed,
                            second.getValue().lastUsed
                    );
                }
        );

        int target = MAX_ENTRIES - 100;

        for (int i = 0;
             i < all.size() && entries.size() > target;
             i++) {

            entries.remove(all.get(i).getKey());
        }
    }

    /*
     * ============================================================
     * HELPERS
     * ============================================================
     */

    private static String normalize(CharSequence word) {

        if (word == null) {
            return "";
        }

        return word
                .toString()
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('\u2019', '\'');
    }

    /**
     * Edit distance (insert, delete, substitute, swap adjacent) is
     * at most {@code max}.
     */
    static boolean withinDistance(
            String first,
            String second,
            int max) {

        int n = first.length();
        int m = second.length();

        if (Math.abs(n - m) > max) {
            return false;
        }

        int[] beforePrevious = new int[m + 1];
        int[] previous = new int[m + 1];
        int[] current = new int[m + 1];

        for (int j = 0; j <= m; j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= n; i++) {

            current[0] = i;

            int rowMinimum = current[0];

            for (int j = 1; j <= m; j++) {

                int cost =
                        first.charAt(i - 1) == second.charAt(j - 1)
                                ? 0
                                : 1;

                int value = Math.min(
                        Math.min(
                                previous[j] + 1,
                                current[j - 1] + 1
                        ),
                        previous[j - 1] + cost
                );

                if (i > 1
                        && j > 1
                        && first.charAt(i - 1) == second.charAt(j - 2)
                        && first.charAt(i - 2) == second.charAt(j - 1)) {

                    value = Math.min(
                            value,
                            beforePrevious[j - 2] + 1
                    );
                }

                current[j] = value;

                rowMinimum = Math.min(rowMinimum, value);
            }

            if (rowMinimum > max) {
                return false;
            }

            int[] swap = beforePrevious;
            beforePrevious = previous;
            previous = current;
            current = swap;
        }

        return previous[m] <= max;
    }
}
