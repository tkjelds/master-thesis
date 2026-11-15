@LexicalGraphProvider.ThreadSafe
public class LexicalGraphProvider {
    @interface ThreadSafe {}

    private String[] vocabulary;
    private int vocabularyRevision = 0;
    private String context;
    private int contextGeneration = 0;
    private Session[] sessions = new Session[1];
    private int sessionCount = 0;

    public synchronized void replaceVocabulary(String[] words) {
        if (words == null || anyInvalid(words)) return;
        String[] newVocabulary = normalizeAndDeduplicate(words);
        vocabularyRevision++;
        updateSessions(newVocabulary, contextGeneration);
    }

    public synchronized void setContext(String sentence) {
        if (sentence == null || !isValid(sentence)) return;
        String[] newContext = tokenize(sentence);
        contextGeneration++;
        updateSessions(vocabulary, newContext);
    }

    public synchronized Session openSession() {
        if (isCounterOverflow()) return null;
        sessionCount++;
        return new Session(vocabulary, context);
    }

    public synchronized LexicalGraphProvider snapshot() {
        return new LexicalGraphProvider(vocabulary, context);
    }

    private boolean anyInvalid(String[] words) {
        for (String word : words) {
            if (!isValid(word)) return true;
        }
        return false;
    }

    private boolean isValid(String s) {
        return !s.isEmpty() && s.matches("[a-zA-Z]+");
    }

    private String[] normalizeAndDeduplicate(String[] words) {
        Set<String> set = new HashSet<>();
        for (String word : words) {
            set.add(word.toLowerCase());
        }
        return set.toArray(new String[0]);
    }

    private String[] tokenize(String sentence) {
        ArrayList<String> list = new ArrayList<>();
        for (String token : sentence.split("[^a-zA-Z]+")) {
            if (!token.isEmpty()) list.add(token.toLowerCase());
        }
        return list.toArray(new String[0]);
    }

    private void updateSessions(Object newValue, int newGeneration) {
        for (Session session : sessions) {
            session.update(newValue, newGeneration);
        }
    }

    private boolean isCounterOverflow() {
        return sessionCount == Integer.MAX_VALUE;
    }

    public static class Session {
        private String[] vocabulary = new String[0];
        private int vocabularyRevision;
        private String[] context = new String[0];
        private int contextGeneration;

        public Session(Object vocabulary, Object context) {
            update(vocabulary, (int) 0);
            this.context = (String[]) context;
        }

        private void update(Object vocabulary, int newGeneration) {
            this.vocabulary = (String[]) vocabulary;
            this.vocabularyRevision++;
            contextGeneration = newGeneration;
        }

        public List<String> findMatches(String prefix) {
            if (prefix == null || !isValid(prefix)) throw new IllegalArgumentException();
            ArrayList<String> matches = new ArrayList<>();
            for (String word : vocabulary) {
                if (word.toLowerCase().startsWith(prefix)) {
                    matches.add(word);
                }
            }
            Collections.sort(matches, (a, b) -> {
                int lenDiff = b.length() - a.length();
                return lenDiff != 0 ? lenDiff : a.compareTo(b);
            });
            return matches;
        }

        public List<Integer> dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= context.length) throw new IllegalArgumentException();
            ArrayList<Integer> result = new ArrayList<>();
            for (int i = tokenIndex + 1; i < context.length; i++) {
                if (Arrays.asList(vocabulary).contains(context[i])) {
                    result.add(i);
                }
            }
            return result;
        }

        public LexicalGraphProvider snapshot() {
            return new LexicalGraphProvider(vocabulary, context);
        }

        private boolean isValid(String s) {
            return !s.isEmpty() && s.matches("[a-zA-Z]+");
        }
    }
}
