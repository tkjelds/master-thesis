@LexicalGraphProvider.ThreadSafe
public final class LexicalGraphProvider {
    @interface ThreadSafe {}

    private final Object stateMonitor = new Object();
    private State state = new State(new String[0], new String[0],
            new Occurrence[0], 0L, 0L);

    public void replaceVocabulary(String[] words) {
        String[] vocabulary = prepareVocabulary(words);

        synchronized (stateMonitor) {
            State current = state;
            if (current.vocabularyRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("Vocabulary revision overflow");
            }
            state = new State(vocabulary, current.tokens, current.occurrences,
                    current.vocabularyRevision + 1L, current.contextGeneration);
        }
    }

    public void setContext(String sentence) {
        String[] tokens = tokenize(sentence);
        Occurrence[] occurrences = buildOccurrences(tokens);

        synchronized (stateMonitor) {
            State current = state;
            if (current.contextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Context generation overflow");
            }
            state = new State(current.vocabulary, tokens, occurrences,
                    current.vocabularyRevision, current.contextGeneration + 1L);
        }
    }

    public Session openSession() {
        State captured;
        synchronized (stateMonitor) {
            captured = state;
        }
        return new Session(captured);
    }

    public Snapshot snapshot() {
        State captured;
        synchronized (stateMonitor) {
            captured = state;
        }
        return new Snapshot(captured);
    }

    public static final class Session {
        private final String[] vocabulary;
        private final String[] tokens;
        private final Occurrence[] occurrences;
        private final long vocabularyRevision;
        private final long contextGeneration;

        private Session(State state) {
            vocabulary = state.vocabulary;
            tokens = state.tokens;
            occurrences = state.occurrences;
            vocabularyRevision = state.vocabularyRevision;
            contextGeneration = state.contextGeneration;
        }

        public String[] findMatches(String prefix) {
            String normalizedPrefix = normalizePrefix(prefix);
            String[] matches = new String[vocabulary.length];
            int count = 0;

            for (String word : vocabulary) {
                if (startsWith(word, normalizedPrefix)
                        && findOccurrence(word) >= 0) {
                    matches[count++] = word;
                }
            }

            String[] result = new String[count];
            for (int i = 0; i < count; i++) {
                result[i] = matches[i];
            }
            sortMatches(result);
            return result;
        }

        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= tokens.length) {
                throw new IllegalArgumentException("Invalid token index");
            }

            int[] result = new int[tokens.length - tokenIndex - 1];
            int count = 0;
            for (int i = tokenIndex + 1; i < tokens.length; i++) {
                if (findVocabularyWord(tokens[i]) >= 0) {
                    result[count++] = i;
                }
            }

