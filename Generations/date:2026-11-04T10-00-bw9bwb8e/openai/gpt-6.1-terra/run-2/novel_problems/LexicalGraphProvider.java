@LexicalGraphProvider.ThreadSafe
public final class LexicalGraphProvider {
    @interface ThreadSafe {}

    private final Object stateMonitor = new Object();

    // All published arrays are privately owned and never modified after publication.
    private String[] vocabulary = new String[0];
    private String[] tokens = new String[0];
    private String[] occurrenceWords = new String[0];
    private int[][] occurrenceIndexes = new int[0][];
    private long vocabularyRevision;
    private long contextGeneration;

    /*
     * Operations do not wait for other operations to complete, except for
     * acquiring the state monitor. Invalid input causes IllegalArgumentException;
     * counter overflow causes IllegalStateException without publication.
     */
    public void replaceVocabulary(String[] words) {
        if (words == null) {
            throw new IllegalArgumentException("Vocabulary must not be null");
        }

        String[] prepared = words.clone();
        for (int i = 0; i < prepared.length; i++) {
            prepared[i] = normalizeLetters(prepared[i], false);
        }
        sortStrings(prepared, false);

        int uniqueCount = 0;
        for (int i = 0; i < prepared.length; i++) {
            if (uniqueCount == 0 || !prepared[i].equals(prepared[uniqueCount - 1])) {
                prepared[uniqueCount++] = prepared[i];
            }
        }
        String[] unique = new String[uniqueCount];
        System.arraycopy(prepared, 0, unique, 0, uniqueCount);

        synchronized (stateMonitor) {
            if (vocabularyRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("Vocabulary revision overflow");
            }
            vocabulary = unique;
            vocabularyRevision++;
        }
    }

    public void setContext(String sentence) {
        if (sentence == null) {
            throw new IllegalArgumentException("Sentence must not be null");
        }

        ContextData prepared = prepareContext(sentence);
        synchronized (stateMonitor) {
            if (contextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Context generation overflow");
            }
            tokens = prepared.tokens;
            occurrenceWords = prepared.words;
            occurrenceIndexes = prepared.indexes;
            contextGeneration++;
        }
    }

    public Session openSession() {
        synchronized (stateMonitor) {
            return new Session(
                    vocabulary, tokens, occurrenceWords, occurrenceIndexes,
                    vocabularyRevision, contextGeneration);
        }
    }

    public Snapshot snapshot() {
        return openSession().snapshot();
    }

    public static final class Session {
        private final String[] vocabulary;
        private final String[] tokens;
        private final String[] occurrenceWords;
        private final int[][] occurrenceIndexes;
        private final long vocabularyRevision;
        private final long contextGeneration;

        private Session(
                String[] vocabulary,
                String[] tokens,
                String[] occurrenceWords,
                int[][] occurrenceIndexes,
                long vocabularyRevision,
                long contextGeneration) {
            this.vocabulary = vocabulary;
            this.tokens = tokens;
            this.occurrenceWords = occurrenceWords;
            this.occurrenceIndexes = occurrenceIndexes;
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }

        public String[] findMatches(String prefix) {
            String normalized = normalizeLetters(prefix, true);
            int start = lowerBound(vocabulary, normalized);
            int end = start;
            int count = 0;

            while (end < vocabulary.length && vocabulary[end].startsWith(normalized)) {
                if (contains(occurrenceWords, vocabulary[end])) {
                    count++;
                }
                end++;
            }

            String[] result = new String[count];
            int next = 0;
            for (int i = start; i < end; i++) {
                if (contains(occurrenceWords, vocabulary[i])) {
                    result[next++] = vocabulary[i];
                }
            }
            sortStrings(result, true);
            return result;
        }

        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= tokens.length) {
                throw new IllegalArgumentException("Invalid token index");
            }

            int count = 0;
            for (int i = tokenIndex + 1; i < tokens.length; i++) {
                if (contains(vocabulary, tokens[i])) {
                    count++;
                }
            }

