@LexicalGraphProvider.ThreadSafe
public final class LexicalGraphProvider {
    @interface ThreadSafe {}

    private final Object stateMonitor = new Object();
    private String[] vocabulary = new String[0];
    private long vocabularyRevision;
    private String[] tokens = new String[0];
    private String[] occurrenceWords = new String[0];
    private int[][] occurrenceIndexes = new int[0][];
    private long contextGeneration;

    public LexicalGraphProvider() {
    }

    public void replaceVocabulary(String[] words) {
        if (words == null) {
            throw new IllegalArgumentException("words must not be null");
        }

        String[] input = words.clone();
        String[] prepared = new String[input.length];
        for (int i = 0; i < input.length; i++) {
            String word = input[i];
            if (word == null || word.length() == 0) {
                throw new IllegalArgumentException("vocabulary words must be nonempty");
            }
            for (int j = 0; j < word.length(); j++) {
                if (!isAsciiLetter(word.charAt(j))) {
                    throw new IllegalArgumentException(
                            "vocabulary words must contain only ASCII letters");
                }
            }
            prepared[i] = lowercaseAscii(word);
        }

        sortStrings(prepared);
        int uniqueCount = 0;
        for (int i = 0; i < prepared.length; i++) {
            if (i == 0 || !prepared[i].equals(prepared[i - 1])) {
                prepared[uniqueCount++] = prepared[i];
            }
        }
        String[] completeVocabulary = new String[uniqueCount];
        for (int i = 0; i < uniqueCount; i++) {
            completeVocabulary[i] = prepared[i];
        }

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
        String[] sortedWords = preparedTokens.clone();
        sortStrings(sortedWords);

        int uniqueCount = 0;
        for (int i = 0; i < sortedWords.length; i++) {
            if (i == 0 || !sortedWords[i].equals(sortedWords[i - 1])) {
                uniqueCount++;
            }
        }
        String[] preparedOccurrenceWords = new String[uniqueCount];
        int uniqueIndex = 0;
        for (int i = 0; i < sortedWords.length; i++) {
            if (i == 0 || !sortedWords[i].equals(sortedWords[i - 1])) {
                preparedOccurrenceWords[uniqueIndex++] = sortedWords[i];
            }
        }

        int[] counts = new int[uniqueCount];
        for (int i = 0; i < preparedTokens.length; i++) {
            counts[binarySearch(preparedOccurrenceWords, preparedTokens[i])]++;
        }

        int[][] preparedOccurrenceIndexes = new int[uniqueCount][];
        int[] positions = new int[uniqueCount];
        for (int i = 0; i < uniqueCount; i++) {
            preparedOccurrenceIndexes[i] = new int[counts[i]];
        }
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
        return new Session(capturedVocabulary, capturedTokens, capturedOccurrenceWords,
                capturedOccurrenceIndexes, capturedVocabularyRevision, capturedContextGeneration);
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
        return new Snapshot(capturedVocabulary, capturedTokens, capturedOccurrenceWords,
                capturedOccurrenceIndexes, capturedVocabularyRevision, capturedContextGeneration);
    }

    public static final class Session {
        private final String[] vocabulary;
        private final String[] tokens;
        private final String[] occurrenceWords;
        private final int[][] occurrenceIndexes;
        private final long vocabularyRevision;
        private final long contextGeneration;

        private Session(String[] vocabulary, String[] tokens, String[] occurrenceWords,
                int[][] occurrenceIndexes, long vocabularyRevision, long contextGeneration) {
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
            for (int i = 0; i < prefix.length(); i++) {
                if (!isAsciiLetter(prefix.charAt(i))) {
                    throw new IllegalArgumentException(
                            "prefix must contain only ASCII letters");
                }
            }
            String normalizedPrefix = lowercaseAscii(prefix);

            String[] matches = new String[vocabulary.length];
            int count = 0;
            for (int i = 0; i < vocabulary.length; i++) {
                String word = vocabulary[i];
                if (word.startsWith(normalizedPrefix)
                        && binarySearch(occurrenceWords, word) >= 0) {
                    matches[count++] = word;
                }
            }

            for (int i = 1; i < count; i++) {
                String word = matches[i];
                int j = i;
                while (j > 0 && compareMatchOrder(word, matches[j - 1]) < 0) {
                    matches[j] = matches[j - 1];
                    j--;
                }
                matches[j] = word;
            }

            String[] result = new String[count];
            for (int i = 0; i < count; i++) {
                result[i] = matches[i];
            }
            return result;
        }

        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= tokens.length) {
                throw new IllegalArgumentException("token index is out of range");
            }