            int[] trimmed = new int[count];
            for (int i = 0; i < count; i++) {
                trimmed[i] = result[i];
            }
            return trimmed;
        }

        public Snapshot snapshot() {
            return new Snapshot(vocabulary, tokens, occurrences,
                    vocabularyRevision, contextGeneration);
        }

        private int findOccurrence(String word) {
            int low = 0;
            int high = occurrences.length - 1;
            while (low <= high) {
                int middle = low + (high - low) / 2;
                int comparison = occurrences[middle].word.compareTo(word);
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

        private int findVocabularyWord(String word) {
            int low = 0;
            int high = vocabulary.length - 1;
            while (low <= high) {
                int middle = low + (high - low) / 2;
                int comparison = vocabulary[middle].compareTo(word);
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
    }

    public static final class Snapshot {
        private final String[] vocabulary;
        private final String[] tokens;
        private final String[] occurrenceWords;
        private final int[][] occurrenceIndexes;
        private final long vocabularyRevision;
        private final long contextGeneration;

        private Snapshot(State state) {
            this(state.vocabulary, state.tokens, state.occurrences,
                    state.vocabularyRevision, state.contextGeneration);
        }

        private Snapshot(String[] vocabulary, String[] tokens,
                         Occurrence[] occurrences, long vocabularyRevision,
                         long contextGeneration) {
            this.vocabulary = copyStrings(vocabulary);
            this.tokens = copyStrings(tokens);
            this.occurrenceWords = new String[occurrences.length];
            this.occurrenceIndexes = new int[occurrences.length][];
            for (int i = 0; i < occurrences.length; i++) {
                this.occurrenceWords[i] = occurrences[i].word;
                this.occurrenceIndexes[i] = copyInts(occurrences[i].indexes);
            }
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
            int[][] copy = new int[occurrenceIndexes.length][];
            for (int i = 0; i < occurrenceIndexes.length; i++) {
                copy[i] = copyInts(occurrenceIndexes[i]);
            }
            return copy;
        }

        public long getVocabularyRevision() {
            return vocabularyRevision;
        }

        public long getContextGeneration() {
            return contextGeneration;
        }

        public long[] getVersions() {
            return new long[] { vocabularyRevision, contextGeneration };
        }
    }

    private static final class State {
        private final String[] vocabulary;
        private final String[] tokens;
        private final Occurrence[] occurrences;
        private final long vocabularyRevision;
        private final long contextGeneration;

        private State(String[] vocabulary, String[] tokens,
                      Occurrence[] occurrences, long vocabularyRevision,
                      long contextGeneration) {
            this.vocabulary = vocabulary;
            this.tokens = tokens;
            this.occurrences = occurrences;
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }
    }

    private static final class Occurrence {
        private final String word;
        private final int[] indexes;

        private Occurrence(String word, int[] indexes) {
            this.word = word;
            this.indexes = indexes;
        }
    }

    private static final class TokenRef {
        private final String word;
        private final int index;

        private TokenRef(String word, int index) {
            this.word = word;
            this.index = index;
        }
    }

    private static String[] prepareVocabulary(String[] words) {
        if (words == null) {
            throw new IllegalArgumentException("Vocabulary must not be null");
        }

        String[] normalized = new String[words.length];
        for (int i = 0; i < words.length; i++) {
            normalized[i] = normalizeWord(words[i]);
        }

        sortStrings(normalized);
        int uniqueCount = 0;
        for (int i = 0; i < normalized.length; i++) {
            if (i == 0 || !normalized[i].equals(normalized[i - 1])) {
                uniqueCount++;
            }
        }

        String[] vocabulary = new String[uniqueCount];
        int output = 0;
        for (int i = 0; i < normalized.length; i++) {
            if (i == 0 || !normalized[i].equals(normalized[i - 1])) {
                vocabulary[output++] = normalized[i];
            }
        }
        return vocabulary;
    }

    private static String normalizeWord(String word) {
        if (word == null || word.length() == 0) {
            throw new IllegalArgumentException("Words must be nonempty");
        }

        char[] chars = new char[word.length()];
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (!isAsciiLetter(c)) {
                throw new IllegalArgumentException("Words must contain ASCII letters only");
            }
            chars[i] = toLowerAscii(c);
        }
        return new String(chars);
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null) {
            throw new IllegalArgumentException("Prefix must not be null");
        }

        char[] chars = new char[prefix.length()];
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (!isAsciiLetter(c)) {
                throw new IllegalArgumentException("Prefix must contain ASCII letters only");
            }
            chars[i] = toLowerAscii(c);
        }
        return new String(chars);
    }

    private static String[] tokenize(String sentence) {
        if (sentence == null) {
            throw new IllegalArgumentException("Sentence must not be null");
        }

        int tokenCount = 0;
        boolean inToken = false;
        for (int i = 0; i < sentence.length(); i++) {
            if (isAsciiLetter(sentence.charAt(i))) {
                if (!inToken) {
                    tokenCount++;
                    inToken = true;
                }
            } else {
                inToken = false;
            }
        }

        String[] tokens = new String[tokenCount];
        int tokenIndex = 0;
        int position = 0;
        while (position < sentence.length()) {
            while (position < sentence.length()
                    && !isAsciiLetter(sentence.charAt(position))) {
                position++;
            }
            if (position == sentence.length()) {
                break;
            }

            int start = position;
            while (position < sentence.length()
                    && isAsciiLetter(sentence.charAt(position))) {
                position++;
            }

            char[] chars = new char[position - start];
            for (int i = start; i < position; i++) {
                chars[i - start] = toLowerAscii(sentence.charAt(i));
            }
            tokens[tokenIndex++] = new String(chars);
        }
        return tokens;
    }

    private static Occurrence[] buildOccurrences(String[] tokens) {
        TokenRef[] refs = new TokenRef[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            refs[i] = new TokenRef(tokens[i], i);
        }
        sortTokenRefs(refs);

        int occurrenceCount = 0;
        for (int i = 0; i < refs.length; i++) {
            if (i == 0 || !refs[i].word.equals(refs[i - 1].word)) {
                occurrenceCount++;
            }
        }

        Occurrence[] occurrences = new Occurrence[occurrenceCount];
        int refPosition = 0;
        int occurrencePosition = 0;
        while (refPosition < refs.length) {
            int end = refPosition + 1;
            while (end < refs.length
                    && refs[refPosition].word.equals(refs[end].word)) {
                end++;
            }

            int[] indexes = new int[end - refPosition];
            for (int i = refPosition; i < end; i++) {
                indexes[i - refPosition] = refs[i].index;
            }
            occurrences[occurrencePosition++] =
                    new Occurrence(refs[refPosition].word, indexes);
            refPosition = end;
        }
        return occurrences;
    }

    private static boolean startsWith(String word, String prefix) {
        if (prefix.length() > word.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (word.charAt(i) != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static char toLowerAscii(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
    }

    private static String[] copyStrings(String[] source) {
        String[] copy = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static int[] copyInts(int[] source) {
        int[] copy = new int[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static void sortStrings(String[] values) {
        if (values.length < 2) {
            return;
        }
        String[] temporary = new String[values.length];
        mergeSortStrings(values, temporary, 0, values.length);
    }

    private static void mergeSortStrings(String[] values, String[] temporary,
                                         int start, int end) {
        if (end - start < 2) {
            return;
        }
        int middle = start + (end - start) / 2;
        mergeSortStrings(values, temporary, start, middle);
        mergeSortStrings(values, temporary, middle, end);

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

    private static void sortTokenRefs(TokenRef[] values) {
        if (values.length < 2) {
            return;
        }
        TokenRef[] temporary = new TokenRef[values.length];
        mergeSortTokenRefs(values, temporary, 0, values.length);
    }

    private static void mergeSortTokenRefs(TokenRef[] values,
                                           TokenRef[] temporary,
                                           int start, int end) {
        if (end - start < 2) {
            return;
        }
        int middle = start + (end - start) / 2;
        mergeSortTokenRefs(values, temporary, start, middle);
        mergeSortTokenRefs(values, temporary, middle, end);

        int left = start;
        int right = middle;
        int output = start;
        while (left < middle && right < end) {
            if (compareTokenRefs(values[left], values[right]) <= 0) {
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

    private static int compareTokenRefs(TokenRef left, TokenRef right) {
        int comparison = left.word.compareTo(right.word);
        if (comparison != 0) {
            return comparison;
        }
        if (left.index < right.index) {
            return -1;
        }
        if (left.index > right.index) {
            return 1;
        }
        return 0;
    }

    private static void sortMatches(String[] values) {
        if (values.length < 2) {
            return;
        }
        String[] temporary = new String[values.length];
        mergeSortMatches(values, temporary, 0, values.length);
    }

    private static void mergeSortMatches(String[] values, String[] temporary,
                                         int start, int end) {
        if (end - start < 2) {
            return;
        }
        int middle = start + (end - start) / 2;
        mergeSortMatches(values, temporary, start, middle);
        mergeSortMatches(values, temporary, middle, end);

        int left = start;
        int right = middle;
        int output = start;
        while (left < middle && right < end) {
            if (compareMatches(values[left], values[right]) <= 0) {
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

    private static int compareMatches(String left, String right) {
        if (left.length() > right.length()) {
            return -1;
        }
        if (left.length() < right.length()) {
            return 1;
        }
        return left.compareTo(right);
    }
}