            int[] result = new int[count];
            int next = 0;
            for (int i = tokenIndex + 1; i < tokens.length; i++) {
                if (contains(vocabulary, tokens[i])) {
                    result[next++] = i;
                }
            }
            return result;
        }

        public Snapshot snapshot() {
            String[] vocabularyCopy = vocabulary.clone();
            String[] tokensCopy = tokens.clone();
            String[] occurrenceWordsCopy = occurrenceWords.clone();
            int[][] occurrenceIndexesCopy = new int[occurrenceIndexes.length][];
            for (int i = 0; i < occurrenceIndexes.length; i++) {
                occurrenceIndexesCopy[i] = occurrenceIndexes[i].clone();
            }
            return new Snapshot(
                    vocabularyCopy, tokensCopy, occurrenceWordsCopy,
                    occurrenceIndexesCopy, vocabularyRevision, contextGeneration);
        }
    }

    /*
     * Arrays are detached from the provider and its sessions. occurrenceWords
     * is sorted lexically; occurrenceIndexes[i] contains all token indexes for
     * occurrenceWords[i], in increasing order. Vocabulary is also sorted
     * lexically. Callers may modify the arrays without affecting the provider.
     */
    public static final class Snapshot {
        public final String[] vocabulary;
        public final String[] tokens;
        public final String[] occurrenceWords;
        public final int[][] occurrenceIndexes;
        public final long vocabularyRevision;
        public final long contextGeneration;

        private Snapshot(
                String[] vocabulary,
                String[] tokens,
                String[] occurrenceWords,
                int[][] occurrenceIndexes,
                long vocabularyRevision,
                long contextGeneration) {
            this.vocabulary = vocabulary;
            this.tokens = tokens;
            this.occurrenceWords = occurrenceWords;
            this.occurrenceIndexes = occurrenceIndexes;
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }
    }

    private static final class ContextData {
        final String[] tokens;
        final String[] words;
        final int[][] indexes;

        ContextData(String[] tokens, String[] words, int[][] indexes) {
            this.tokens = tokens;
            this.words = words;
            this.indexes = indexes;
        }
    }

    private static ContextData prepareContext(String sentence) {
        int length = sentence.length();
        int tokenCount = 0;
        int position = 0;

        while (position < length) {
            if (!isAsciiLetter(sentence.charAt(position))) {
                position++;
            } else {
                tokenCount++;
                do {
                    position++;
                } while (position < length && isAsciiLetter(sentence.charAt(position)));
            }
        }

        String[] tokens = new String[tokenCount];
        position = 0;
        int tokenNumber = 0;
        while (position < length) {
            if (!isAsciiLetter(sentence.charAt(position))) {
                position++;
            } else {
                int start = position;
                do {
                    position++;
                } while (position < length && isAsciiLetter(sentence.charAt(position)));

                char[] characters = new char[position - start];
                for (int i = 0; i < characters.length; i++) {
                    characters[i] = lowerAscii(sentence.charAt(start + i));
                }
                tokens[tokenNumber++] = new String(characters);
            }
        }

        String[] sorted = tokens.clone();
        sortStrings(sorted, false);
        int uniqueCount = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (uniqueCount == 0 || !sorted[i].equals(sorted[uniqueCount - 1])) {
                sorted[uniqueCount++] = sorted[i];
            }
        }
        String[] words = new String[uniqueCount];
        System.arraycopy(sorted, 0, words, 0, uniqueCount);

        int[] counts = new int[uniqueCount];
        for (int i = 0; i < tokens.length; i++) {
            counts[lowerBound(words, tokens[i])]++;
        }

        int[][] indexes = new int[uniqueCount][];
        for (int i = 0; i < uniqueCount; i++) {
            indexes[i] = new int[counts[i]];
            counts[i] = 0;
        }
        for (int i = 0; i < tokens.length; i++) {
            int wordIndex = lowerBound(words, tokens[i]);
            indexes[wordIndex][counts[wordIndex]++] = i;
        }
        return new ContextData(tokens, words, indexes);
    }

    private static String normalizeLetters(String value, boolean allowEmpty) {
        if (value == null || (!allowEmpty && value.length() == 0)) {
            throw new IllegalArgumentException("Invalid ASCII-letter string");
        }
        char[] characters = new char[value.length()];
        for (int i = 0; i < characters.length; i++) {
            char c = value.charAt(i);
            if (!isAsciiLetter(c)) {
                throw new IllegalArgumentException("Invalid ASCII-letter string");
            }
            characters[i] = lowerAscii(c);
        }
        return new String(characters);
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static char lowerAscii(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
    }

    private static boolean contains(String[] sorted, String value) {
        int index = lowerBound(sorted, value);
        return index < sorted.length && sorted[index].equals(value);
    }

    private static int lowerBound(String[] sorted, String value) {
        int low = 0;
        int high = sorted.length;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (sorted[middle].compareTo(value) < 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static void sortStrings(String[] values, boolean matchOrder) {
        if (values.length < 2) {
            return;
        }
        String[] temporary = new String[values.length];
        mergeSort(values, temporary, 0, values.length, matchOrder);
    }

    private static void mergeSort(
            String[] values, String[] temporary, int from, int to, boolean matchOrder) {
        if (to - from < 2) {
            return;
        }
        int middle = from + (to - from) / 2;
        mergeSort(values, temporary, from, middle, matchOrder);
        mergeSort(values, temporary, middle, to, matchOrder);

        int left = from;
        int right = middle;
        int output = from;
        while (left < middle && right < to) {
            if (compare(values[left], values[right], matchOrder) <= 0) {
                temporary[output++] = values[left++];
            } else {
                temporary[output++] = values[right++];
            }
        }
        while (left < middle) {
            temporary[output++] = values[left++];
        }
        while (right < to) {
            temporary[output++] = values[right++];
        }
        System.arraycopy(temporary, from, values, from, to - from);
    }

    private static int compare(String first, String second, boolean matchOrder) {
        if (matchOrder && first.length() != second.length()) {
            return first.length() > second.length() ? -1 : 1;
        }
        return first.compareTo(second);
    }
}
