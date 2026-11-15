@UserSpaceProvisioner.ThreadSafe
public final class UserSpaceProvisioner {
    @interface ThreadSafe {}

    public interface Installer {
        String[] install(String user, String[] configuration);
    }

    public enum State {
        PENDING,
        INSTALLING,
        ACTIVE,
        FAILED
    }

    public static final class UserSnapshot {
        public final State state;
        public final long generation;
        public final String[] manifest;

        private UserSnapshot(State state, long generation, String[] manifest) {
            this.state = state;
            this.generation = generation;
            this.manifest = copyStrings(manifest);
        }

        public String[] getManifest() {
            return copyStrings(manifest);
        }
    }

    public static final class Metrics {
        public final int pending;
        public final int installing;
        public final int active;
        public final int failed;
        public final int total;

        private Metrics(int pending, int installing, int active, int failed) {
            this.pending = pending;
            this.installing = installing;
            this.active = active;
            this.failed = failed;
            this.total = pending + installing + active + failed;
        }
    }

    private static final class User {
        final String name;
        String[] configuration;
        State state;
        long generation;
        String[] manifest;
        Throwable failure;
        Attempt attempt;

        User(String name, String[] configuration) {
            this.name = name;
            this.configuration = configuration;
            this.state = State.PENDING;
        }
    }

    private static final class Attempt {
        final long generation;
        final String[] configuration;
        boolean done;
        boolean superseded;
        String[] manifest;
        Throwable failure;

        Attempt(long generation, String[] configuration) {
            this.generation = generation;
            this.configuration = configuration;
        }
    }

    private final Object lock = new Object();
    private final Installer installer;
    private User[] users = new User[8];
    private int userCount;

    public UserSpaceProvisioner(Installer installer) {
        if (installer == null) {
            throw new IllegalArgumentException("installer must not be null");
        }
        this.installer = installer;
    }

    public void register(String user, String[] configuration) {
        validateUserName(user);
        String[] copiedConfiguration = copyConfiguration(configuration);

        synchronized (lock) {
            if (findUser(user) != null) {
                throw new IllegalArgumentException("User is already registered: " + user);
            }
            ensureUserCapacity();
            users[userCount++] = new User(user, copiedConfiguration);
        }
    }

    public String[] activate(String user) throws InterruptedException {
        validateUserName(user);
        Attempt attemptToRun = null;
        Attempt attemptToJoin = null;

        synchronized (lock) {
            User record = requireUser(user);
            switch (record.state) {
                case PENDING:
                    attemptToRun = new Attempt(
                            record.generation, copyStrings(record.configuration));
                    record.attempt = attemptToRun;
                    record.manifest = null;
                    record.failure = null;
                    record.state = State.INSTALLING;
                    break;
                case INSTALLING:
                    attemptToJoin = record.attempt;
                    break;
                case ACTIVE:
                    return copyStrings(record.manifest);
                case FAILED:
                    throwFailure(record.failure);
                    break;
                default:
                    throw new IllegalStateException("Unknown user state");
            }
        }

        if (attemptToRun != null) {
            return runAttempt(user, attemptToRun);
        }
        return awaitAttempt(attemptToJoin);
    }

    public String[] reinstall(String user, String[] configuration) {
        validateUserName(user);
        String[] copiedConfiguration = copyConfiguration(configuration);
        Attempt attempt;

        synchronized (lock) {
            User record = requireUser(user);
            if (record.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("User generation overflow");
            }

            long nextGeneration = record.generation + 1;
            attempt = new Attempt(nextGeneration, copyStrings(copiedConfiguration));

            if (record.state == State.INSTALLING && record.attempt != null) {
                record.attempt.superseded = true;
                record.attempt.done = true;
            }

            record.configuration = copiedConfiguration;
            record.generation = nextGeneration;
            record.manifest = null;
            record.failure = null;
            record.attempt = attempt;
            record.state = State.INSTALLING;
            lock.notifyAll();
        }

        return runAttempt(user, attempt);
    }

