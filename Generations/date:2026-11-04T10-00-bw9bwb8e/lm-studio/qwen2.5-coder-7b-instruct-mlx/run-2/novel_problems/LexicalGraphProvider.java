@LexicalGraphProvider.ThreadSafe
public class LexicalGraphProvider {
    @interface ThreadSafe {}

    private String[] vocabulary;
    private int vocIndex;
    private String context;
    private int contextIndex;
    private long revision;
    private long gen;

    public LexicalGraphProvider() {
        this.vocabulary = new String[0];
        this.vocIndex = 0;
        this.context = "";
        this.contextIndex = 0;
        this.revision = 0;
        this.gen = 0;
    }

    public void replaceVocabulary(String[] words) {
        synchronized (this) {
            if (words == null || words.length == 0) {
                throw new IllegalArgumentException("Invalid vocabulary");
            }
            String[] newVocab = words.clone();
            Set<String> dedupedVocab = new HashSet<>();
            for (String word : newVocab) {
                if (word != null && !word.isEmpty()) {
                    dedupedVocab.add(word.toLowerCase());
                }
            }
            newVocab = dedupedVocab.toArray(new String[0]);
            this.vocabulary = newVocab;
            vocIndex++;
            revision++;
        }
    }

    public void setContext(String sentence) {
        synchronized (this) {
            if (sentence == null || sentence.isEmpty()) {
                throw new IllegalArgumentException("Invalid context");
            }
            StringBuilder sb = new StringBuilder();
            for (char c : sentence.toCharArray()) {
                if (Character.isLetter(c)) {
                    sb.append(Character.toLowerCase(c));
                } else {
                    if (sb.length() > 0) {
                        String token = sb.toString();
                        sb.setLength(0);
                    }
                }
            }
            if (sb.length() > 0) {
                context = sb.toString();
            } else {
                context = "";
            }
            this.contextIndex++;
            gen++;
        }
    }

    public Session openSession() {
        synchronized (this) {
            return new Session(this.vocabulary, this.context, vocIndex, contextIndex, revision, gen);
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return new Snapshot(this.vocabulary, context, revision, gen);
        }
    }

    public static class Session {
        private final String[] vocabulary;
        private final String context;
        private final int vocIndex;
        private final int contextIndex;
        private final long revision;
        private final long gen;

        public Session(String[] vocabulary, String context, int vocIndex, int contextIndex, long revision, long gen) {
            this.vocabulary = vocabulary;
            this.context = context;
            this.vocIndex = vocIndex;
            this.contextIndex = contextIndex;
            this.revision = revision;
            this.gen = gen;
        }

        public List<String> findMatches(String prefix) {
            Set<String> matches = new TreeSet<>((a, b) -> {
                int lenA = a.length(), lenB = b.length();
                if (lenA != lenB) return lenB - lenA;
                return a.compareTo(b);
            });
            for (String word : vocabulary) {
                if (word.length() >= prefix.length() && word.toLowerCase().startsWith(prefix.toLowerCase())) {
                    matches.add(word);
                }
            }
            return new ArrayList<>(matches);
        }

        public List<Integer> dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= context.length()) {
                throw new IllegalArgumentException("Invalid token index");
            }
            List<Integer> dependencies = new ArrayList<>();
            for (int i = tokenIndex + 1; i < context.length(); i++) {
                if (Character.isLetter(context.charAt(i))) {
                    dependencies.add(i);
                }
            }
            return dependencies;
        }

        public Snapshot snapshot() {
            return new Snapshot(vocabulary, context, revision, gen);
        }
    }

    public static class Snapshot {
        private final String[] vocabulary;
        private final String context;
        private final long revision;
        private final long gen;

        public Snapshot(String[] vocabulary, String context, long revision, long gen) {
            this.vocabulary = vocabulary;
            this.context = context;
            this.revision = revision;
            this.gen = gen;
        }

        public String[] vocabulary() {
            return Arrays.copyOf(vocabulary, vocabulary.length);
        }

        public String context() {
            return context;
        }

        public long revision() {
            return revision;
        }

        public long gen() {
            return gen;
        }
    }
}
