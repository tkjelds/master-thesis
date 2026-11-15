@LexicalGraphProvider.ThreadSafe
public class LexicalGraphProvider {
    @interface ThreadSafe {}

    private final Object stateMonitor = new Object();

    private String[] vocabulary = new String[0];
    private String[] tokens = new String[0];
    private OccurrenceIndex occurrenceIndex = new OccurrenceIndex(new String[0], new int[0][]);
    private long vocabularyRevision;
    private long contextGeneration;

    public void replaceVocabulary(String[] words) {
        if (words == null) {
            throw new IllegalArgumentException("words must not be null");
        }

        String[] prepared = new String[words.length];
        int count = 0;
        for (String word : words) {
            if (word == null || word.length() == 0 || !isAsciiLetters(word)) {
                throw new IllegalArgumentException("vocabulary entries must be nonempty ASCII letter strings");
            }

            String normalized = lowercaseAscii(word);
            if (!contains(prepared, count, normalized)) {
                prepared[count++] = normalized;
            }
        }

        String[] completeVocabulary = copyOf(prepared, count);
        synchronized (stateMonitor) {
            if (vocabularyRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("vocabulary revision overflow");
            }
            vocabulary = completeVocabulary;
            vocabularyRevision++;
        }
    }

    public void setContext(String sentence) {
        if (sentence == null) {
            throw new IllegalArgumentException("sentence must not be null");
        }

        String[] preparedTokens = tokenize(sentence);
        OccurrenceIndex preparedIndex = buildOccurrenceIndex(preparedTokens);

        synchronized (stateMonitor) {
            if (contextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("context generation overflow");
            }
            tokens = preparedTokens;
            occurrenceIndex = preparedIndex;
            contextGeneration++;
        }
    }

    public Session openSession() {
        CapturedState captured;
        synchronized (stateMonitor) {
            captured = captureState();
        }
        return new Session(captured);
    }

    public Snapshot snapshot() {
        CapturedState captured;
        synchronized (stateMonitor) {
            captured = captureState();
        }
        return new Snapshot(
                captured.vocabulary,
                captured.tokens,
                captured.occurrenceIndex.words,
                captured.occurrenceIndex.indexes,
                captured.vocabularyRevision,
                captured.contextGeneration);
    }

    private CapturedState captureState() {
        return new CapturedState(
                vocabulary,
                tokens,
                occurrenceIndex,
                vocabularyRevision,
                contextGeneration);
    }

    private static String[] tokenize(String sentence) {
        String[] result = new String[sentence.length()];
        int count = 0;
        int position = 0;

        while (position < sentence.length()) {
            if (!isAsciiLetter(sentence.charAt(position))) {
                position++;
                continue;
            }

            int start = position;
            while (position < sentence.length() && isAsciiLetter(sentence.charAt(position))) {
                position++;
            }

            char[] normalized = new char[position - start];
            for (int i = start; i < position; i++) {
                normalized[i - start] = lowercaseAscii(sentence.charAt(i));
            }
            result[count++] = new String(normalized);
        }

        return copyOf(result, count);
    }

    private static OccurrenceIndex buildOccurrenceIndex(String[] tokens) {
        String[] words = new String[tokens.length];
        int[] counts = new int[tokens.length];
        int wordCount = 0;

        for (String token : tokens) {
            int wordIndex = indexOf(words, wordCount, token);
            if (wordIndex < 0) {
                wordIndex = wordCount++;
                words[wordIndex] = token;
            }
            counts[wordIndex]++;
        }

        String[] uniqueWords = copyOf(words, wordCount);
        int[][] indexes = new int[wordCount][];
        for (int i = 0; i < wordCount; i++) {
            indexes[i] = new int[counts[i]];
            counts[i] = 0;
        }

        for (int tokenIndex = 0; tokenIndex < tokens.length; tokenIndex++) {
            int wordIndex = indexOf(uniqueWords, tokenCount(uniqueWords), tokens[tokenIndex]);
            indexes[wordIndex][counts[wordIndex]++] = tokenIndex;
        }

        return new OccurrenceIndex(uniqueWords, indexes);
    }

    private static int tokenCount(String[] words) {
        return words.length;
    }

    private static boolean contains(String[] values, int length, String target) {
        return indexOf(values, length, target) >= 0;
    }