    public UserSnapshot verify(String user) {
        validateUserName(user);
        synchronized (lock) {
            User record = requireUser(user);
            return new UserSnapshot(record.state, record.generation, record.manifest);
        }
    }

    public Metrics snapshotMetrics() {
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
                    default:
                        throw new IllegalStateException("Unknown user state");
                }
            }

            return new Metrics(pending, installing, active, failed);
        }
    }

    private String[] runAttempt(String user, Attempt attempt) {
        String[] installedManifest = null;
        Throwable installFailure = null;

        try {
            installedManifest = copyManifest(
                    installer.install(user, copyStrings(attempt.configuration)));
        } catch (RuntimeException exception) {
            installFailure = exception;
        } catch (Error error) {
            installFailure = error;
        }

        synchronized (lock) {
            if (!attempt.done) {
                User record = findUser(user);
                if (record != null
                        && record.attempt == attempt
                        && record.generation == attempt.generation
                        && record.state == State.INSTALLING) {
                    if (installFailure == null) {
                        record.manifest = installedManifest;
                        record.failure = null;
                        record.state = State.ACTIVE;
                        attempt.manifest = installedManifest;
                    } else {
                        record.manifest = null;
                        record.failure = installFailure;
                        record.state = State.FAILED;
                        attempt.failure = installFailure;
                    }
                } else {
                    attempt.superseded = true;
                }
                attempt.done = true;
                lock.notifyAll();
            }

            if (attempt.superseded) {
                throw supersededException();
            }
            if (attempt.failure != null) {
                throwFailure(attempt.failure);
            }
            return copyStrings(attempt.manifest);
        }
    }

    private String[] awaitAttempt(Attempt attempt) throws InterruptedException {
        synchronized (lock) {
            while (!attempt.done) {
                lock.wait();
            }
            if (attempt.superseded) {
                throw supersededException();
            }
            if (attempt.failure != null) {
                throwFailure(attempt.failure);
            }
            return copyStrings(attempt.manifest);
        }
    }

    private User requireUser(String name) {
        User record = findUser(name);
        if (record == null) {
            throw new IllegalArgumentException("Unknown user: " + name);
        }
        return record;
    }

    private User findUser(String name) {
        for (int i = 0; i < userCount; i++) {
            if (users[i].name.equals(name)) {
                return users[i];
            }
        }
        return null;
    }

    private void ensureUserCapacity() {
        if (userCount == users.length) {
            if (users.length == Integer.MAX_VALUE) {
                throw new IllegalStateException("User capacity exhausted");
            }
            int newCapacity = users.length > Integer.MAX_VALUE / 2
                    ? Integer.MAX_VALUE
                    : users.length * 2;
            User[] expanded = new User[newCapacity];
            for (int i = 0; i < userCount; i++) {
                expanded[i] = users[i];
            }
            users = expanded;
        }
    }

    private static void validateUserName(String user) {
        if (user == null || user.length() == 0) {
            throw new IllegalArgumentException("User name must not be null or empty");
        }
    }

    private static String[] copyConfiguration(String[] configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("Configuration must not be null");
        }
        String[] copy = copyStrings(configuration);
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == null) {
                throw new IllegalArgumentException("Configuration entries must not be null");
            }
        }
        return copy;
    }

    private static String[] copyManifest(String[] manifest) {
        if (manifest == null) {
            throw new IllegalStateException("Installer returned a null manifest");
        }
        String[] copy = copyStrings(manifest);
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == null) {
                throw new IllegalStateException("Installer returned a manifest with a null entry");
            }
        }
        return copy;
    }

    private static String[] copyStrings(String[] values) {
        if (values == null) {
            return null;
        }
        String[] copy = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            copy[i] = values[i];
        }
        return copy;
    }

    private static IllegalStateException supersededException() {
        return new IllegalStateException("Installation attempt was superseded");
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
}
