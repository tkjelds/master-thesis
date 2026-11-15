@LexicalGraphProvider.ThreadSafe
public final class LexicalGraphProvider {
    @interface ThreadSafe {}

    private final Object stateMonitor = new Object();
    private String[] vocabulary = new String[0];
    private ContextState context = new ContextState(new String[0], new String[0], new int[0][]);
    private long vocabularyRevision;
    private long contextGeneration;

    public void replaceVocabulary(String[] words) {
        if (words == null) {
            throw new IllegalArgumentException("words must not be null");
        }

        String[] prepared = new String[words.length];
        for (int i = 0; i < words.length; i++) {
            if (words[i] == null || words[i].length() == 0) {
                throw new IllegalArgumentException("vocabulary words must be nonempty");
            }
            prepared[i] = normalizeLetters(words[i]);
        }

        sortStrings(prepared);
        int uniqueCount = 0;
        for (String word : prepared) {
            if (uniqueCount == 0 || !prepared[uniqueCount - 1].equals(word)) {
                prepared[uniqueCount++] = word;
            }
        }
        String[] nextVocabulary = new String[uniqueCount];
        for (int i = 0; i < uniqueCount; i++) {
            nextVocabulary[i] = prepared[i];
        }

        synchronized (stateMonitor) {
            if (vocabularyRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("vocabulary revision overflow");
            }
            vocabulary = nextVocabulary;
            vocabularyRevision++;
        }
    }

    public void setContext(String sentence) {
        if (sentence == null) {
            throw new IllegalArgumentException("sentence must not be null");
        }

        ContextState nextContext = prepareContext(sentence);

        synchronized (stateMonitor) {
            if (contextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("context generation overflow");
            }
            context = nextContext;
            contextGeneration++;
        }
    }

    public Session openSession() {
        synchronized (stateMonitor) {
            return new Session(
                vocabulary,
                context,
                vocabularyRevision,
                contextGeneration
            );
        }
    }

    public Snapshot snapshot() {
        String[] capturedVocabulary;
        ContextState capturedContext;
        long capturedVocabularyRevision;
        long capturedContextGeneration;

        synchronized (stateMonitor) {
            capturedVocabulary = vocabulary;
            capturedContext = context;
            capturedVocabularyRevision = vocabularyRevision;
            capturedContextGeneration = contextGeneration;
        }

        return new Snapshot(
            capturedVocabulary,
            capturedContext.tokens,
            capturedContext.occurrenceWords,
            capturedContext.occurrenceIndexes,
            capturedVocabularyRevision,
            capturedContextGeneration
        );
    }

    public static final class Session {
        private final String[] vocabulary;
        private final ContextState context;
        private final long vocabularyRevision;
        private final long contextGeneration;

        private Session(
            String[] vocabulary,
            ContextState context,
            long vocabularyRevision,
            long contextGeneration
        ) {
            this.vocabulary = vocabulary;
            this.context = context;
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }

        public String[] findMatches(String prefix) {
            if (prefix == null) {
                throw new IllegalArgumentException("prefix must not be null");
            }
            String normalizedPrefix = normalizeLetters(prefix);

            String[] matches = new String[vocabulary.length];
            int matchCount = 0;
            for (String word : vocabulary) {
                if (startsWith(word, normalizedPrefix)
                    && findString(context.occurrenceWords, word) >= 0) {
                    matches[matchCount++] = word;
                }
            }

            for (int i = 1; i < matchCount; i++) {
                String value = matches[i];
                int j = i - 1;
                while (j >= 0 && compareMatchOrder(value, matches[j]) < 0) {
                    matches[j + 1] = matches[j];
                    j--;
                }
                matches[j + 1] = value;
            }

            String[] result = new String[matchCount];
            for (int i = 0; i < matchCount; i++) {
                result[i] = matches[i];
            }
            return result;
        }

        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= context.tokens.length) {
                throw new IllegalArgumentException("invalid token index");
            }

            int count = 0;
            for (int i = tokenIndex + 1; i < context.tokens.length; i++) {
                if (findString(vocabulary, context.tokens[i]) >= 0) {
                    count++;
                }
            }

