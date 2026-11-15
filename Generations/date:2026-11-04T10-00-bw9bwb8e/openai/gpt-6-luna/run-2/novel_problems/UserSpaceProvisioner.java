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
        public final State status;
        public final long generation;
        private final String[] manifest;

        private UserSnapshot(State status, long generation, String[] manifest) {
            this.status = status;
            this.generation = generation;
            this.manifest = copy(manifest);
        }

        public String[] getManifest() {
            return copy(manifest);
        }
    }

    public static final class MetricsSnapshot {
        public final int pendingCount;
        public final int installingCount;
        public final int activeCount;
        public final int failedCount;
        public final int totalUsers;

        private MetricsSnapshot(int pendingCount, int installingCount,
                                int activeCount, int failedCount, int totalUsers) {
            this.pendingCount = pendingCount;
            this.installingCount = installingCount;
            this.activeCount = activeCount;
            this.failedCount = failedCount;
            this.totalUsers = totalUsers;
        }
    }

    private static final int ATTEMPT_RUNNING = 0;
    private static final int ATTEMPT_SUCCEEDED = 1;
    private static final int ATTEMPT_FAILED = 2;
    private static final int ATTEMPT_SUPERSEDED = 3;

    private static final class Attempt {
        final long generation;
        int outcome = ATTEMPT_RUNNING;
        String[] manifest;
        RuntimeException failure;

        Attempt(long generation) {
            this.generation = generation;
        }
    }

    private static final class UserRecord {
        final String name;
        String[] configuration;
        long generation;
        State state = State.PENDING;
        String[] manifest;
        RuntimeException failure;
        Attempt attempt;
        UserRecord next;

        UserRecord(String name, String[] configuration) {
            this.name = name;
            this.configuration = configuration;
        }
    }

    private final Object lock = new Object();
    private final Installer installer;
    private UserRecord users;
    private int userCount;

    public UserSpaceProvisioner(Installer installer) {
        if (installer == null) {
            throw new IllegalArgumentException("installer must not be null");
        }
        this.installer = installer;
    }

    public void register(String user, String[] configuration) {
        validateUser(user);
        String[] savedConfiguration = copyConfiguration(configuration);

        synchronized (lock) {
            if (findUser(user) != null) {
                throw new IllegalArgumentException("duplicate user: " + user);
            }
            UserRecord record = new UserRecord(user, savedConfiguration);
            record.next = users;
            users = record;
            userCount++;
        }
    }

    public String[] activate(String user) throws InterruptedException {
        Attempt attempt;
        String[] configuration;

        synchronized (lock) {
            UserRecord record = requireUser(user);

            if (record.state == State.ACTIVE) {
                return copy(record.manifest);
            }
            if (record.state == State.FAILED) {
                throw record.failure;
            }
            if (record.state == State.INSTALLING) {
                attempt = record.attempt;
                while (attempt.outcome == ATTEMPT_RUNNING) {
                    lock.wait();
                }
                return resolveAttempt(attempt);
            }

            attempt = new Attempt(record.generation);
            record.attempt = attempt;
            record.state = State.INSTALLING;
            record.manifest = null;
            record.failure = null;
            configuration = copy(record.configuration);
        }

        return runInstallation(user, configuration, attempt);
    }

    public String[] reinstall(String user, String[] configuration) {
        validateUser(user);
        String[] savedConfiguration = copyConfiguration(configuration);

        Attempt attempt;
        long generation;
        synchronized (lock) {
            UserRecord record = requireUser(user);
            if (record.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("generation overflow for user: " + user);
            }

            if (record.state == State.INSTALLING && record.attempt != null
                    && record.attempt.outcome == ATTEMPT_RUNNING) {
                record.attempt.outcome = ATTEMPT_SUPERSEDED;
            }

            generation = record.generation + 1;
            record.generation = generation;
            record.configuration = savedConfiguration;
            record.manifest = null;
            record.failure = null;
            record.state = State.INSTALLING;
            attempt = new Attempt(generation);
            record.attempt = attempt;
            lock.notifyAll();
        }

        return runInstallation(user, copy(savedConfiguration), attempt);
    }

    public UserSnapshot verify(String user) {
        synchronized (lock) {
            UserRecord record = requireUser(user);
            return new UserSnapshot(record.state, record.generation,
                    record.state == State.ACTIVE ? record.manifest : null);
        }
    }

    public MetricsSnapshot snapshotMetrics() {
        synchronized (lock) {
            int pending = 0;
            int installing = 0;
            int active = 0;
            int failed = 0;

            for (UserRecord record = users; record != null; record = record.next) {
                switch (record.state) {
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

            return new MetricsSnapshot(pending, installing, active, failed, userCount);
        }
    }

    private String[] runInstallation(String user, String[] configuration, Attempt attempt) {
        String[] installedManifest;
        try {
            String[] callbackResult = installer.install(user, copy(configuration));
            installedManifest = validateAndCopyManifest(callbackResult);
        } catch (RuntimeException failure) {
            return finishFailure(attempt, failure);
        }

        synchronized (lock) {
            if (attempt.outcome == ATTEMPT_SUPERSEDED) {
                throw supersededException(attempt);
            }

            UserRecord record = findUser(user);
            if (record == null || record.generation != attempt.generation
                    || record.attempt != attempt) {
                attempt.outcome = ATTEMPT_SUPERSEDED;
                lock.notifyAll();
                throw supersededException(attempt);
            }

            record.manifest = copy(installedManifest);
            record.failure = null;
            record.state = State.ACTIVE;
            attempt.manifest = copy(installedManifest);
            attempt.outcome = ATTEMPT_SUCCEEDED;
            lock.notifyAll();
            return copy(attempt.manifest);
        }
    }

    private String[] finishFailure(Attempt attempt, RuntimeException failure) {
        synchronized (lock) {
            if (attempt.outcome == ATTEMPT_SUPERSEDED) {
                throw supersededException(attempt);
            }

            UserRecord record = findUserForAttempt(attempt);
            if (record == null || record.generation != attempt.generation
                    || record.attempt != attempt) {
                attempt.outcome = ATTEMPT_SUPERSEDED;
                lock.notifyAll();
                throw supersededException(attempt);
            }

            record.manifest = null;
            record.failure = failure;
            record.state = State.FAILED;
            attempt.failure = failure;
            attempt.outcome = ATTEMPT_FAILED;
            lock.notifyAll();
            throw failure;
        }
    }

    private UserRecord findUserForAttempt(Attempt attempt) {
        for (UserRecord record = users; record != null; record = record.next) {
            if (record.attempt == attempt) {
                return record;
            }
        }
        return null;
    }

    private static String[] resolveAttempt(Attempt attempt) {
        if (attempt.outcome == ATTEMPT_SUCCEEDED) {
            return copy(attempt.manifest);
        }
        if (attempt.outcome == ATTEMPT_FAILED) {
            throw attempt.failure;
        }
        throw supersededException(attempt);
    }

    private UserRecord requireUser(String user) {
        UserRecord record = findUser(user);
        if (record == null) {
            throw new IllegalArgumentException("unknown user: " + user);
        }
        return record;
    }

    private UserRecord findUser(String user) {
        for (UserRecord record = users; record != null; record = record.next) {
            if (record.name.equals(user)) {
                return record;
            }
        }
        return null;
    }

    private static void validateUser(String user) {
        if (user == null || user.length() == 0) {
            throw new IllegalArgumentException("user must not be null or empty");
        }
    }

    private static String[] copyConfiguration(String[] configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        String[] result = copy(configuration);
        for (int i = 0; i < result.length; i++) {
            if (result[i] == null) {
                throw new IllegalArgumentException("configuration entries must not be null");
            }
        }
        return result;
    }

    private static String[] validateAndCopyManifest(String[] manifest) {
        if (manifest == null) {
            throw new IllegalStateException("installer returned a null manifest");
        }
        String[] result = copy(manifest);
        for (int i = 0; i < result.length; i++) {
            if (result[i] == null) {
                throw new IllegalStateException("installer returned a manifest with a null entry");
            }
        }
        return result;
    }

    private static String[] copy(String[] source) {
        if (source == null) {
            return null;
        }
        String[] result = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static IllegalStateException supersededException(Attempt attempt) {
        return new IllegalStateException(
                "installation generation " + attempt.generation + " was superseded");
    }
}
