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
        public final String[] manifest;

        private UserSnapshot(State status, long generation, String[] manifest) {
            this.status = status;
            this.generation = generation;
            this.manifest = manifest;
        }
    }

    public static final class Metrics {
        public final int pendingCount;
        public final int installingCount;
        public final int activeCount;
        public final int failedCount;
        public final int totalUsers;

        private Metrics(int pendingCount, int installingCount, int activeCount,
                        int failedCount, int totalUsers) {
            this.pendingCount = pendingCount;
            this.installingCount = installingCount;
            this.activeCount = activeCount;
            this.failedCount = failedCount;
            this.totalUsers = totalUsers;
        }
    }

    private static final class Attempt {
        private final long generation;
        private boolean done;
        private boolean superseded;
        private String[] manifest;
        private RuntimeException failure;
        private Thread callbackThread;
        private Attempt nextCallback;

        private Attempt(long generation) {
            this.generation = generation;
        }
    }

    private static final class UserRecord {
        private final String username;
        private String[] configuration;
        private State state = State.PENDING;
        private long generation;
        private String[] manifest;
        private RuntimeException failure;
        private Attempt currentAttempt;
        private Attempt activeCallbacks;

        private UserRecord(String username, String[] configuration) {
            this.username = username;
            this.configuration = configuration;
        }
    }

    private final Object monitor = new Object();
    private final Installer installer;
    private UserRecord[] users = new UserRecord[0];
    private int userCount;

    public UserSpaceProvisioner(Installer installer) {
        if (installer == null) {
            throw new IllegalArgumentException("Installer must not be null");
        }
        this.installer = installer;
    }

    public void register(String user, String[] configuration) {
        validateUsername(user);
        String[] copiedConfiguration = copyConfiguration(configuration);

        synchronized (monitor) {
            if (findUser(user) != null) {
                throw new IllegalArgumentException("User is already registered: " + user);
            }
            ensureUserCapacity();
            users[userCount++] = new UserRecord(user, copiedConfiguration);
        }
    }

    public String[] activate(String user) throws InterruptedException {
        validateUsername(user);
        UserRecord record;
        Attempt attempt;
        boolean startInstallation = false;

        synchronized (monitor) {
            record = requireUser(user);
            rejectCallbackRecursion(record);

            if (record.state == State.ACTIVE) {
                return copyArray(record.manifest);
            }
            if (record.state == State.FAILED) {
                throw record.failure;
            }
            if (record.state == State.INSTALLING) {
                attempt = record.currentAttempt;
            } else {
                attempt = beginAttempt(record);
                startInstallation = true;
            }

            if (!startInstallation) {
                while (!attempt.done) {
                    monitor.wait();
                }
                return attemptResult(attempt);
            }
        }

        performInstallation(record, attempt);
        synchronized (monitor) {
            return attemptResult(attempt);
        }
    }

    public String[] reinstall(String user, String[] configuration) {
        validateUsername(user);
        String[] copiedConfiguration = copyConfiguration(configuration);
        UserRecord record;
        Attempt attempt;

        synchronized (monitor) {
            record = requireUser(user);
            rejectCallbackRecursion(record);
            if (record.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("User generation overflow");
            }

            Attempt previous = record.currentAttempt;
            if (previous != null && !previous.done) {
                previous.done = true;
                previous.superseded = true;
            }

            record.generation++;
            record.configuration = copiedConfiguration;
            record.manifest = null;
            record.failure = null;
            attempt = beginAttempt(record);
            monitor.notifyAll();
        }

        performInstallation(record, attempt);
        synchronized (monitor) {
            return attemptResult(attempt);
        }
    }

    public UserSnapshot verify(String user) {
        validateUsername(user);
        synchronized (monitor) {
            UserRecord record = requireUser(user);
            return new UserSnapshot(record.state, record.generation,
                    record.manifest == null ? null : copyArray(record.manifest));
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (monitor) {
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

            return new Metrics(pending, installing, active, failed, userCount);
        }
    }

    private void performInstallation(UserRecord record, Attempt attempt) {
        String[] installedManifest = null;
        RuntimeException failure = null;

        try {
            String[] suppliedConfiguration = copyArray(record.configuration);
            installedManifest = copyManifest(
                    installer.install(record.username, suppliedConfiguration));
        } catch (RuntimeException exception) {
            failure = exception;
        } catch (Error error) {
            completeAttempt(record, attempt, null,
                    new IllegalStateException("Installer failed with an Error", error));
            throw error;
        }

        completeAttempt(record, attempt, installedManifest, failure);
    }

    private void completeAttempt(UserRecord record, Attempt attempt,
                                 String[] manifest, RuntimeException failure) {
        synchronized (monitor) {
            removeActiveCallback(record, attempt);

            if (!attempt.done
                    && record.currentAttempt == attempt
                    && record.generation == attempt.generation
                    && record.state == State.INSTALLING) {
                if (failure == null) {
                    record.manifest = manifest;
                    record.failure = null;
                    record.state = State.ACTIVE;
                    attempt.manifest = manifest;
                } else {
                    record.manifest = null;
                    record.failure = failure;
                    record.state = State.FAILED;
                    attempt.failure = failure;
                }
                attempt.done = true;
            } else if (!attempt.done) {
                attempt.done = true;
                attempt.superseded = true;
            }

            monitor.notifyAll();
        }
    }

    private Attempt beginAttempt(UserRecord record) {
        Attempt attempt = new Attempt(record.generation);
        attempt.callbackThread = Thread.currentThread();
        attempt.nextCallback = record.activeCallbacks;
        record.activeCallbacks = attempt;
        record.currentAttempt = attempt;
        record.state = State.INSTALLING;
        return attempt;
    }

    private String[] attemptResult(Attempt attempt) {
        if (attempt.superseded) {
            throw new IllegalStateException("Installation was superseded");
        }
        if (attempt.failure != null) {
            throw attempt.failure;
        }
        if (!attempt.done || attempt.manifest == null) {
            throw new IllegalStateException("Installation did not complete successfully");
        }
        return copyArray(attempt.manifest);
    }

    private void rejectCallbackRecursion(UserRecord record) {
        Thread currentThread = Thread.currentThread();
        for (Attempt callback = record.activeCallbacks;
             callback != null;
             callback = callback.nextCallback) {
            if (callback.callbackThread == currentThread) {
                throw new IllegalStateException(
                        "Installer callbacks cannot activate or reinstall their own user");
            }
        }
    }

    private void removeActiveCallback(UserRecord record, Attempt attempt) {
        Attempt previous = null;
        Attempt current = record.activeCallbacks;

        while (current != null) {
            if (current == attempt) {
                if (previous == null) {
                    record.activeCallbacks = current.nextCallback;
                } else {
                    previous.nextCallback = current.nextCallback;
                }
                attempt.callbackThread = null;
                attempt.nextCallback = null;
                return;
            }
            previous = current;
            current = current.nextCallback;
        }
    }

    private UserRecord requireUser(String user) {
        UserRecord record = findUser(user);
        if (record == null) {
            throw new IllegalArgumentException("Unknown user: " + user);
        }
        return record;
    }

    private UserRecord findUser(String user) {
        for (int i = 0; i < userCount; i++) {
            if (users[i].username.equals(user)) {
                return users[i];
            }
        }
        return null;
    }

    private void ensureUserCapacity() {
        if (userCount < users.length) {
            return;
        }
        if (users.length == Integer.MAX_VALUE) {
            throw new IllegalStateException("Too many registered users");
        }

        int newCapacity;
        if (users.length == 0) {
            newCapacity = 4;
        } else if (users.length <= Integer.MAX_VALUE / 2) {
            newCapacity = users.length * 2;
        } else {
            newCapacity = Integer.MAX_VALUE;
        }

        if (newCapacity <= users.length) {
            throw new IllegalStateException("Too many registered users");
        }

        UserRecord[] expanded = new UserRecord[newCapacity];
        for (int i = 0; i < userCount; i++) {
            expanded[i] = users[i];
        }
        users = expanded;
    }

    private static void validateUsername(String user) {
        if (user == null || user.length() == 0) {
            throw new IllegalArgumentException("Username must not be null or empty");
        }
    }

    private static String[] copyConfiguration(String[] configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("Configuration must not be null");
        }
        for (int i = 0; i < configuration.length; i++) {
            if (configuration[i] == null) {
                throw new IllegalArgumentException(
                        "Configuration entries must not be null");
            }
        }
        return copyArray(configuration);
    }

    private static String[] copyManifest(String[] manifest) {
        if (manifest == null) {
            throw new IllegalStateException("Installer returned a null manifest");
        }
        for (int i = 0; i < manifest.length; i++) {
            if (manifest[i] == null) {
                throw new IllegalStateException(
                        "Installer returned a manifest with a null entry");
            }
        }
        return copyArray(manifest);
    }

    private static String[] copyArray(String[] source) {
        String[] copy = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }
}
