@UserSpaceProvisioner.ThreadSafe
public final class UserSpaceProvisioner {
    @interface ThreadSafe {}

    public interface Installer {
        String[] install(String user, String[] configuration);
    }

    public enum State {
        PENDING, INSTALLING, ACTIVE, FAILED
    }

    public static final class UserSnapshot {
        private final State state;
        private final long generation;
        private final String[] manifest;

        private UserSnapshot(State state, long generation, String[] manifest) {
            this.state = state;
            this.generation = generation;
            this.manifest = manifest;
        }

        public State getState() {
            return state;
        }

        public long getGeneration() {
            return generation;
        }

        public String[] getManifest() {
            return manifest == null ? null : copyArray(manifest);
        }
    }

    public static final class MetricsSnapshot {
        private final int pending;
        private final int installing;
        private final int active;
        private final int failed;
        private final int total;

        private MetricsSnapshot(
                int pending, int installing, int active, int failed, int total) {
            this.pending = pending;
            this.installing = installing;
            this.active = active;
            this.failed = failed;
            this.total = total;
        }

        public int getPending() {
            return pending;
        }

        public int getInstalling() {
            return installing;
        }

        public int getActive() {
            return active;
        }

        public int getFailed() {
            return failed;
        }

        public int getTotal() {
            return total;
        }
    }

    private static final class Attempt {
        final String[] configuration;
        boolean done;
        boolean superseded;
        String[] manifest;
        Throwable failure;

        Attempt(String[] configuration) {
            this.configuration = configuration;
        }
    }

    private static final class User {
        final String name;
        String[] configuration;
        long generation;
        State state;
        String[] manifest;
        Attempt attempt;

        User(String name, String[] configuration) {
            this.name = name;
            this.configuration = configuration;
            this.generation = 1;
            this.state = State.PENDING;
        }
    }

    private final Object lock = new Object();
    private final Installer installer;
    private User[] users = new User[8];
    private int userCount;

    public UserSpaceProvisioner(Installer installer) {
        if (installer == null) {
            throw new IllegalArgumentException("Installer must not be null");
        }
        this.installer = installer;
    }

    public void register(String user, String[] configuration) {
        validateUser(user);
        String[] copiedConfiguration = copyConfiguration(configuration);

        synchronized (lock) {
            if (findUser(user) != null) {
                throw new IllegalArgumentException("User already registered: " + user);
            }
            if (userCount == users.length) {
                growUsers();
            }
            users[userCount] = new User(user, copiedConfiguration);
            userCount++;
        }
    }

    public String[] activate(String user) throws InterruptedException {
        validateUser(user);
        User record;
        Attempt attempt;

        synchronized (lock) {
            record = requireUser(user);

            if (record.state == State.ACTIVE) {
                return copyArray(record.manifest);
            }
            if (record.state == State.FAILED) {
                throwFailure(record.attempt.failure);
            }
            if (record.state == State.INSTALLING) {
                attempt = record.attempt;
                while (!attempt.done) {
                    lock.wait();
                }
                if (attempt.superseded) {
                    throw superseded(user);
                }
                if (attempt.failure != null) {
                    throwFailure(attempt.failure);
                }
                return copyArray(attempt.manifest);
            }

            attempt = new Attempt(copyArray(record.configuration));
            record.attempt = attempt;
            record.state = State.INSTALLING;
        }

        return runAttempt(record, attempt);
    }

    public String[] reinstall(String user, String[] configuration) {
        validateUser(user);
        String[] copiedConfiguration = copyConfiguration(configuration);
        User record;
        Attempt attempt;

        synchronized (lock) {
            record = requireUser(user);
            if (record.generation == 9223372036854775807L) {
                throw new IllegalStateException("Generation overflow: " + user);
            }

            attempt = new Attempt(copyArray(copiedConfiguration));
            if (record.state == State.INSTALLING) {
                record.attempt.superseded = true;
                record.attempt.done = true;
            }

            record.configuration = copiedConfiguration;
            record.generation++;
            record.manifest = null;
            record.attempt = attempt;
            record.state = State.INSTALLING;
            lock.notifyAll();
        }

        return runAttempt(record, attempt);
    }

