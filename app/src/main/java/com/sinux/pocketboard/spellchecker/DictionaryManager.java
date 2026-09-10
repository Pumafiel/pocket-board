package com.sinux.pocketboard.spellchecker;

import android.content.Context;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DictionaryManager {

    private static final String FORMAT_HEADER =
            "#POCKETBOARD-DICT-1";

    private static final int MAX_DICTIONARY_WORDS =
            250000;

    private static final int MAX_PREFIX_RESULTS =
            20;

    private static final int MAX_CORRECTION_CANDIDATES =
            600;

    private static final int MAX_LENGTH_DELTA =
            2;

    private static final int MAX_CORRECTION_RESULTS =
            3;

    private final Context context;

    private final Map<String, Dictionary> dictionaries =
            new HashMap<>();

    private final Map<String, DictionaryIndex> indexes =
            new HashMap<>();

    private final CorrectionEngine correctionEngine =
            new CorrectionEngine();

    public DictionaryManager(Context context) {

        this.context =
                context.getApplicationContext();
    }


    /*
     * ============================================================
     * SUGGESTIONS / CORRECTIONS
     * ============================================================
     */

    public synchronized List<String> getSuggestions(
            String word,
            String languageTag,
            int maxResults) {

        if (TextUtils.isEmpty(word) ||
                maxResults <= 0) {

            return new ArrayList<>();
        }

        String language =
                normalizeLanguage(
                        languageTag
                );

        Dictionary dictionary =
                getDictionary(
                        language
                );

        if (dictionary == null ||
                dictionary.isEmpty()) {

            return new ArrayList<>();
        }

        String normalizedWord =
                normalizeWord(
                        word
                );

        if (normalizedWord.isEmpty()) {
            return new ArrayList<>();
        }

        int resultLimit =
                Math.min(
                        maxResults,
                        MAX_CORRECTION_RESULTS
                );

        /*
         * --------------------------------------------------------
         * 1. PREFIX SUGGESTIONS
         * --------------------------------------------------------
         *
         * El prefijo sigue teniendo prioridad.
         *
         * Esto es importante para el uso normal del teclado:
         *
         *     ma -> mañana
         *     man -> mañana
         *     cas -> casa
         */

        List<String> prefixResults =
                getPrefixSuggestions(
                        dictionary,
                        normalizedWord,
                        Math.min(
                                resultLimit,
                                MAX_PREFIX_RESULTS
                        )
                );

        /*
         * Si tenemos suficientes resultados de prefijo,
         * no necesitamos ejecutar el motor de corrección.
         */

        if (prefixResults.size()
                >= resultLimit) {

            return applyCapitalization(
                    prefixResults,
                    word
            );
        }

        /*
         * --------------------------------------------------------
         * 2. CANDIDATOS DE CORRECCIÓN
         * --------------------------------------------------------
         *
         * Generamos únicamente candidatos plausibles:
         *
         * - misma palabra
         * - eliminación de una letra
         * - inserción de una letra
         * - sustitución
         * - transposición
         * - repetición accidental
         * - teclas vecinas
         *
         * Después CorrectionEngine decide cuáles son mejores.
         */

        List<String> candidates =
                collectCorrectionCandidates(
                        normalizedWord,
                        language
                );

        /*
         * Los prefijos ya encontrados también participan en el
         * ranking final, pero conservamos su prioridad.
         */

        LinkedHashSet<String> combined =
                new LinkedHashSet<>();

        combined.addAll(
                prefixResults
        );

        combined.addAll(
                candidates
        );

        if (combined.isEmpty()) {

            return new ArrayList<>();
        }

        /*
         * --------------------------------------------------------
         * 3. RANKING
         * --------------------------------------------------------
         */

        List<String> ranked =
                correctionEngine.rankCandidates(
                        normalizedWord,
                        new ArrayList<>(
                                combined
                        ),
                        language,
                        resultLimit
                );

        /*
         * Si el motor no encontró candidatos suficientemente
         * buenos pero sí tenemos prefijos, devolvemos éstos.
         */

        if (ranked.isEmpty()) {

            return applyCapitalization(
                    prefixResults,
                    word
            );
        }

        /*
         * --------------------------------------------------------
         * 4. CAPITALIZACIÓN
         * --------------------------------------------------------
         */

        return applyCapitalization(
                ranked,
                word
        );
    }


    /*
     * ============================================================
     * CONTAINS
     * ============================================================
     */

    public synchronized boolean contains(
            String word,
            String languageTag) {

        if (TextUtils.isEmpty(word)) {
            return false;
        }

        String language =
                normalizeLanguage(
                        languageTag
                );

        Dictionary dictionary =
                getDictionary(
                        language
                );

        if (dictionary == null ||
                dictionary.isEmpty()) {

            return false;
        }

        return dictionary.contains(
                normalizeWord(
                        word
                )
        );
    }


    /*
     * ============================================================
     * DICTIONARY LOADING
     * ============================================================
     */

    private Dictionary getDictionary(
            String language) {

        Dictionary dictionary =
                dictionaries.get(
                        language
                );

        if (dictionary != null) {
            return dictionary;
        }

        dictionary =
                loadDictionary(
                        language
                );

        dictionaries.put(
                language,
                dictionary
        );

        /*
         * El índice se construye una sola vez.
         */

        indexes.put(
                language,
                new DictionaryIndex(
                        dictionary
                )
        );

        return dictionary;
    }


    private Dictionary loadDictionary(
            String language) {

        String assetName =
                "dictionaries/" +
                        getAssetName(
                                language
                        );

        List<String> words =
                new ArrayList<>();

        List<String> flags =
                new ArrayList<>();

        try (
                InputStream inputStream =
                        context.getAssets().open(
                                assetName
                        );

                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        inputStream,
                                        StandardCharsets.UTF_8
                                )
                        )
        ) {

            String header =
                    reader.readLine();

            if (!FORMAT_HEADER.equals(
                    header
            )) {

                return emptyDictionary();
            }

            String line;

            while (
                    (line = reader.readLine()) != null
            ) {

                if (line.isEmpty() ||
                        line.startsWith("#")) {

                    continue;
                }

                int separator =
                        line.indexOf(
                                '\t'
                        );

                String word;
                String wordFlags;

                if (separator >= 0) {

                    word =
                            line.substring(
                                    0,
                                    separator
                            );

                    wordFlags =
                            line.substring(
                                    separator + 1
                            );

                } else {

                    word = line;
                    wordFlags = "";
                }

                word =
                        normalizeWord(
                                word
                        );

                if (!isValidWord(
                        word
                )) {

                    continue;
                }

                words.add(
                        word
                );

                flags.add(
                        wordFlags.trim()
                );

                if (words.size()
                        >= MAX_DICTIONARY_WORDS) {

                    break;
                }
            }

        } catch (IOException ignored) {

            return emptyDictionary();
        }

        if (words.isEmpty()) {
            return emptyDictionary();
        }

        String[] wordArray =
                words.toArray(
                        new String[0]
                );

        String[] flagArray =
                flags.toArray(
                        new String[0]
                );

        /*
         * El build ya entrega los datos ordenados.
         *
         * Aun así ordenamos defensivamente para garantizar
         * el contrato de Dictionary.binarySearch().
         */

        sortEntries(
                wordArray,
                flagArray
        );

        return new Dictionary(
                wordArray,
                flagArray
        );
    }


    private void sortEntries(
            String[] words,
            String[] flags) {

        Integer[] indexes =
                new Integer[
                        words.length
                ];

        for (int i = 0;
             i < indexes.length;
             i++) {

            indexes[i] = i;
        }

        Arrays.sort(
                indexes,
                Comparator.comparing(
                        i -> words[i]
                )
        );

        String[] sortedWords =
                words.clone();

        String[] sortedFlags =
                flags.clone();

        for (int i = 0;
             i < indexes.length;
             i++) {

            int source =
                    indexes[i];

            words[i] =
                    sortedWords[source];

            flags[i] =
                    sortedFlags[source];
        }
    }


    private Dictionary emptyDictionary() {

        return new Dictionary(
                new String[0],
                new String[0]
        );
    }


    /*
     * ============================================================
     * PREFIX SEARCH
     * ============================================================
     */

    private List<String> getPrefixSuggestions(
            Dictionary dictionary,
            String prefix,
            int maxResults) {

        if (dictionary == null ||
                dictionary.isEmpty() ||
                prefix == null ||
                prefix.isEmpty() ||
                maxResults <= 0) {

            return new ArrayList<>();
        }

        List<String> results =
                new ArrayList<>(
                        maxResults
                );

        int index =
                findPrefixStart(
                        dictionary,
                        prefix
                );

        while (
                index < dictionary.size() &&
                results.size() < maxResults
        ) {

            String candidate =
                    dictionary.get(
                            index
                    );

            if (!candidate.startsWith(
                    prefix
            )) {

                break;
            }

            results.add(
                    candidate
            );

            index++;
        }

        return results;
    }


    private int findPrefixStart(
            Dictionary dictionary,
            String prefix) {

        int low = 0;
        int high =
                dictionary.size();

        while (low < high) {

            int mid =
                    (low + high) >>> 1;

            String candidate =
                    dictionary.get(
                            mid
                    );

            if (candidate.compareTo(
                    prefix
            ) < 0) {

                low =
                        mid + 1;

            } else {

                high =
                        mid;
            }
        }

        return low;
    }


    /*
     * ============================================================
     * CORRECTION CANDIDATE GENERATION
     * ============================================================
     */

    private List<String> collectCorrectionCandidates(
            String input,
            String language) {

        DictionaryIndex index =
                indexes.get(
                        language
                );

        if (index == null ||
                input.isEmpty()) {

            return new ArrayList<>();
        }

        LinkedHashSet<String> candidates =
                new LinkedHashSet<>();

        /*
         * --------------------------------------------------------
         * 1. Variantes por eliminación
         * --------------------------------------------------------
         *
         * manana -> maana
         *
         * Útil para detectar palabras donde falta una letra
         * respecto del candidato real.
         */

        addDeletionCandidates(
                input,
                index,
                candidates
        );

        /*
         * --------------------------------------------------------
         * 2. Variantes por inserción
         * --------------------------------------------------------
         *
         * maanana -> manana
         *
         * Se genera eliminando cada carácter de la entrada y
         * buscando esa variante.
         */

        addInsertionCandidates(
                input,
                index,
                candidates
        );

        /*
         * --------------------------------------------------------
         * 3. Transposiciones
         * --------------------------------------------------------
         *
         * manana -> maanna
         *
         * qeu -> que
         */

        addTranspositionCandidates(
                input,
                index,
                candidates
        );

        /*
         * --------------------------------------------------------
         * 4. Sustituciones
         * --------------------------------------------------------
         *
         * Se prueban letras normales y letras físicamente
         * próximas en QWERTY.
         */

        addSubstitutionCandidates(
                input,
                language,
                index,
                candidates
        );

        /*
         * --------------------------------------------------------
         * 5. Repeticiones accidentales
         * --------------------------------------------------------
         *
         * helllo -> hello
         * maaana -> maana
         */

        addRepetitionCandidates(
                input,
                index,
                candidates
        );

        /*
         * --------------------------------------------------------
         * 6. Variantes sin diacríticos
         * --------------------------------------------------------
         *
         * manana -> mañana
         *
         * Esta búsqueda depende del idioma.
         */

        addDiacriticCandidates(
                input,
                language,
                index,
                candidates
        );

        /*
         * El conjunto está deliberadamente limitado para impedir
         * que una corrección bloquee el hilo del teclado.
         */

        if (candidates.size()
                > MAX_CORRECTION_CANDIDATES) {

            List<String> limited =
                    new ArrayList<>(
                            candidates
                    );

            return limited.subList(
                    0,
                    MAX_CORRECTION_CANDIDATES
            );
        }

        return new ArrayList<>(
                candidates
        );
    }


    /*
     * ============================================================
     * DELETE
     * ============================================================
     */

    private void addDeletionCandidates(
            String input,
            DictionaryIndex index,
            Set<String> candidates) {

        if (input.length() <= 1) {
            return;
        }

        for (int i = 0;
             i < input.length();
             i++) {

            String variant =
                    input.substring(
                            0,
                            i
                    )
                            +
                            input.substring(
                                    i + 1
                            );

            addIndexMatches(
                    variant,
                    index,
                    candidates
            );

            if (candidates.size()
                    >= MAX_CORRECTION_CANDIDATES) {

                return;
            }
        }
    }


    /*
     * ============================================================
     * INSERT
     * ============================================================
     */

    private void addInsertionCandidates(
            String input,
            DictionaryIndex index,
            Set<String> candidates) {

        /*
         * No generamos todas las letras del alfabeto.
         *
         * Buscamos palabras del diccionario que difieren por una
         * eliminación. Para eso aprovechamos los buckets por
         * longitud y prefijo.
         */

        List<String> lengthCandidates =
                index.getLengthCandidates(
                        input.length() + 1
                );

        for (String candidate :
                lengthCandidates) {

            if (candidates.size()
                    >= MAX_CORRECTION_CANDIDATES) {

                return;
            }

            if (isOneInsertionAway(
                    input,
                    candidate
            )) {

                candidates.add(
                        candidate
                );
            }
        }
    }


    private boolean isOneInsertionAway(
            String shorter,
            String longer) {

        if (longer.length()
                != shorter.length() + 1) {

            return false;
        }

        int shortIndex = 0;
        int longIndex = 0;
        boolean skipped = false;

        while (
                shortIndex < shorter.length() &&
                longIndex < longer.length()
        ) {

            if (shorter.charAt(
                    shortIndex
            ) ==
                    longer.charAt(
                            longIndex
                    )) {

                shortIndex++;
                longIndex++;

                continue;
            }

            if (skipped) {
                return false;
            }

            skipped = true;
            longIndex++;
        }

        return true;
    }


    /*
     * ============================================================
     * TRANSPOSE
     * ============================================================
     */

    private void addTranspositionCandidates(
            String input,
            DictionaryIndex index,
            Set<String> candidates) {

        if (input.length() < 2) {
            return;
        }

        for (int i = 0;
             i < input.length() - 1;
             i++) {

            char first =
                    input.charAt(
                            i
                    );

            char second =
                    input.charAt(
                            i + 1
                    );

            if (first == second) {
                continue;
            }

            String variant =
                    input.substring(
                            0,
                            i
                    )
                            +
                            second
                            +
                            first
                            +
                            input.substring(
                                    i + 2
                            );

            addIndexMatches(
                    variant,
                    index,
                    candidates
            );

            if (candidates.size()
                    >= MAX_CORRECTION_CANDIDATES) {

                return;
            }
        }
    }


    /*
     * ============================================================
     * SUBSTITUTION
     * ============================================================
     */

    private void addSubstitutionCandidates(
            String input,
            String language,
            DictionaryIndex index,
            Set<String> candidates) {

        /*
         * Primero probamos las teclas físicamente vecinas.
         *
         * Esto cubre errores frecuentes como:
         *
         *     manana -> mamana
         *     holq -> hola
         */

        for (int i = 0;
             i < input.length();
             i++) {

            char typed =
                    input.charAt(
                            i
                    );

            List<Character> neighbors =
                    getKeyboardNeighbors(
                            typed
                    );

            for (Character replacement :
                    neighbors) {

                if (replacement == null) {
                    continue;
                }

                String variant =
                        replaceCharacter(
                                input,
                                i,
                                replacement
                        );

                addIndexMatches(
                        variant,
                        index,
                        candidates
                );

                if (candidates.size()
                        >= MAX_CORRECTION_CANDIDATES) {

                    return;
                }
            }
        }

        /*
         * Después buscamos candidatos de igual longitud.
         *
         * El CorrectionEngine se encargará de penalizar las
         * sustituciones que no sean plausibles.
         */

        List<String> sameLength =
                index.getLengthCandidates(
                        input.length()
                );

        for (String candidate :
                sameLength) {

            if (candidates.size()
                    >= MAX_CORRECTION_CANDIDATES) {

                return;
            }

            if (hasSmallSubstitutionDistance(
                    input,
                    candidate
            )) {

                candidates.add(
                        candidate
                );
            }
        }
    }


    private boolean hasSmallSubstitutionDistance(
            String first,
            String second) {

        if (first.length()
                != second.length()) {

            return false;
        }

        int differences = 0;

        for (int i = 0;
             i < first.length();
             i++) {

            if (first.charAt(i)
                    != second.charAt(i)) {

                differences++;

                if (differences > 2) {
                    return false;
                }
            }
        }

        return differences <= 2;
    }


    /*
     * ============================================================
     * REPETITIONS
     * ============================================================
     */

    private void addRepetitionCandidates(
            String input,
            DictionaryIndex index,
            Set<String> candidates) {

        if (input.length() < 2) {
            return;
        }

        /*
         * Caso:
         *
         *     helllo
         *
         * eliminamos una de las letras repetidas.
         */

        for (int i = 0;
             i < input.length() - 1;
             i++) {

            if (input.charAt(i)
                    != input.charAt(i + 1)) {

                continue;
            }

            String variant =
                    input.substring(
                            0,
                            i
                    )
                            +
                            input.substring(
                                    i + 1
                            );

            addIndexMatches(
                    variant,
                    index,
                    candidates
            );

            if (candidates.size()
                    >= MAX_CORRECTION_CANDIDATES) {

                return;
            }
        }
    }


    /*
     * ============================================================
     * DIACRITICS
     * ============================================================
     */

    private void addDiacriticCandidates(
            String input,
            String language,
            DictionaryIndex index,
            Set<String> candidates) {

        List<String> variants =
                LanguageRules.getDiacriticVariants(
                        input,
                        language
                );

        if (variants == null) {
            return;
        }

        for (String variant :
                variants) {

            if (TextUtils.isEmpty(
                    variant
            )) {

                continue;
            }

            addIndexMatches(
                    variant,
                    index,
                    candidates
            );

            if (candidates.size()
                    >= MAX_CORRECTION_CANDIDATES) {

                return;
            }
        }
    }


    /*
     * ============================================================
     * INDEX LOOKUP
     * ============================================================
     */

    private void addIndexMatches(
            String variant,
            DictionaryIndex index,
            Set<String> candidates) {

        if (variant == null ||
                variant.isEmpty()) {

            return;
        }

        /*
         * Primero buscamos coincidencia exacta.
         *
         * Las variantes generadas representan errores de un paso,
         * por lo que una coincidencia exacta es un candidato fuerte.
         */

        if (index.contains(
                variant
        )) {

            candidates.add(
                    variant
            );

            return;
        }

        /*
         * También agregamos palabras que comienzan por la variante.
         *
         * Esto ayuda especialmente cuando el usuario está a mitad
         * de una palabra.
         */

        int prefixLimit =
                Math.min(
                        5,
                        MAX_CORRECTION_CANDIDATES
                                - candidates.size()
                );

        if (prefixLimit <= 0) {
            return;
        }

        List<String> prefixMatches =
                index.getPrefixMatches(
                        variant,
                        prefixLimit
                );

        candidates.addAll(
                prefixMatches
        );
    }


    /*
     * ============================================================
     * KEYBOARD NEIGHBORS
     * ============================================================
     */

    private List<Character> getKeyboardNeighbors(
            char character) {

        List<Character> result =
                new ArrayList<>();

        String alphabet =
                "abcdefghijklmnopqrstuvwxyz";

        char normalized =
                Character.toLowerCase(
                        character
                );

        /*
         * No existe una API para enumerar vecinos en
         * KeyboardErrorModel, por lo que probamos las letras del
         * alfabeto y consultamos su costo.
         *
         * Son sólo 26 comprobaciones por posición, no 250.000
         * entradas del diccionario.
         */

        for (int i = 0;
             i < alphabet.length();
             i++) {

            char candidate =
                    alphabet.charAt(
                            i
                    );

            if (candidate == normalized) {
                continue;
            }

            if (KeyboardErrorModel
                    .getSubstitutionCost(
                            normalized,
                            candidate,
                            null
                    ) <= 2) {

                result.add(
                        candidate
                );
            }
        }

        return result;
    }


    private String replaceCharacter(
            String input,
            int index,
            char replacement) {

        StringBuilder builder =
                new StringBuilder(
                        input
                );

        builder.setCharAt(
                index,
                replacement
        );

        return builder.toString();
    }


    /*
     * ============================================================
     * CAPITALIZATION
     * ============================================================
     */

    private List<String> applyCapitalization(
            List<String> suggestions,
            String original) {

        if (suggestions == null ||
                suggestions.isEmpty()) {

            return new ArrayList<>();
        }

        List<String> results =
                new ArrayList<>(
                        suggestions.size()
                );

        for (String suggestion :
                suggestions) {

            results.add(
                    applyCapitalization(
                            suggestion,
                            original
                    )
            );
        }

        return results;
    }


    private String applyCapitalization(
            String suggestion,
            String original) {

        if (suggestion == null ||
                TextUtils.isEmpty(
                        original
                )) {

            return suggestion;
        }

        boolean allUpper =
                true;

        boolean firstUpper =
                Character.isUpperCase(
                        original.charAt(
                                0
                        )
                );

        for (int i = 0;
             i < original.length();
             i++) {

            char c =
                    original.charAt(
                            i
                    );

            if (Character.isLetter(c) &&
                    !Character.isUpperCase(c)) {

                allUpper = false;
                break;
            }
        }

        if (allUpper) {

            return suggestion.toUpperCase(
                    Locale.ROOT
            );
        }

        if (firstUpper &&
                !suggestion.isEmpty()) {

            return Character.toUpperCase(
                    suggestion.charAt(
                            0
                    )
            )
                    +
                    suggestion.substring(
                            1
                    );
        }

        return suggestion;
    }


    /*
     * ============================================================
     * LANGUAGE
     * ============================================================
     */

    private String normalizeLanguage(
            String languageTag) {

        if (TextUtils.isEmpty(
                languageTag
        )) {

            return "en";
        }

        String normalized =
                languageTag.replace(
                        '_',
                        '-'
                );

        Locale locale =
                Locale.forLanguageTag(
                        normalized
                );

        String language =
                locale.getLanguage();

        if ("es".equals(
                language
        )) {

            return "es-AR";
        }

        if ("de".equals(
                language
        )) {

            return "de";
        }

        if ("en".equals(
                language
        )) {

            return "en";
        }

        return "en";
    }


    private String normalizeWord(
            String word) {

        if (word == null) {
            return "";
        }

        return word
                .trim()
                .toLowerCase(
                        Locale.ROOT
                );
    }


    /*
     * ============================================================
     * VALIDATION
     * ============================================================
     */

    private boolean isValidWord(
            String word) {

        if (word == null ||
                word.length() < 1 ||
                word.length() > 64) {

            return false;
        }

        for (int i = 0;
             i < word.length();
             i++) {

            char c =
                    word.charAt(
                            i
                    );

            if (Character.isLetter(c) ||
                    c == '\'' ||
                    c == '-') {

                continue;
            }

            return false;
        }

        return true;
    }


    /*
     * ============================================================
     * ASSET NAMES
     * ============================================================
     */

    private String getAssetName(
            String language) {

        switch (language) {

            case "es-AR":
                return "es-AR.dict";

            case "de":
                return "de.dict";

            case "en":
            default:
                return "en.dict";
        }
    }


    /*
     * ============================================================
     * DICTIONARY INDEX
     * ============================================================
     *
     * El objetivo es evitar recorrer todo el diccionario en cada
     * pulsación.
     *
     * Tenemos:
     *
     *     longitud -> palabras
     *
     * y aprovechamos la ordenación original para búsquedas de
     * prefijo.
     */

    private static final class DictionaryIndex {

        private final Dictionary dictionary;

        private final Map<Integer, List<String>>
                wordsByLength =
                new HashMap<>();

        DictionaryIndex(
                Dictionary dictionary) {

            this.dictionary =
                    dictionary;

            buildLengthIndex();
        }

        private void buildLengthIndex() {

            if (dictionary == null ||
                    dictionary.isEmpty()) {

                return;
            }

            for (int i = 0;
                 i < dictionary.size();
                 i++) {

                String word =
                        dictionary.get(
                                i
                        );

                if (word == null) {
                    continue;
                }

                int length =
                        word.length();

                List<String> bucket =
                        wordsByLength.computeIfAbsent(
                                length,
                                key ->
                                        new ArrayList<>()
                        );

                bucket.add(
                        word
                );
            }
        }

        boolean contains(
                String word) {

            return dictionary != null &&
                    dictionary.contains(
                            word
                    );
        }

        List<String> getLengthCandidates(
                int length) {

            List<String> candidates =
                    wordsByLength.get(
                            length
                    );

            if (candidates == null) {
                return Collections.emptyList();
            }

            return candidates;
        }

        List<String> getPrefixMatches(
                String prefix,
                int limit) {

            if (dictionary == null ||
                    dictionary.isEmpty() ||
                    prefix == null ||
                    prefix.isEmpty() ||
                    limit <= 0) {

                return Collections.emptyList();
            }

            List<String> result =
                    new ArrayList<>(
                            limit
                    );

            int index =
                    findPrefixStart(
                            prefix
                    );

            while (
                    index < dictionary.size() &&
                    result.size() < limit
            ) {

                String candidate =
                        dictionary.get(
                                index
                        );

                if (!candidate.startsWith(
                        prefix
                )) {

                    break;
                }

                result.add(
                        candidate
                );

                index++;
            }

            return result;
        }

        private int findPrefixStart(
                String prefix) {

            int low = 0;

            int high =
                    dictionary.size();

            while (low < high) {

                int mid =
                        (low + high) >>> 1;

                String candidate =
                        dictionary.get(
                                mid
                        );

                if (candidate.compareTo(
                        prefix
                ) < 0) {

                    low =
                            mid + 1;

                } else {

                    high =
                            mid;
                }
            }

            return low;
        }
    }
}
