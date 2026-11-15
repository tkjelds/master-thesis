@LexicalGraphProvider.ThreadSafe
public class LexicalGraphProvider {
    @interface ThreadSafe {}

    private final Object stateMonitor = new Object();
    private State current = new State(
            new String[0], new String[0], new String[0], new int[0][],
            0L, 0L);

    public void replaceVocabulary(String[] words) {
        if (words == null) {
            throw new IllegalArgumentException("Vocabulary must not be null");
        }

        String[] preparedVocabulary = prepareVocabulary(words);

        synchronized (stateMonitor) {
            if (current.vocabularyRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("Vocabulary revision overflow");
            }
            current = new State(
                    preparedVocabulary,
                    current.tokens,
                    current.occurrenceWords,
                    current.occurrenceIndexes,
                    current.vocabularyRevision + 1L,
                    current.contextGeneration);
        }
    }

    public void setContext(String sentence) {
        if (sentence == null) {
            throw new IllegalArgumentException("Context must not be null");
        }

        PreparedContext preparedContext = prepareContext(sentence);

        synchronized (stateMonitor) {
            if (current.contextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Context generation overflow");
            }
            current = new State(
                    current.vocabulary,
                    preparedContext.tokens,
                    preparedContext.occurrenceWords,
                    preparedContext.occurrenceIndexes,
                    current.vocabularyRevision,
                    current.contextGeneration + 1L);
        }
    }

    public Session openSession() {
        State captured;
        synchronized (stateMonitor) {
            captured = current;
        }
        return new Session(captured);
    }

    public Snapshot snapshot() {
        State captured;
        synchronized (stateMonitor) {
            captured = current;
        }
        return new Snapshot(captured);
    }

    public static final class Session {
        private final State pinned;

        private Session(State pinned) {
            this.pinned = pinned;
        }

        public String[] findMatches(String prefix) {
            String normalizedPrefix = normalizePrefix(prefix);
            String[] matches = new String[pinned.vocabulary.length];
            int count = 0;

            for (String word : pinned.vocabulary) {
                if (word.startsWith(normalizedPrefix)
                        && hasOccurrences(pinned, word)) {
                    matches[count++] = word;
                }
            }

            for (int i = 1; i < count; i++) {
                String value = matches[i];
                int j = i - 1;
                while (j >= 0 && comesBefore(value, matches[j])) {
                    matches[j + 1] = matches[j];
                    j--;
                }
                matches[j + 1] = value;
            }

            String[] result = new String[count];
            System.arraycopy(matches, 0, result, 0, count);
            return result;
        }

        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= pinned.tokens.length) {
                throw new IllegalArgumentException("Invalid token index");
            }

            int count = 0;
            for (int i = tokenIndex + 1; i < pinned.tokens.length; i++) {
                if (binarySearch(pinned.vocabulary, pinned.tokens[i]) >= 0) {
                    count++;
                }
            }