            int count = 0;
            for (int i = tokenIndex + 1; i < tokens.length; i++) {
                if (binarySearch(vocabulary, tokens[i]) >= 0) {
                    count++;
                }
            }
            int[] result = new int[count];
            int resultIndex = 0;
            for (int i = tokenIndex + 1; i < tokens.length; i++) {
                if (binarySearch(vocabulary, tokens[i]) >= 0) {
                    result[resultIndex++] = i;
                }
            }
            return result;
        }

        public Snapshot snapshot() {
            return new Snapshot(vocabulary, tokens, occurrenceWords, occurrenceIndexes,
                    vocabularyRevision, contextGeneration);
        }
    }

    public static final class Snapshot {
        public final String[] vocabulary;
        public final String[] tokens;
        public final String[] occurrenceWords;
        public final int[][] occurrenceIndexes;
        public final long vocabularyRevision;
        public final long contextGeneration;

        private Snapshot(String[] vocabulary, String[] tokens, String[] occurrenceWords,
                int[][] occurrenceIndexes, long vocabularyRevision, long contextGeneration) {
            this.vocabulary = vocabulary.clone();
            this.tokens = tokens.clone();
            this.occurrenceWords = occurrenceWords.clone();
            this.occurrenceIndexes = copyIndexes(occurrenceIndexes);
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
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
            return copyIndexes(occurrenceIndexes);
        }

        public long getVocabularyRevision() {
            return vocabularyRevision;
        }

        public long getContextGeneration() {
            return contextGeneration;
        }
    }

    private static String[] tokenize(String sentence) {
        int count = 0;
        boolean inWord = false;
        for (int i = 0; i < sentence.length(); i++) {
            boolean letter = isAsciiLetter(sentence.charAt(i));
            if (letter && !inWord) {
                count++;
            }
            inWord = letter;
        }

        String[] result = new String[count];
        int tokenIndex = 0;
        int start = -1;
        for (int i = 0; i <= sentence.length(); i++) {
            boolean letter = i < sentence.length() && isAsciiLetter(sentence.charAt(i));
            if (letter && start < 0) {
                start = i;
            } else if (!letter && start >= 0) {
                result[tokenIndex++] = lowercaseAscii(sentence.substring(start, i));
                start = -1;
            }
        }
        return result;
    }

    private static String lowercaseAscii(String value) {
        char[] chars = value.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] >= 'A' && chars[i] <= 'Z') {
                chars[i] = (char) (chars[i] + ('a' - 'A'));
            }
        }
        return new String(chars);
    }

    private static boolean isAsciiLetter(char value) {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
    }

    private static int binarySearch(String[] sortedValues, String value) {
        int low = 0;
        int high = sortedValues.length - 1;
        while (low <= high) {
            int middle = low + ((high - low) >>> 1);
            int comparison = sortedValues[middle].compareTo(value);
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

    private static int compareMatchOrder(String first, String second) {
        if (first.length() != second.length()) {
            return first.length() > second.length() ? -1 : 1;
        }
        return first.compareTo(second);
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
        int middle = start + ((end - start) >>> 1);
        mergeSort(values, temporary, start, middle);
        mergeSort(values, temporary, middle, end);

        int left = start;
        int right = middle;
        int destination = start;
        while (left < middle && right < end) {
            if (values[left].compareTo(values[right]) <= 0) {
                temporary[destination++] = values[left++];
            } else {
                temporary[destination++] = values[right++];
            }
        }
        while (left < middle) {
            temporary[destination++] = values[left++];
        }
        while (right < end) {
            temporary[destination++] = values[right++];
        }
        for (int i = start; i < end; i++) {
            values[i] = temporary[i];
        }
    }

    private static int[][] copyIndexes(int[][] indexes) {
        int[][] copy = new int[indexes.length][];
        for (int i = 0; i < indexes.length; i++) {
            copy[i] = indexes[i].clone();
        }
        return copy;
    }
}
