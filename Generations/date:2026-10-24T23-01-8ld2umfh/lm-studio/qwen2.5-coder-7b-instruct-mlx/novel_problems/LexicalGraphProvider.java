@LexicalGraphProvider.ThreadSafe
class LexicalGraphProvider {
    @interface ThreadSafe {}

    private String[] vocabulary;
    private int vocabularyRevision;
    private String context;
    private int contextGeneration;

    public synchronized void replaceVocabulary(String[] words) {
        if (words == null || words.length == 0) {
            throw new IllegalArgumentException("Invalid vocabulary");
        }
        String[] normalizedWords = normalize(words);
        this.vocabulary = normalizedWords;
        this.vocabularyRevision++;
    }

    public synchronized void setContext(String sentence) {
        if (sentence == null || sentence.isEmpty()) {
            throw new IllegalArgumentException("Invalid context");
        }
        String[] tokens = tokenize(sentence);
        this.context = sentence;
        this.contextGeneration++;
    }

    public Session openSession() {
        return new Session();
    }

    private String[] normalize(String[] words) {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String word : words) {
            if (!word.isEmpty()) {
                set.add(word.toLowerCase());
            }
        }
        return set.toArray(new String[0]);
    }

    private String[] tokenize(String sentence) {
        StringBuilder sb = new StringBuilder();
        for (char c : sentence.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                sb.append(c);
            } else if (sb.length() > 0) {
                String token = sb.toString().toLowerCase();
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) {
            return new String[] { sb.toString().toLowerCase() };
        }
        return new String[0];
    }

    class Session {
        private final int snapshotVocabularyRevision;
        private final String[] vocabularySnapshot;
        private final String contextSnapshot;
        private final int snapshotContextGeneration;

        public Session() {
            synchronized (LexicalGraphProvider.this) {
                this.vocabularySnapshot = LexicalGraphProvider.this.vocabulary;
                this.snapshotVocabularyRevision = LexicalGraphProvider.this.vocabularyRevision;
                this.contextSnapshot = LexicalGraphProvider.this.context;
                this.snapshotContextGeneration = LexicalGraphProvider.this.contextGeneration;
            }
        }

        public String[] findMatches(String prefix) {
            java.util.Set<String> result = new java.util.TreeSet<>((a, b) -> {
                int lenA = a.length(), lenB = b.length();
                if (lenA != lenB) return lenB - lenA;
                return a.compareTo(b);
            });
            for (String word : vocabularySnapshot) {
                if (word.regionMatches(true, 0, prefix.toLowerCase(), 0, prefix.length())) {
                    result.add(word);
                }
            }
            return result.toArray(new String[0]);
        }

        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= contextSnapshot.length()) {
                throw new IllegalArgumentException("Invalid token index");
            }
            java.util.Set<Integer> result = new java.util.TreeSet<>();
            for (int i = tokenIndex + 1; i < contextSnapshot.length(); i++) {
                if (isInVocabulary(contextSnapshot[i])) {
                    result.add(i);
                }
            }
            return result.stream().mapToInt(i -> i).toArray();
        }

        public boolean isInVocabulary(String token) {
            for (String word : vocabularySnapshot) {
                if (word.equals(token)) return true;
            }
            return false;
        }

        public String[] snapshot() {
            synchronized (LexicalGraphProvider.this) {
                return vocabularySnapshot.clone();
            }
        }

        public String contextSnapshot() {
            synchronized (LexicalGraphProvider.this) {
                return contextSnapshot;
            }
        }

        public int vocabularyRevision() {
            synchronized (LexicalGraphProvider.this) {
                return snapshotVocabularyRevision;
            }
        }

        public int contextGeneration() {
            synchronized (LexicalGraphProvider.this) {
                return snapshotContextGeneration;
            }
        }
    }
}