    private static int indexOf(String[] values, int length, String target) {
        for (int i = 0; i < length; i++) {
            if (values[i].equals(target)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isAsciiLetters(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!isAsciiLetter(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiLetter(char value) {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
    }

    private static char lowercaseAscii(char value) {
        return value >= 'A' && value <= 'Z' ? (char) (value + ('a' - 'A')) : value;
    }

    private static String lowercaseAscii(String value) {
        char[] normalized = new char[value.length()];
        for (int i = 0; i < value.length(); i++) {
            normalized[i] = lowercaseAscii(value.charAt(i));
        }
        return new String(normalized);
    }

    private static String[] copyOf(String[] values, int length) {
        String[] result = new String[length];
        for (int i = 0; i < length; i++) {
            result[i] = values[i];
        }
        return result;
    }

    private static int[] copyOf(int[] values) {
        int[] result = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i];
        }
        return result;
    }

    private static int[][] copyOf(int[][] values) {
        int[][] result = new int[values.length][];
        for (int i = 0; i < values.length; i++) {
            result[i] = copyOf(values[i]);
        }
        return result;
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

    private static boolean occursIn(OccurrenceIndex index, String word) {
        return indexOf(index.words, index.words.length, word) >= 0;
    }

    private static boolean isVocabularyWord(String[] vocabulary, String word) {
        return contains(vocabulary, vocabulary.length, word);
    }

    private static int compareLexically(String left, String right) {
        int sharedLength = left.length() < right.length() ? left.length() : right.length();
        for (int i = 0; i < sharedLength; i++) {
            if (left.charAt(i) != right.charAt(i)) {
                return left.charAt(i) < right.charAt(i) ? -1 : 1;
            }
        }
        if (left.length() == right.length()) {
            return 0;
        }
        return left.length() < right.length() ? -1 : 1;
    }

    private static boolean comesBefore(String left, String right) {
        if (left.length() != right.length()) {
            return left.length() > right.length();
        }
        return compareLexically(left, right) < 0;
    }

    public static final class Session {
        private final String[] vocabulary;
        private final String[] tokens;
        private final OccurrenceIndex occurrenceIndex;
        private final long vocabularyRevision;
        private final long contextGeneration;

        private Session(CapturedState captured) {
            this.vocabulary = captured.vocabulary;
            this.tokens = captured.tokens;
            this.occurrenceIndex = captured.occurrenceIndex;
            this.vocabularyRevision = captured.vocabularyRevision;
            this.contextGeneration = captured.contextGeneration;
        }

        public String[] findMatches(String prefix) {
            if (prefix == null || !isAsciiLetters(prefix)) {
                throw new IllegalArgumentException("prefix must contain only ASCII letters");
            }

            String normalizedPrefix = lowercaseAscii(prefix);
            String[] matches = new String[vocabulary.length];
            int count = 0;

            for (String word : vocabulary) {
                if (startsWith(word, normalizedPrefix) && occursIn(occurrenceIndex, word)) {
                    matches[count++] = word;
                }
            }

            for (int i = 1; i < count; i++) {
                String value = matches[i];
                int position = i;
                while (position > 0 && comesBefore(value, matches[position - 1])) {
                    matches[position] = matches[position - 1];
                    position--;
                }
                matches[position] = value;
            }

            return copyOf(matches, count);
        }

        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= tokens.length) {
                throw new IllegalArgumentException("token index is out of range");
            }

            int count = 0;
            for (int i = tokenIndex + 1; i < tokens.length; i++) {
                if (isVocabularyWord(vocabulary, tokens[i])) {
                    count++;
                }
            }

            int[] result = new int[count];
            int next = 0;
            for (int i = tokenIndex + 1; i < tokens.length; i++) {
                if (isVocabularyWord(vocabulary, tokens[i])) {
                    result[next++] = i;
                }
            }
            return result;
        }

        public Snapshot snapshot() {
            return new Snapshot(
                    vocabulary,
                    tokens,
                    occurrenceIndex.words,
                    occurrenceIndex.indexes,
                    vocabularyRevision,
                    contextGeneration);
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
                long contextGeneration) {
            this.vocabulary = copyOf(vocabulary, vocabulary.length);
            this.tokens = copyOf(tokens, tokens.length);
            this.occurrenceWords = copyOf(occurrenceWords, occurrenceWords.length);
            this.occurrenceIndexes = copyOf(occurrenceIndexes);
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }

        public String[] getVocabulary() {
            return copyOf(vocabulary, vocabulary.length);
        }

        public String[] getTokens() {
            return copyOf(tokens, tokens.length);
        }

        public String[] getOccurrenceWords() {
            return copyOf(occurrenceWords, occurrenceWords.length);
        }

        public int[][] getOccurrenceIndexes() {
            return copyOf(occurrenceIndexes);
        }

        public long getVocabularyRevision() {
            return vocabularyRevision;
        }

        public long getContextGeneration() {
            return contextGeneration;
        }
    }

    private static final class OccurrenceIndex {
        private final String[] words;
        private final int[][] indexes;

        private OccurrenceIndex(String[] words, int[][] indexes) {
            this.words = words;
            this.indexes = indexes;
        }
    }

    private static final class CapturedState {
        private final String[] vocabulary;
        private final String[] tokens;
        private final OccurrenceIndex occurrenceIndex;
        private final long vocabularyRevision;
        private final long contextGeneration;

        private CapturedState(
                String[] vocabulary,
                String[] tokens,
                OccurrenceIndex occurrenceIndex,
                long vocabularyRevision,
                long contextGeneration) {
            this.vocabulary = vocabulary;
            this.tokens = tokens;
            this.occurrenceIndex = occurrenceIndex;
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }
    }
}
