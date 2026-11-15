/**
 * Operations never wait for data or capacity; only publication and capture
 * briefly acquire the state monitor. Invalid inputs throw
 * IllegalArgumentException without changing provider state.
 */
@LexicalGraphProvider.ThreadSafe
public final class LexicalGraphProvider {
    @interface ThreadSafe {}

    private static final long MAX_VERSION = 9223372036854775807L;
    private final Object stateMonitor = new Object();
    private Vocabulary vocabulary = new Vocabulary(new String[0], 0L);
    private Context context = new Context(new String[0], new Occurrence[0], 0L);

    public LexicalGraphProvider() {
    }

    public void replaceVocabulary(String[] words) {
        if (words == null) {
            throw new IllegalArgumentException("Vocabulary must not be null");
        }
        String[] prepared = new String[words.length];
        for (int i = 0; i < words.length; i++) {
            prepared[i] = normalize(words[i], false);
        }
        sort(prepared, false);
        int distinct = 0;
        for (int i = 0; i < prepared.length; i++) {
            if (distinct == 0 || !prepared[i].equals(prepared[distinct - 1])) {
                prepared[distinct++] = prepared[i];
            }
        }
        String[] unique = new String[distinct];
        for (int i = 0; i < distinct; i++) {
            unique[i] = prepared[i];
        }
        synchronized (stateMonitor) {
            if (vocabulary.revision == MAX_VERSION) {
                throw new IllegalStateException("Vocabulary revision overflow");
            }
            vocabulary = new Vocabulary(unique, vocabulary.revision + 1L);
        }
    }

    public void setContext(String sentence) {
        if (sentence == null) {
            throw new IllegalArgumentException("Sentence must not be null");
        }
        String[] tokens = tokenize(sentence);
        Occurrence[] occurrences = index(tokens);
        synchronized (stateMonitor) {
            if (context.generation == MAX_VERSION) {
                throw new IllegalStateException("Context generation overflow");
            }
            context = new Context(tokens, occurrences, context.generation + 1L);
        }
    }

    public Session openSession() {
        Vocabulary pinnedVocabulary;
        Context pinnedContext;
        synchronized (stateMonitor) {
            pinnedVocabulary = vocabulary;
            pinnedContext = context;
        }
        return new Session(pinnedVocabulary, pinnedContext);
    }

    public Snapshot snapshot() {
        return openSession().snapshot();
    }

    public static final class Session {
        private final Vocabulary vocabulary;
        private final Context context;

        private Session(Vocabulary vocabulary, Context context) {
            this.vocabulary = vocabulary;
            this.context = context;
        }

