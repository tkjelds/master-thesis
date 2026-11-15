@LexicalGraphProvider.ThreadSafe
public final class LexicalGraphProvider {
    @interface ThreadSafe {}

    private final Object stateMonitor = new Object();

    private String[] vocabulary = new String[0];
    private String[] tokens = new String[0];
    private String[] occurrenceWords = new String[0];
    private int[][] occurrenceIndexes = new int[0][];
    private long vocabularyRevision;
    private long contextGeneration;

    public LexicalGraphProvider() {
    }

    public void replaceVocabulary(String[] words) {
        if (words == null) {
            throw new IllegalArgumentException("words must not be null");
        }

        String[] copiedWords = words.clone();
        for (int i = 0; i < copiedWords.length; i++) {
            if (copiedWords[i] == null || copiedWords[i].length() == 0) {
                throw new IllegalArgumentException("vocabulary words must be nonempty");
            }
            copiedWords[i] = normalizeLetters(copiedWords[i]);
        }

        String[] preparedVocabulary = uniqueSorted(copiedWords);

        synchronized (stateMonitor) {
            if (vocabularyRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("vocabulary revision overflow");
            }
            vocabulary = preparedVocabulary;
            vocabularyRevision++;
        }
    }

    public void setContext(String sentence) {
        if (sentence == null) {
            throw new IllegalArgumentException("sentence must not be null");
        }

        String[] preparedTokens = tokenize(sentence);
        String[] sortedWords = preparedTokens.clone();
        sortStrings(sortedWords);

        int uniqueCount = 0;
        for (int i = 0; i < sortedWords.length; i++) {
            if (i == 0 || !sortedWords[i].equals(sortedWords[i - 1])) {
                uniqueCount++;
            }
        }

        String[] preparedOccurrenceWords = new String[uniqueCount];
        int wordPosition = 0;
        for (int i = 0; i < sortedWords.length; i++) {
            if (i == 0 || !sortedWords[i].equals(sortedWords[i - 1])) {
                preparedOccurrenceWords[wordPosition++] = sortedWords[i];
            }
        }

        int[] counts = new int[uniqueCount];
        for (int i = 0; i < preparedTokens.length; i++) {
            int wordIndex = binarySearch(preparedOccurrenceWords, preparedTokens[i]);
            counts[wordIndex]++;
        }

        int[][] preparedOccurrenceIndexes = new int[uniqueCount][];
        for (int i = 0; i < uniqueCount; i++) {
            preparedOccurrenceIndexes[i] = new int[counts[i]];
        }

        int[] positions = new int[uniqueCount];
        for (int i = 0; i < preparedTokens.length; i++) {
            int wordIndex = binarySearch(preparedOccurrenceWords, preparedTokens[i]);
            preparedOccurrenceIndexes[wordIndex][positions[wordIndex]++] = i;
        }

        synchronized (stateMonitor) {
            if (contextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("context generation overflow");
            }
            tokens = preparedTokens;
            occurrenceWords = preparedOccurrenceWords;
            occurrenceIndexes = preparedOccurrenceIndexes;
            contextGeneration++;
        }
    }

    public Session openSession() {
        synchronized (stateMonitor) {
            return new Session(
                    vocabulary,
                    tokens,
                    occurrenceWords,
                    occurrenceIndexes,
                    vocabularyRevision,
                    contextGeneration);
        }
    }