            int[] result = new int[count];
            int position = 0;
            for (int i = tokenIndex + 1; i < context.tokens.length; i++) {
                if (findString(vocabulary, context.tokens[i]) >= 0) {
                    result[position++] = i;
                }
            }
            return result;
        }

        public Snapshot snapshot() {
            return new Snapshot(
                vocabulary,
                context.tokens,
                context.occurrenceWords,
                context.occurrenceIndexes,
                vocabularyRevision,
                contextGeneration
            );
        }
    }

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
            long contextGeneration
        ) {
            this.vocabulary = copyStrings(vocabulary);
            this.tokens = copyStrings(tokens);
            this.occurrenceWords = copyStrings(occurrenceWords);
            this.occurrenceIndexes = copyIndexes(occurrenceIndexes);
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }

        public String[] getVocabulary() {
            return copyStrings(vocabulary);
        }

        public String[] getTokens() {
            return copyStrings(tokens);
        }

        public String[] getOccurrenceWords() {
            return copyStrings(occurrenceWords);
        }

        public int[][] getOccurrenceIndexes() {
            return copyIndexes(occurrenceIndexes);
        }

        public long getVocabularyRevision() {
            return vocabularyRevision;
        }

        public long getContextGeneration() {
            return contextGeneration;
        }
    }

    private static final class ContextState {
        private final String[] tokens;
        private final String[] occurrenceWords;
        private final int[][] occurrenceIndexes;

        private ContextState(
            String[] tokens,
            String[] occurrenceWords,
            int[][] occurrenceIndexes
        ) {
            this.tokens = tokens;
            this.occurrenceWords = occurrenceWords;
            this.occurrenceIndexes = occurrenceIndexes;
        }
    }

    private static ContextState prepareContext(String sentence) {
        int tokenCount = 0;
        boolean inLetters = false;
        for (int i = 0; i < sentence.length(); i++) {
            boolean isLetter = isAsciiLetter(sentence.charAt(i));
            if (isLetter && !inLetters) {
                tokenCount++;
            }
            inLetters = isLetter;
        }

        String[] tokens = new String[tokenCount];
        int tokenPosition = 0;
        int start = -1;
        for (int i = 0; i <= sentence.length(); i++) {
            boolean isLetter = i < sentence.length() && isAsciiLetter(sentence.charAt(i));
            if (isLetter && start < 0) {
                start = i;
            } else if (!isLetter && start >= 0) {
                tokens[tokenPosition++] = normalizeLetters(sentence.substring(start, i));
                start = -1;
            }
        }

        String[] distinctWords = new String[tokens.length];
        int distinctCount = 0;
        for (String token : tokens) {
            boolean found = false;
            for (int i = 0; i < distinctCount; i++) {
                if (distinctWords[i].equals(token)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                distinctWords[distinctCount++] = token;
            }
        }

        String[] occurrenceWords = new String[distinctCount];
        for (int i = 0; i < distinctCount; i++) {
            occurrenceWords[i] = distinctWords[i];
        }
        sortStrings(occurrenceWords);

        int[][] occurrenceIndexes = new int[occurrenceWords.length][];
        int[] counts = new int[occurrenceWords.length];
        for (String token : tokens) {
            counts[findString(occurrenceWords, token)]++;
        }
        for (int i = 0; i < occurrenceWords.length; i++) {
            occurrenceIndexes[i] = new int[counts[i]];
            counts[i] = 0;
        }
        for (int i = 0; i < tokens.length; i++) {
            int wordIndex = findString(occurrenceWords, tokens[i]);
            occurrenceIndexes[wordIndex][counts[wordIndex]++] = i;
        }

        return new ContextState(tokens, occurrenceWords, occurrenceIndexes);
    }

    private static String normalizeLetters(String value) {
        char[] normalized = new char[value.length()];
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!isAsciiLetter(c)) {
                throw new IllegalArgumentException("value must contain only ASCII letters");
            }
            normalized[i] = c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
        }
        return new String(normalized);
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static boolean startsWith(String value, String prefix) {
        if (prefix.length() > value.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (value.charAt(i) != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int compareMatchOrder(String left, String right) {
        if (left.length() != right.length()) {
            return right.length() - left.length();
        }
        return left.compareTo(right);
    }

    private static int findString(String[] values, String target) {
        int low = 0;
        int high = values.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int comparison = values[middle].compareTo(target);
            if (comparison == 0) {
                return middle;
            }
            if (comparison < 0) {
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return -1;
    }

    private static void sortStrings(String[] values) {
        for (int i = 1; i < values.length; i++) {
            String value = values[i];
            int j = i - 1;
            while (j >= 0 && values[j].compareTo(value) > 0) {
                values[j + 1] = values[j];
                j--;
            }
            values[j + 1] = value;
        }
    }

    private static String[] copyStrings(String[] source) {
        String[] copy = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static int[][] copyIndexes(int[][] source) {
        int[][] copy = new int[source.length][];
        for (int i = 0; i < source.length; i++) {
            int[] row = new int[source[i].length];
            for (int j = 0; j < source[i].length; j++) {
                row[j] = source[i][j];
            }
            copy[i] = row;
        }
        return copy;
    }
}
