@LexicalGraphProvider.ThreadSafe
public final class LexicalGraphProvider {
    @interface ThreadSafe {}

    private final Object stateMonitor = new Object();

    private VocabularyState vocabulary = VocabularyState.empty();
    private ContextState context = ContextState.empty();
    private long vocabularyRevision = 0L;
    private long contextGeneration = 0L;

    public void replaceVocabulary(String[] words) {
        VocabularyState prepared = VocabularyState.prepare(words);

        synchronized (stateMonitor) {
            if (vocabularyRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("Vocabulary revision overflow");
            }
            vocabulary = prepared;
            vocabularyRevision++;
        }
    }

    public void setContext(String sentence) {
        ContextState prepared = ContextState.prepare(sentence);

        synchronized (stateMonitor) {
            if (contextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Context generation overflow");
            }
            context = prepared;
            contextGeneration++;
        }
    }

    public Session openSession() {
        synchronized (stateMonitor) {
            return new Session(
                    vocabulary.words,
                    vocabulary.hashSlots,
                    context.tokens,
                    context.occurrenceWords,
                    context.occurrenceIndexes,
                    context.occurrenceHashSlots,
                    vocabularyRevision,
                    contextGeneration);
        }
    }

    public Snapshot snapshot() {
        VocabularyState capturedVocabulary;
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
                capturedVocabulary.words,
                capturedContext.tokens,
                capturedContext.occurrenceWords,
                capturedContext.occurrenceIndexes,
                capturedVocabularyRevision,
                capturedContextGeneration);
    }

    public static final class Session {
        private final String[] vocabulary;
        private final int[] vocabularyHashSlots;
        private final String[] tokens;
        private final String[] occurrenceWords;
        private final int[][] occurrenceIndexes;
        private final int[] occurrenceHashSlots;
        private final long vocabularyRevision;
        private final long contextGeneration;

        private Session(
                String[] vocabulary,
                int[] vocabularyHashSlots,
                String[] tokens,
                String[] occurrenceWords,
                int[][] occurrenceIndexes,
                int[] occurrenceHashSlots,
                long vocabularyRevision,
                long contextGeneration) {
            this.vocabulary = vocabulary;
            this.vocabularyHashSlots = vocabularyHashSlots;
            this.tokens = tokens;
            this.occurrenceWords = occurrenceWords;
            this.occurrenceIndexes = occurrenceIndexes;
            this.occurrenceHashSlots = occurrenceHashSlots;
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }

        public String[] findMatches(String prefix) {
            String normalizedPrefix = normalizePrefix(prefix);
            String[] matches = new String[vocabulary.length];
            int matchCount = 0;

            for (int i = 0; i < vocabulary.length; i++) {
                String word = vocabulary[i];
                if (startsWith(word, normalizedPrefix)
                        && occursInContext(word)) {
                    matches[matchCount++] = word;
                }
            }

            for (int i = 1; i < matchCount; i++) {
                String word = matches[i];
                int j = i - 1;
                while (j >= 0 && compareMatches(word, matches[j]) < 0) {
                    matches[j + 1] = matches[j];
                    j--;
                }
                matches[j + 1] = word;
            }

            return copyOf(matches, matchCount);
        }

        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= tokens.length) {
                throw new IllegalArgumentException("Invalid token index");
            }

            int count = 0;
            for (int i = tokenIndex + 1; i < tokens.length; i++) {
                if (isVocabularyWord(tokens[i])) {
                    count++;
                }
            }

            int[] result = new int[count];
            int resultIndex = 0;
            for (int i = tokenIndex + 1; i < tokens.length; i++) {
                if (isVocabularyWord(tokens[i])) {
                    result[resultIndex++] = i;
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

        private boolean occursInContext(String word) {
            int slot = findWordSlot(occurrenceWords, occurrenceHashSlots, word);
            return occurrenceHashSlots[slot] != 0;
        }

        private boolean isVocabularyWord(String word) {
            int slot = findWordSlot(vocabulary, vocabularyHashSlots, word);
            return vocabularyHashSlots[slot] != 0;
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
            this.occurrenceIndexes = copyOf(occurrenceIndexes, occurrenceIndexes.length);
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }
    }

    private static final class VocabularyState {
        private final String[] words;
        private final int[] hashSlots;

        private VocabularyState(String[] words, int[] hashSlots) {
            this.words = words;
            this.hashSlots = hashSlots;
        }

        private static VocabularyState empty() {
            return new VocabularyState(new String[0], new int[1]);
        }

        private static VocabularyState prepare(String[] input) {
            if (input == null) {
                throw new IllegalArgumentException("Vocabulary must not be null");
            }

            String[] words = new String[input.length];
            int[] slots = new int[hashCapacity(input.length)];
            int wordCount = 0;

            for (int i = 0; i < input.length; i++) {
                String rawWord = input[i];
                if (rawWord == null || rawWord.length() == 0) {
                    throw new IllegalArgumentException("Vocabulary words must be nonempty");
                }

                String word = normalizeWord(rawWord);
                int slot = findWordSlot(words, slots, word);
                if (slots[slot] == 0) {
                    words[wordCount] = word;
                    slots[slot] = wordCount + 1;
                    wordCount++;
                }
            }

            return new VocabularyState(copyOf(words, wordCount), slots);
        }
    }

    private static final class ContextState {
        private final String[] tokens;
        private final String[] occurrenceWords;
        private final int[][] occurrenceIndexes;
        private final int[] occurrenceHashSlots;

        private ContextState(
                String[] tokens,
                String[] occurrenceWords,
                int[][] occurrenceIndexes,
                int[] occurrenceHashSlots) {
            this.tokens = tokens;
            this.occurrenceWords = occurrenceWords;
            this.occurrenceIndexes = occurrenceIndexes;
            this.occurrenceHashSlots = occurrenceHashSlots;
        }

        private static ContextState empty() {
            return new ContextState(
                    new String[0],
                    new String[0],
                    new int[0][],
                    new int[1]);
        }

        private static ContextState prepare(String sentence) {
            if (sentence == null) {
                throw new IllegalArgumentException("Sentence must not be null");
            }

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
            int tokenIndex = 0;
            int runStart = -1;
            for (int i = 0; i <= sentence.length(); i++) {
                boolean letter = i < sentence.length() && isAsciiLetter(sentence.charAt(i));
                if (letter && runStart < 0) {
                    runStart = i;
                } else if (!letter && runStart >= 0) {
                    tokens[tokenIndex++] = lowercaseRun(sentence, runStart, i);
                    runStart = -1;
                }
            }

            int[] slots = new int[hashCapacity(tokens.length)];
            String[] words = new String[tokens.length];
            int[] counts = new int[tokens.length];
            int uniqueCount = 0;

            for (int i = 0; i < tokens.length; i++) {
                int slot = findWordSlot(words, slots, tokens[i]);
                if (slots[slot] == 0) {
                    words[uniqueCount] = tokens[i];
                    counts[uniqueCount] = 1;
                    slots[slot] = uniqueCount + 1;
                    uniqueCount++;
                } else {
                    counts[slots[slot] - 1]++;
                }
            }

            String[] occurrenceWords = copyOf(words, uniqueCount);
            int[][] occurrenceIndexes = new int[uniqueCount][];
            int[] positions = new int[uniqueCount];
            for (int i = 0; i < uniqueCount; i++) {
                occurrenceIndexes[i] = new int[counts[i]];
            }

            for (int i = 0; i < tokens.length; i++) {
                int slot = findWordSlot(occurrenceWords, slots, tokens[i]);
                int wordIndex = slots[slot] - 1;
                occurrenceIndexes[wordIndex][positions[wordIndex]++] = i;
            }

            return new ContextState(tokens, occurrenceWords, occurrenceIndexes, slots);
        }
    }

    private static String normalizeWord(String word) {
        char[] normalized = new char[word.length()];
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (!isAsciiLetter(c)) {
                throw new IllegalArgumentException("Vocabulary words must contain only ASCII letters");
            }
            normalized[i] = toLowerAscii(c);
        }
        return new String(normalized);
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null) {
            throw new IllegalArgumentException("Prefix must not be null");
        }

        char[] normalized = new char[prefix.length()];
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (!isAsciiLetter(c)) {
                throw new IllegalArgumentException("Prefix must contain only ASCII letters");
            }
            normalized[i] = toLowerAscii(c);
        }
        return new String(normalized);
    }

    private static String lowercaseRun(String text, int start, int end) {
        char[] normalized = new char[end - start];
        for (int i = start; i < end; i++) {
            normalized[i - start] = toLowerAscii(text.charAt(i));
        }
        return new String(normalized);
    }

    private static char toLowerAscii(char c) {
        if (c >= 'A' && c <= 'Z') {
            return (char) (c + ('a' - 'A'));
        }
        return c;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
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

    private static int compareMatches(String first, String second) {
        if (first.length() != second.length()) {
            return second.length() - first.length();
        }
        return compareLexically(first, second);
    }

    private static int compareLexically(String first, String second) {
        int limit = first.length() < second.length() ? first.length() : second.length();
        for (int i = 0; i < limit; i++) {
            if (first.charAt(i) != second.charAt(i)) {
                return first.charAt(i) - second.charAt(i);
            }
        }
        return first.length() - second.length();
    }

    private static int hashCapacity(int expectedSize) {
        long target = (long) expectedSize * 2L;
        int capacity = 1;
        while ((long) capacity < target) {
            if (capacity > (1 << 29)) {
                throw new OutOfMemoryError("Required hash table is too large");
            }
            capacity <<= 1;
        }
        return capacity;
    }

    private static int findWordSlot(String[] words, int[] slots, String word) {
        int mask = slots.length - 1;
        int slot = word.hashCode() & mask;
        while (slots[slot] != 0 && !words[slots[slot] - 1].equals(word)) {
            slot = (slot + 1) & mask;
        }
        return slot;
    }

    private static String[] copyOf(String[] source, int length) {
        String[] copy = new String[length];
        for (int i = 0; i < length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static int[][] copyOf(int[][] source, int length) {
        int[][] copy = new int[length][];
        for (int i = 0; i < length; i++) {
            copy[i] = copyOf(source[i], source[i].length);
        }
        return copy;
    }

    private static int[] copyOf(int[] source, int length) {
        int[] copy = new int[length];
        for (int i = 0; i < length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }
}
