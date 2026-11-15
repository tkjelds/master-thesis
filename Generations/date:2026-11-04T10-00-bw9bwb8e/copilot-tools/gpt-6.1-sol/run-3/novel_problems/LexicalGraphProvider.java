@LexicalGraphProvider.ThreadSafe
public final class LexicalGraphProvider {
    @interface ThreadSafe {}

    private final Object stateMonitor = new Object();

    private Vocabulary vocabulary = new Vocabulary(new String[0]);
    private Context context =
            new Context(new String[0], new String[0], new int[0][]);
    private long vocabularyRevision;
    private long contextGeneration;

    public LexicalGraphProvider() {
    }

    public void replaceVocabulary(String[] words) {
        if (words == null) {
            throw new IllegalArgumentException("Vocabulary must not be null");
        }

        String[] preparedWords = new String[words.length];
        for (int i = 0; i < words.length; i++) {
            preparedWords[i] = normalizeLetters(words[i], false);
        }
        sort(preparedWords, false);
        Vocabulary prepared = new Vocabulary(uniqueSorted(preparedWords));

        synchronized (stateMonitor) {
            if (vocabularyRevision == 9223372036854775807L) {
                throw new IllegalStateException("Vocabulary revision overflow");
            }
            vocabulary = prepared;
            vocabularyRevision++;
        }
    }

    public void setContext(String sentence) {
        if (sentence == null) {
            throw new IllegalArgumentException("Sentence must not be null");
        }

        Context prepared = prepareContext(sentence);

        synchronized (stateMonitor) {
            if (contextGeneration == 9223372036854775807L) {
                throw new IllegalStateException("Context generation overflow");
            }
            context = prepared;
            contextGeneration++;
        }
    }

    public Session openSession() {
        synchronized (stateMonitor) {
            return new Session(
                    vocabulary, context, vocabularyRevision, contextGeneration);
        }
    }

    public Snapshot snapshot() {
        return openSession().snapshot();
    }

    public static final class Session {
        private final Vocabulary vocabulary;
        private final Context context;
        private final long vocabularyRevision;
        private final long contextGeneration;

        private Session(
                Vocabulary vocabulary,
                Context context,
                long vocabularyRevision,
                long contextGeneration) {
            this.vocabulary = vocabulary;
            this.context = context;
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }

        public String[] findMatches(String prefix) {
            String normalized = normalizeLetters(prefix, true);
            String[] matches = new String[vocabulary.words.length];
            int count = 0;

            for (int i = 0; i < vocabulary.words.length; i++) {
                String word = vocabulary.words[i];
                if (word.startsWith(normalized)
                        && binarySearch(context.occurrenceWords, word) >= 0) {
                    matches[count++] = word;
                }
            }

            String[] result = copyStrings(matches, count);
            sort(result, true);
            return result;
        }

        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= context.tokens.length) {
                throw new IllegalArgumentException("Invalid token index");
            }

            int count = 0;
            for (int i = tokenIndex + 1; i < context.tokens.length; i++) {
                if (binarySearch(vocabulary.words, context.tokens[i]) >= 0) {
                    count++;
                }
            }

