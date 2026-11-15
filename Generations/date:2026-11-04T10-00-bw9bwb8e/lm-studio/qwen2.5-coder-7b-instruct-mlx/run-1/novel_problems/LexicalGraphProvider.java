@LexicalGraphProvider.ThreadSafe
public class LexicalGraphProvider {
    @interface ThreadSafe {}

    private volatile String[] vocabulary;
    private volatile int[] contextTokens;
    private volatile int[] contextIndices;
    private volatile long vocabularyVersion;
    private volatile long contextVersion;

    public synchronized void replaceVocabulary(String[] words) {
        if (words == null || containsInvalidWord(words)) {
            throw new IllegalArgumentException("Invalid vocabulary");
        }
        String[] normalizedWords = normalizeAndDeduplicate(words);
        this.vocabularyVersion++;
        this.vocabulary = normalizedWords;
    }

    public synchronized void setContext(String sentence) {
        if (sentence == null) {
            throw new IllegalArgumentException("Invalid context");
        }
        int[] tokens = tokenize(sentence);
        this.contextVersion++;
        this.contextTokens = tokens;
        this.contextIndices = buildIndex(tokens);
    }

    public Session openSession() {
        String[] snapshotVocabulary;
        int[] snapshotTokens;
        int[] snapshotIndices;
        long snapshotVocabularyVersion;
        long snapshotContextVersion;

        synchronized (this) {
            snapshotVocabulary = vocabulary.clone();
            snapshotTokens = contextTokens.clone();
            snapshotIndices = contextIndices.clone();
            snapshotVocabularyVersion = vocabularyVersion;
            snapshotContextVersion = contextVersion;
        }

        return new Session(snapshotVocabulary, snapshotTokens, snapshotIndices, snapshotVocabularyVersion, snapshotContextVersion);
    }

    public static class Session {
        private final String[] vocabulary;
        private final int[] tokens;
        private final int[] indices;
        private final long vocabularyVersion;
        private final long contextVersion;

        public Session(String[] vocabulary, int[] tokens, int[] indices, long vocabularyVersion, long contextVersion) {
            this.vocabulary = vocabulary;
            this.tokens = tokens;
            this.indices = indices;
            this.vocabularyVersion = vocabularyVersion;
            this.contextVersion = contextVersion;
        }

        public List<String> findMatches(String prefix) {
            if (prefix == null || !prefix.matches("[a-zA-Z]+")) {
                throw new IllegalArgumentException("Invalid prefix");
            }
            List<String> matches = new ArrayList<>();
            for (String word : vocabulary) {
                if (word.toLowerCase().startsWith(prefix)) {
                    matches.add(word);
                }
            }
            return matches.stream()
                          .distinct()
                          .sorted((w1, w2) -> {
                              int lengthDiff = Integer.compare(w2.length(), w1.length());
                              if (lengthDiff == 0) {
                                  return w1.compareTo(w2);
                              }
                              return lengthDiff;
                          })
                          .collect(Collectors.toList());
        }

        public List<Integer> dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= tokens.length) {
                throw new IllegalArgumentException("Invalid token index");
            }
            List<Integer> dependencies = new ArrayList<>();
            for (int i = tokenIndex + 1; i < tokens.length; i++) {
                if (tokens[i] == tokens[tokenIndex]) {
                    dependencies.add(indices[i]);
                }
            }
            return dependencies;
        }

        public Object[] snapshot() {
            return new Object[]{vocabulary.clone(), tokens.clone(), indices.clone(), vocabularyVersion, contextVersion};
        }
    }

    private static boolean containsInvalidWord(String[] words) {
        for (String word : words) {
            if (!isValidWord(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidWord(String word) {
        return !word.isEmpty() && word.matches("[a-zA-Z]+");
    }

    private static String[] normalizeAndDeduplicate(String[] words) {
        Set<String> set = new HashSet<>();
        for (String word : words) {
            if (!isValidWord(word)) {
                continue;
            }
            set.add(word.toLowerCase());
        }
        return set.toArray(new String[0]);
    }

    private static int[] tokenize(String sentence) {
        List<Integer> tokens = new ArrayList<>();
        StringBuilder currentToken = new StringBuilder();
        for (char c : sentence.toCharArray()) {
            if (Character.isLetter(c)) {
                currentToken.append(Character.toLowerCase(c));
            } else {
                if (currentToken.length() > 0) {
                    tokens.add(currentToken.toString().hashCode());
                    currentToken.setLength(0);
                }
            }
        }
        if (currentToken.length() > 0) {
            tokens.add(currentToken.toString().hashCode());
        }
        return tokens.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int[] buildIndex(int[] tokens) {
        Map<Integer, List<Integer>> indexMap = new HashMap<>();
        for (int i = 0; i < tokens.length; i++) {
            if (!indexMap.containsKey(tokens[i])) {
                indexMap.put(tokens[i], new ArrayList<>());
            }
            indexMap.get(tokens[i]).add(i);
        }
        int[] indices = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            indices[i] = indexMap.get(tokens[i]).get(i);
        }
        return indices;
    }
}