            int[] result = new int[count];
            int output = 0;
            for (int i = tokenIndex + 1; i < pinned.tokens.length; i++) {
                if (binarySearch(pinned.vocabulary, pinned.tokens[i]) >= 0) {
                    result[output++] = i;
                }
            }
            return result;
        }

        public Snapshot snapshot() {
            return new Snapshot(pinned);
        }
    }

    public static final class Snapshot {
        public final String[] vocabulary;
        public final String[] tokens;
        public final String[] occurrenceWords;
        public final int[][] occurrenceIndexes;
        public final long vocabularyRevision;
        public final long contextGeneration;

        private Snapshot(State state) {
            this.vocabulary = state.vocabulary.clone();
            this.tokens = state.tokens.clone();
            this.occurrenceWords = state.occurrenceWords.clone();
            this.occurrenceIndexes = deepCopy(state.occurrenceIndexes);
            this.vocabularyRevision = state.vocabularyRevision;
            this.contextGeneration = state.contextGeneration;
        }

        public String[] getVocabulary() {
            return vocabulary.clone();
        }

        public String[] getTokens() {
            return tokens.clone();
        }

        public String[] getOccurrenceWords() {
            return occurrenceWords.clone();
        }

        public int[][] getOccurrenceIndexes() {
            return deepCopy(occurrenceIndexes);
        }
    }

    private static final class State {
        final String[] vocabulary;
        final String[] tokens;
        final String[] occurrenceWords;
        final int[][] occurrenceIndexes;
        final long vocabularyRevision;
        final long contextGeneration;

        State(String[] vocabulary, String[] tokens,
              String[] occurrenceWords, int[][] occurrenceIndexes,
              long vocabularyRevision, long contextGeneration) {
            this.vocabulary = vocabulary;
            this.tokens = tokens;
            this.occurrenceWords = occurrenceWords;
            this.occurrenceIndexes = occurrenceIndexes;
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }
    }

    private static final class PreparedContext {
        final String[] tokens;
        final String[] occurrenceWords;
        final int[][] occurrenceIndexes;

        PreparedContext(String[] tokens, String[] occurrenceWords,
                        int[][] occurrenceIndexes) {
            this.tokens = tokens;
            this.occurrenceWords = occurrenceWords;
            this.occurrenceIndexes = occurrenceIndexes;
        }
    }

    private static String[] prepareVocabulary(String[] words) {
        String[] normalized = new String[words.length];

        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word == null || word.length() == 0) {
                throw new IllegalArgumentException("Vocabulary entries must be nonempty");
            }

            char[] characters = new char[word.length()];
            for (int j = 0; j < word.length(); j++) {
                char c = word.charAt(j);
                if (!isAsciiLetter(c)) {
                    throw new IllegalArgumentException("Invalid vocabulary entry");
                }
                characters[j] = toLowerAscii(c);
            }
            normalized[i] = new String(characters);
        }

        sortStrings(normalized);
        int uniqueCount = 0;
        for (int i = 0; i < normalized.length; i++) {
            if (i == 0 || !normalized[i].equals(normalized[i - 1])) {
                normalized[uniqueCount++] = normalized[i];
            }
        }

        String[] result = new String[uniqueCount];
        System.arraycopy(normalized, 0, result, 0, uniqueCount);
        return result;
    }

    private static PreparedContext prepareContext(String sentence) {
        int tokenCount = 0;
        boolean inRun = false;

        for (int i = 0; i < sentence.length(); i++) {
            if (isAsciiLetter(sentence.charAt(i))) {
                if (!inRun) {
                    tokenCount++;
                    inRun = true;
                }
            } else {
                inRun = false;
            }
        }

        String[] tokens = new String[tokenCount];
        int tokenPosition = 0;
        int runStart = -1;

        for (int i = 0; i <= sentence.length(); i++) {
            boolean letter = i < sentence.length()
                    && isAsciiLetter(sentence.charAt(i));
            if (letter) {
                if (runStart < 0) {
                    runStart = i;
                }
            } else if (runStart >= 0) {
                int length = i - runStart;
                char[] characters = new char[length];
                for (int j = 0; j < length; j++) {
                    characters[j] = toLowerAscii(sentence.charAt(runStart + j));
                }
                tokens[tokenPosition++] = new String(characters);
                runStart = -1;
            }
        }

        String[] sortedTokens = tokens.clone();
        sortStrings(sortedTokens);

        int uniqueCount = 0;
        for (int i = 0; i < sortedTokens.length; i++) {
            if (i == 0 || !sortedTokens[i].equals(sortedTokens[i - 1])) {
                sortedTokens[uniqueCount++] = sortedTokens[i];
            }
        }

        String[] occurrenceWords = new String[uniqueCount];
        System.arraycopy(sortedTokens, 0, occurrenceWords, 0, uniqueCount);

        int[] counts = new int[uniqueCount];
        for (String token : tokens) {
            int position = binarySearch(occurrenceWords, token);
            counts[position]++;
        }

        int[][] occurrenceIndexes = new int[uniqueCount][];
        for (int i = 0; i < uniqueCount; i++) {
            occurrenceIndexes[i] = new int[counts[i]];
        }

        int[] nextPositions = new int[uniqueCount];
        for (int i = 0; i < tokens.length; i++) {
            int wordPosition = binarySearch(occurrenceWords, tokens[i]);
            occurrenceIndexes[wordPosition][nextPositions[wordPosition]++] = i;
        }

        return new PreparedContext(tokens, occurrenceWords, occurrenceIndexes);
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null) {
            throw new IllegalArgumentException("Prefix must not be null");
        }

        char[] characters = new char[prefix.length()];
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (!isAsciiLetter(c)) {
                throw new IllegalArgumentException("Invalid prefix");
            }
            characters[i] = toLowerAscii(c);
        }
        return new String(characters);
    }

    private static boolean hasOccurrences(State state, String word) {
        int position = binarySearch(state.occurrenceWords, word);
        return position >= 0 && state.occurrenceIndexes[position].length > 0;
    }

    private static boolean comesBefore(String first, String second) {
        if (first.length() != second.length()) {
            return first.length() > second.length();
        }
        return first.compareTo(second) < 0;
    }

    private static int binarySearch(String[] values, String target) {
        int low = 0;
        int high = values.length - 1;

        while (low <= high) {
            int middle = low + ((high - low) >>> 1);
            int comparison = values[middle].compareTo(target);
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

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static char toLowerAscii(char c) {
        if (c >= 'A' && c <= 'Z') {
            return (char) (c + ('a' - 'A'));
        }
        return c;
    }

    private static void sortStrings(String[] values) {
        int length = values.length;
        for (int start = length / 2 - 1; start >= 0; start--) {
            siftDown(values, start, length);
        }

        for (int end = length - 1; end > 0; end--) {
            String temporary = values[0];
            values[0] = values[end];
            values[end] = temporary;
            siftDown(values, 0, end);
        }
    }

    private static void siftDown(String[] values, int root, int size) {
        while (root < size / 2) {
            int child = root * 2 + 1;
            if (child + 1 < size
                    && values[child].compareTo(values[child + 1]) < 0) {
                child++;
            }
            if (values[root].compareTo(values[child]) >= 0) {
                return;
            }

            String temporary = values[root];
            values[root] = values[child];
            values[child] = temporary;
            root = child;
        }
    }

    private static int[][] deepCopy(int[][] values) {
        int[][] copy = new int[values.length][];
        for (int i = 0; i < values.length; i++) {
            copy[i] = values[i].clone();
        }
        return copy;
    }
}