            int[] result = new int[count];
            int next = 0;
            for (int i = tokenIndex + 1; i < context.tokens.length; i++) {
                if (binarySearch(vocabulary.words, context.tokens[i]) >= 0) {
                    result[next++] = i;
                }
            }
            return result;
        }

        public Snapshot snapshot() {
            return new Snapshot(
                    vocabulary, context, vocabularyRevision, contextGeneration);
        }
    }

    /**
     * A detached, caller-owned snapshot. Vocabulary and occurrenceWords are
     * lexically sorted. occurrenceIndexes[i] contains every token index for
     * occurrenceWords[i], in increasing order, including non-vocabulary words.
     * Mutating these arrays does not affect the provider or any session.
     */
    public static final class Snapshot {
        public final String[] vocabulary;
        public final String[] tokens;
        public final String[] occurrenceWords;
        public final int[][] occurrenceIndexes;
        public final long vocabularyRevision;
        public final long contextGeneration;

        private Snapshot(
                Vocabulary vocabulary,
                Context context,
                long vocabularyRevision,
                long contextGeneration) {
            this.vocabulary =
                    copyStrings(vocabulary.words, vocabulary.words.length);
            this.tokens = copyStrings(context.tokens, context.tokens.length);
            this.occurrenceWords = copyStrings(
                    context.occurrenceWords, context.occurrenceWords.length);
            this.occurrenceIndexes = new int[context.occurrenceIndexes.length][];

            for (int i = 0; i < occurrenceIndexes.length; i++) {
                int[] source = context.occurrenceIndexes[i];
                int[] copy = new int[source.length];
                for (int j = 0; j < source.length; j++) {
                    copy[j] = source[j];
                }
                occurrenceIndexes[i] = copy;
            }

            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }
    }

    private static final class Vocabulary {
        private final String[] words;

        private Vocabulary(String[] words) {
            this.words = words;
        }
    }

    private static final class Context {
        private final String[] tokens;
        private final String[] occurrenceWords;
        private final int[][] occurrenceIndexes;

        private Context(
                String[] tokens,
                String[] occurrenceWords,
                int[][] occurrenceIndexes) {
            this.tokens = tokens;
            this.occurrenceWords = occurrenceWords;
            this.occurrenceIndexes = occurrenceIndexes;
        }
    }

    private static Context prepareContext(String sentence) {
        int tokenCount = 0;
        boolean insideToken = false;
        for (int i = 0; i < sentence.length(); i++) {
            if (isAsciiLetter(sentence.charAt(i))) {
                if (!insideToken) {
                    tokenCount++;
                    insideToken = true;
                }
            } else {
                insideToken = false;
            }
        }

        String[] tokens = new String[tokenCount];
        int position = 0;
        int nextToken = 0;
        while (position < sentence.length()) {
            if (!isAsciiLetter(sentence.charAt(position))) {
                position++;
                continue;
            }

            int start = position;
            while (position < sentence.length()
                    && isAsciiLetter(sentence.charAt(position))) {
                position++;
            }

            char[] letters = new char[position - start];
            for (int i = 0; i < letters.length; i++) {
                letters[i] = lowercase(sentence.charAt(start + i));
            }
            tokens[nextToken++] = new String(letters);
        }

        String[] sortedTokens = copyStrings(tokens, tokens.length);
        sort(sortedTokens, false);
        String[] occurrenceWords = uniqueSorted(sortedTokens);

        int[] counts = new int[occurrenceWords.length];
        for (int i = 0; i < tokens.length; i++) {
            counts[binarySearch(occurrenceWords, tokens[i])]++;
        }

        int[][] occurrenceIndexes = new int[occurrenceWords.length][];
        for (int i = 0; i < occurrenceWords.length; i++) {
            occurrenceIndexes[i] = new int[counts[i]];
            counts[i] = 0;
        }

        for (int i = 0; i < tokens.length; i++) {
            int wordIndex = binarySearch(occurrenceWords, tokens[i]);
            occurrenceIndexes[wordIndex][counts[wordIndex]++] = i;
        }

        return new Context(tokens, occurrenceWords, occurrenceIndexes);
    }

    private static String normalizeLetters(String value, boolean allowEmpty) {
        if (value == null || (!allowEmpty && value.length() == 0)) {
            throw new IllegalArgumentException(
                    "Expected " + (allowEmpty ? "an ASCII-letter prefix"
                                             : "a nonempty ASCII-letter word"));
        }

        char[] letters = new char[value.length()];
        for (int i = 0; i < value.length(); i++) {
            char letter = value.charAt(i);
            if (!isAsciiLetter(letter)) {
                throw new IllegalArgumentException("Only ASCII letters are allowed");
            }
            letters[i] = lowercase(letter);
        }
        return new String(letters);
    }

    private static boolean isAsciiLetter(char value) {
        return (value >= 'A' && value <= 'Z')
                || (value >= 'a' && value <= 'z');
    }

    private static char lowercase(char value) {
        return value >= 'A' && value <= 'Z'
                ? (char) (value + ('a' - 'A'))
                : value;
    }

    private static String[] copyStrings(String[] source, int length) {
        String[] copy = new String[length];
        for (int i = 0; i < length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static String[] uniqueSorted(String[] sorted) {
        if (sorted.length == 0) {
            return sorted;
        }

        int count = 1;
        for (int i = 1; i < sorted.length; i++) {
            if (!sorted[i].equals(sorted[count - 1])) {
                sorted[count++] = sorted[i];
            }
        }
        return count == sorted.length ? sorted : copyStrings(sorted, count);
    }

    private static int binarySearch(String[] sorted, String target) {
        int low = 0;
        int high = sorted.length - 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int comparison = sorted[middle].compareTo(target);
            if (comparison < 0) {
                low = middle + 1;
            } else if (comparison > 0) {
                high = middle - 1;
            } else {
                return middle;
            }
        }
        return -1;
    }

    private static void sort(String[] values, boolean lengthFirst) {
        if (values.length > 1) {
            mergeSort(values, new String[values.length],
                    0, values.length, lengthFirst);
        }
    }

    private static void mergeSort(
            String[] values,
            String[] workspace,
            int start,
            int end,
            boolean lengthFirst) {
        if (end - start < 2) {
            return;
        }

        int middle = start + (end - start) / 2;
        mergeSort(values, workspace, start, middle, lengthFirst);
        mergeSort(values, workspace, middle, end, lengthFirst);

        int left = start;
        int right = middle;
        int next = start;
        while (left < middle && right < end) {
            if (compare(values[left], values[right], lengthFirst) <= 0) {
                workspace[next++] = values[left++];
            } else {
                workspace[next++] = values[right++];
            }
        }
        while (left < middle) {
            workspace[next++] = values[left++];
        }
        while (right < end) {
            workspace[next++] = values[right++];
        }
        for (int i = start; i < end; i++) {
            values[i] = workspace[i];
        }
    }

    private static int compare(String left, String right, boolean lengthFirst) {
        if (lengthFirst && left.length() != right.length()) {
            return left.length() > right.length() ? -1 : 1;
        }
        return left.compareTo(right);
    }
}