    public UserSnapshot verify(String user) {
        validateUser(user);
        synchronized (lock) {
            User record = requireUser(user);
            String[] manifest = record.manifest == null
                    ? null : copyArray(record.manifest);
            return new UserSnapshot(record.state, record.generation, manifest);
        }
    }

    public MetricsSnapshot snapshotMetrics() {
        synchronized (lock) {
            int pending = 0;
            int installing = 0;
            int active = 0;
            int failed = 0;

            for (int i = 0; i < userCount; i++) {
                switch (users[i].state) {
                    case PENDING:
                        pending++;
                        break;
                    case INSTALLING:
                        installing++;
                        break;
                    case ACTIVE:
                        active++;
                        break;
                    case FAILED:
                        failed++;
                        break;
                }
            }

            return new MetricsSnapshot(
                    pending, installing, active, failed, userCount);
        }
    }

    private String[] runAttempt(User record, Attempt attempt) {
        String[] manifest;
        String[] result;
        try {
            String[] installed = installer.install(record.name, attempt.configuration);
            manifest = copyManifest(installed);
            result = copyArray(manifest);
        } catch (RuntimeException failure) {
            publishFailure(record, attempt, failure);
            throw failure;
        } catch (Error failure) {
            publishFailure(record, attempt, failure);
            throw failure;
        }

        synchronized (lock) {
            if (attempt.superseded) {
                throw superseded(record.name);
            }
            record.manifest = manifest;
            record.state = State.ACTIVE;
            attempt.manifest = manifest;
            attempt.done = true;
            lock.notifyAll();
        }
        return result;
    }

    private void publishFailure(User record, Attempt attempt, Throwable failure) {
        synchronized (lock) {
            if (attempt.superseded) {
                throw superseded(record.name);
            }
            attempt.failure = failure;
            attempt.done = true;
            record.manifest = null;
            record.state = State.FAILED;
            lock.notifyAll();
        }
    }

    private static void throwFailure(Throwable failure) {
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new IllegalStateException("Installation failed", failure);
    }

    private static IllegalStateException superseded(String user) {
        return new IllegalStateException("Installation superseded for user: " + user);
    }

    private User requireUser(String name) {
        User user = findUser(name);
        if (user == null) {
            throw new IllegalArgumentException("Unknown user: " + name);
        }
        return user;
    }

    private User findUser(String name) {
        for (int i = 0; i < userCount; i++) {
            if (users[i].name.equals(name)) {
                return users[i];
            }
        }
        return null;
    }

    private void growUsers() {
        if (users.length == 2147483647) {
            throw new IllegalStateException("User capacity exhausted");
        }
        int capacity = users.length > 1073741823
                ? 2147483647 : users.length * 2;
        User[] expanded = new User[capacity];
        for (int i = 0; i < userCount; i++) {
            expanded[i] = users[i];
        }
        users = expanded;
    }

    private static void validateUser(String user) {
        if (user == null || user.length() == 0) {
            throw new IllegalArgumentException("User must not be null or empty");
        }
    }

    private static String[] copyConfiguration(String[] configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("Configuration must not be null");
        }
        String[] copy = new String[configuration.length];
        for (int i = 0; i < configuration.length; i++) {
            String entry = configuration[i];
            if (entry == null) {
                throw new IllegalArgumentException(
                        "Configuration entries must not be null");
            }
            copy[i] = entry;
        }
        return copy;
    }

    private static String[] copyManifest(String[] manifest) {
        if (manifest == null) {
            throw new IllegalStateException("Installer returned a null manifest");
        }
        String[] copy = new String[manifest.length];
        for (int i = 0; i < manifest.length; i++) {
            String entry = manifest[i];
            if (entry == null) {
                throw new IllegalStateException(
                        "Installer returned a null manifest entry");
            }
            copy[i] = entry;
        }
        return copy;
    }

    private static String[] copyArray(String[] source) {
        String[] copy = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }
}