    public Snapshot snapshot() {
        String[] capturedVocabulary;
        String[] capturedTokens;
        String[] capturedOccurrenceWords;
        int[][] capturedOccurrenceIndexes;
        long capturedVocabularyRevision;
        long capturedContextGeneration;

        synchronized (stateMonitor) {
            capturedVocabulary = vocabulary;
            capturedTokens = tokens;
            capturedOccurrenceWords = occurrenceWords;
            capturedOccurrenceIndexes = occurrenceIndexes;
            capturedVocabularyRevision = vocabularyRevision;
            capturedContextGeneration = contextGeneration;
        }

        return new Snapshot(
                capturedVocabulary,
                capturedTokens,
                capturedOccurrenceWords,
                capturedOccurrenceIndexes,
                capturedVocabularyRevision,
                capturedContextGeneration);
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
            if (prefix == null) {
                throw new IllegalArgumentException("prefix must not be null");
            }
            String normalizedPrefix = normalizeLetters(prefix);

            String[] matches = new String[vocabulary.length];
            int matchCount = 0;

            for (int i = 0; i < vocabulary.length; i++) {
                String word = vocabulary[i];
                if (word.startsWith(normalizedPrefix)
                        && binarySearch(occurrenceWords, word) >= 0) {
                    matches[matchCount++] = word;
                }
            }

            String[] result = new String[matchCount];
            for (int i = 0; i < matchCount; i++) {
                result[i] = matches[i];
            }
            sortMatches(result);
            return result;
        }

        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= tokens.length) {
                throw new IllegalArgumentException("invalid token index");
            }

            int count = 0;
            for (int i = tokenIndex + 1; i < tokens.length; i++) {
                if (binarySearch(vocabulary, tokens[i]) >= 0) {
                    count++;
                }
            }

            int[] result = new int[count];
            int position = 0;
            for (int i = tokenIndex + 1; i < tokens.length; i++) {
                if (binarySearch(vocabulary, tokens[i]) >= 0) {
                    result[position++] = i;
                }
            }
            return result;
        }

        public Snapshot snapshot() {
            return new Snapshot(
                    vocabulary,
                    tokens,
                    occurrenceWords,
                    occurrenceIndexes,
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
            this.vocabulary = vocabulary.clone();
            this.tokens = tokens.clone();
            this.occurrenceWords = occurrenceWords.clone();
            this.occurrenceIndexes = copyMatrix(occurrenceIndexes);
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }

        public String[] vocabulary() {
            return vocabulary.clone();
        }

        public String[] tokens() {
            return tokens.clone();
        }

        public String[] occurrenceWords() {
            return occurrenceWords.clone();
        }

        public int[][] occurrenceIndexes() {
            return copyMatrix(occurrenceIndexes);
        }

        public long vocabularyRevision() {
            return vocabularyRevision;
        }

        public long contextGeneration() {
            return contextGeneration;
        }
    }

    private static String normalizeLetters(String value) {
        char[] normalized = new char[value.length()];
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!isAsciiLetter(c)) {
                throw new IllegalArgumentException("value must contain only ASCII letters");
            }
            normalized[i] = toLowerAscii(c);
        }
        return new String(normalized);
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

    private static String[] tokenize(String sentence) {
        int count = 0;
        boolean inRun = false;
        for (int i = 0; i < sentence.length(); i++) {
            boolean letter = isAsciiLetter(sentence.charAt(i));
            if (letter && !inRun) {
                count++;
            }
            inRun = letter;
        }

        String[] result = new String[count];
        int tokenIndex = 0;
        int start = -1;
        for (int i = 0; i <= sentence.length(); i++) {
            boolean letter = i < sentence.length() && isAsciiLetter(sentence.charAt(i));
            if (letter && start < 0) {
                start = i;
            } else if (!letter && start >= 0) {
                char[] token = new char[i - start];
                for (int j = start; j < i; j++) {
                    token[j - start] = toLowerAscii(sentence.charAt(j));
                }
                result[tokenIndex++] = new String(token);
                start = -1;
            }
        }
        return result;
    }

    private static String[] uniqueSorted(String[] values) {
        sortStrings(values);
        int uniqueCount = 0;
        for (int i = 0; i < values.length; i++) {
            if (i == 0 || !values[i].equals(values[i - 1])) {
                uniqueCount++;
            }
        }

        String[] result = new String[uniqueCount];
        int position = 0;
        for (int i = 0; i < values.length; i++) {
            if (i == 0 || !values[i].equals(values[i - 1])) {
                result[position++] = values[i];
            }
        }
        return result;
    }

    private static void sortStrings(String[] values) {
        if (values.length < 2) {
            return;
        }
        String[] temporary = new String[values.length];
        mergeSort(values, temporary, 0, values.length);
    }

    private static void mergeSort(String[] values, String[] temporary, int start, int end) {
        if (end - start < 2) {
            return;
        }

        int middle = start + (end - start) / 2;
        mergeSort(values, temporary, start, middle);
        mergeSort(values, temporary, middle, end);

        int left = start;
        int right = middle;
        int output = start;
        while (left < middle && right < end) {
            if (values[left].compareTo(values[right]) <= 0) {
                temporary[output++] = values[left++];
            } else {
                temporary[output++] = values[right++];
            }
        }
        while (left < middle) {
            temporary[output++] = values[left++];
        }
        while (right < end) {
            temporary[output++] = values[right++];
        }
        for (int i = start; i < end; i++) {
            values[i] = temporary[i];
        }
    }

    private static void sortMatches(String[] values) {
        for (int i = 1; i < values.length; i++) {
            String value = values[i];
            int j = i - 1;
            while (j >= 0 && compareMatches(values[j], value) > 0) {
                values[j + 1] = values[j];
                j--;
            }
            values[j + 1] = value;
        }
    }

    private static int compareMatches(String left, String right) {
        if (left.length() != right.length()) {
            return left.length() > right.length() ? -1 : 1;
        }
        return left.compareTo(right);
    }

    private static int binarySearch(String[] sortedValues, String target) {
        int low = 0;
        int high = sortedValues.length - 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int comparison = sortedValues[middle].compareTo(target);
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

    private static int[][] copyMatrix(int[][] values) {
        int[][] copy = new int[values.length][];
        for (int i = 0; i < values.length; i++) {
            copy[i] = values[i].clone();
        }
        return copy;
    }
}