        public String[] findMatches(String prefix) {
            String normalized = normalize(prefix, true);
            String[] candidates = new String[vocabulary.words.length];
            int count = 0;
            for (int i = 0; i < vocabulary.words.length; i++) {
                String word = vocabulary.words[i];
                if (word.startsWith(normalized)
                        && findOccurrence(context.occurrences, word) >= 0) {
                    candidates[count++] = word;
                }
            }
            String[] matches = new String[count];
            for (int i = 0; i < count; i++) {
                matches[i] = candidates[i];
            }
            sort(matches, true);
            return matches;
        }

        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= context.tokens.length) {
                throw new IllegalArgumentException("Invalid token index");
            }
            int count = 0;
            for (int i = tokenIndex + 1; i < context.tokens.length; i++) {
                if (findWord(vocabulary.words, context.tokens[i]) >= 0) {
                    count++;
                }
            }
            int[] result = new int[count];
            int next = 0;
            for (int i = tokenIndex + 1; i < context.tokens.length; i++) {
                if (findWord(vocabulary.words, context.tokens[i]) >= 0) {
                    result[next++] = i;
                }
            }
            return result;
        }

        public Snapshot snapshot() {
            String[] words = copy(vocabulary.words);
            String[] tokens = copy(context.tokens);
            Occurrence[] occurrences = new Occurrence[context.occurrences.length];
            for (int i = 0; i < occurrences.length; i++) {
                Occurrence original = context.occurrences[i];
                int[] indexes = new int[original.tokenIndexes.length];
                for (int j = 0; j < indexes.length; j++) {
                    indexes[j] = original.tokenIndexes[j];
                }
                occurrences[i] = new Occurrence(original.word, indexes);
            }
            return new Snapshot(words, tokens, occurrences,
                    vocabulary.revision, context.generation);
        }
    }

    /**
     * Detached, caller-owned arrays. Vocabulary and occurrence entries are in
     * lexical order; tokens are in sentence order. Changing these arrays never
     * changes a provider, session, or another snapshot.
     */
    public static final class Snapshot {
        public final String[] vocabulary;
        public final String[] tokens;
        public final Occurrence[] occurrences;
        public final long vocabularyRevision;
        public final long contextGeneration;

        private Snapshot(String[] vocabulary, String[] tokens,
                         Occurrence[] occurrences, long vocabularyRevision,
                         long contextGeneration) {
            this.vocabulary = vocabulary;
            this.tokens = tokens;
            this.occurrences = occurrences;
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }
    }

    /** All zero-based positions for one word, in increasing order. */
    public static final class Occurrence {
        public final String word;
        public final int[] tokenIndexes;

        private Occurrence(String word, int[] tokenIndexes) {
            this.word = word;
            this.tokenIndexes = tokenIndexes;
        }
    }

    private static final class Vocabulary {
        final String[] words;
        final long revision;

        Vocabulary(String[] words, long revision) {
            this.words = words;
            this.revision = revision;
        }
    }

    private static final class Context {
        final String[] tokens;
        final Occurrence[] occurrences;
        final long generation;

        Context(String[] tokens, Occurrence[] occurrences, long generation) {
            this.tokens = tokens;
            this.occurrences = occurrences;
            this.generation = generation;
        }
    }

    private static boolean isLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static char lowercase(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
    }

    private static String normalize(String value, boolean allowEmpty) {
        if (value == null || (!allowEmpty && value.length() == 0)) {
            throw new IllegalArgumentException("Invalid word or prefix");
        }
        char[] result = new char[value.length()];
        for (int i = 0; i < result.length; i++) {
            char c = value.charAt(i);
            if (!isLetter(c)) {
                throw new IllegalArgumentException("Only ASCII letters are allowed");
            }
            result[i] = lowercase(c);
        }
        return new String(result);
    }

    private static String[] tokenize(String sentence) {
        int count = 0;
        boolean inToken = false;
        for (int i = 0; i < sentence.length(); i++) {
            boolean letter = isLetter(sentence.charAt(i));
            if (letter && !inToken) {
                count++;
            }
            inToken = letter;
        }
        String[] tokens = new String[count];
        int position = 0;
        int next = 0;
        while (position < sentence.length()) {
            if (!isLetter(sentence.charAt(position))) {
                position++;
                continue;
            }
            int start = position;
            while (position < sentence.length()
                    && isLetter(sentence.charAt(position))) {
                position++;
            }
            char[] letters = new char[position - start];
            for (int i = 0; i < letters.length; i++) {
                letters[i] = lowercase(sentence.charAt(start + i));
            }
            tokens[next++] = new String(letters);
        }
        return tokens;
    }

    private static Occurrence[] index(String[] tokens) {
        String[] sorted = copy(tokens);
        sort(sorted, false);
        int distinct = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (i == 0 || !sorted[i].equals(sorted[i - 1])) {
                distinct++;
            }
        }
        Occurrence[] occurrences = new Occurrence[distinct];
        int next = 0;
        int start = 0;
        while (start < sorted.length) {
            int end = start + 1;
            while (end < sorted.length && sorted[start].equals(sorted[end])) {
                end++;
            }
            occurrences[next++] = new Occurrence(sorted[start], new int[end - start]);
            start = end;
        }
        int[] used = new int[distinct];
        for (int i = 0; i < tokens.length; i++) {
            int entry = findOccurrence(occurrences, tokens[i]);
            occurrences[entry].tokenIndexes[used[entry]++] = i;
        }
        return occurrences;
    }

    private static String[] copy(String[] source) {
        String[] result = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static int findWord(String[] words, String target) {
        int low = 0;
        int high = words.length - 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int comparison = words[middle].compareTo(target);
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

    private static int findOccurrence(Occurrence[] entries, String target) {
        int low = 0;
        int high = entries.length - 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int comparison = entries[middle].word.compareTo(target);
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

    private static int compare(String a, String b, boolean matchOrder) {
        if (matchOrder && a.length() != b.length()) {
            return a.length() > b.length() ? -1 : 1;
        }
        return a.compareTo(b);
    }

    private static void sort(String[] words, boolean matchOrder) {
        for (int root = words.length / 2 - 1; root >= 0; root--) {
            siftDown(words, root, words.length, matchOrder);
        }
        for (int end = words.length - 1; end > 0; end--) {
            String temporary = words[0];
            words[0] = words[end];
            words[end] = temporary;
            siftDown(words, 0, end, matchOrder);
        }
    }

    private static void siftDown(String[] words, int root, int size,
                                 boolean matchOrder) {
        while (root < size / 2) {
            int child = root * 2 + 1;
            if (child + 1 < size
                    && compare(words[child], words[child + 1], matchOrder) < 0) {
                child++;
            }
            if (compare(words[root], words[child], matchOrder) >= 0) {
                return;
            }
            String temporary = words[root];
            words[root] = words[child];
            words[child] = temporary;
            root = child;
        }
    }
}
