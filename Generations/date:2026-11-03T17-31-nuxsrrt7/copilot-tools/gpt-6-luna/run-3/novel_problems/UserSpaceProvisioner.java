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

    public static final class Snapshot {
        public final State status;
        public final long generation;
        public final String[] manifest;

        private Snapshot(State status, long generation, String[] manifest) {
            this.status = status;
            this.generation = generation;
            this.manifest = manifest;
        }
    }

    public static final class Metrics {
        public final long pendingCount;
        public final long installingCount;
        public final long activeCount;
        public final long failedCount;
        public final long totalUsers;

        private Metrics(long pendingCount, long installingCount,
                        long activeCount, long failedCount, long totalUsers) {
            this.pendingCount = pendingCount;
            this.installingCount = installingCount;
            this.activeCount = activeCount;
            this.failedCount = failedCount;
            this.totalUsers = totalUsers;
        }
    }

    private static final class UserRecord {
        final String name;
        UserRecord next;
        String[] configuration;
        String[] manifest;
        State state = State.PENDING;
        long generation;
        Throwable failure;
        Thread installerThread;

        UserRecord(String name, String[] configuration) {
            this.name = name;
            this.configuration = configuration;
        }
    }

    private final Object monitor = new Object();
    private final Installer installer;
    private UserRecord users;

    public UserSpaceProvisioner(Installer installer) {
        if (installer == null) {
            throw new IllegalArgumentException("Installer must not be null");
        }
        this.installer = installer;
    }

    public void register(String user, String[] configuration) {
        validateUserName(user);
        String[] copiedConfiguration = copyAndValidateConfiguration(configuration);
        UserRecord record = new UserRecord(user, copiedConfiguration);

        synchronized (monitor) {
            if (findUserLocked(user) != null) {
                throw new IllegalArgumentException("User is already registered: " + user);
            }
            record.next = users;
            users = record;
        }
    }

    public String[] activate(String user) throws InterruptedException {
        validateUserName(user);
        UserRecord record;
        long generation;
        String[] configuration;

        synchronized (monitor) {
            record = requireUserLocked(user);

            if (record.state == State.ACTIVE) {
                return copyArray(record.manifest);
            }
            if (record.state == State.FAILED) {
                throwFailure(record.failure);
            }
            if (record.state == State.INSTALLING) {
                if (record.installerThread == Thread.currentThread()) {
                    throw new IllegalStateException(
                            "An installer callback cannot recursively activate its user");
                }

                long joinedGeneration = record.generation;
                while (record.generation == joinedGeneration
                        && record.state == State.INSTALLING) {
                    monitor.wait();
                }

                if (record.generation != joinedGeneration) {
                    throw superseded(joinedGeneration);
                }
                if (record.state == State.ACTIVE) {
                    return copyArray(record.manifest);
                }
                if (record.state == State.FAILED) {
                    throwFailure(record.failure);
                }
                throw new IllegalStateException("Installation attempt ended unexpectedly");
            }

            record.state = State.INSTALLING;
            record.failure = null;
            record.manifest = null;
            record.installerThread = Thread.currentThread();
            generation = record.generation;
            configuration = copyArray(record.configuration);
        }

        return runInstallation(record, generation, configuration);
    }

    public String[] reinstall(String user, String[] configuration) {
        validateUserName(user);
        String[] copiedConfiguration = copyAndValidateConfiguration(configuration);
        UserRecord record;
        long generation;

        synchronized (monitor) {
            record = requireUserLocked(user);
            if (record.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("User generation overflow");
            }
            if (record.state == State.INSTALLING
                    && record.installerThread == Thread.currentThread()) {
                throw new IllegalStateException(
                        "An installer callback cannot recursively reinstall its user");
            }

            generation = record.generation + 1;
            record.generation = generation;
            record.configuration = copiedConfiguration;
            record.manifest = null;
            record.failure = null;
            record.state = State.INSTALLING;
            record.installerThread = Thread.currentThread();
            monitor.notifyAll();
        }

        return runInstallation(record, generation, copyArray(copiedConfiguration));
    }

    public Snapshot verify(String user) {
        validateUserName(user);
        synchronized (monitor) {
            UserRecord record = requireUserLocked(user);
            String[] manifest = record.state == State.ACTIVE
                    ? copyArray(record.manifest)
                    : null;
            return new Snapshot(record.state, record.generation, manifest);
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (monitor) {
            long pending = 0;
            long installing = 0;
            long active = 0;
            long failed = 0;
            long total = 0;

            for (UserRecord record = users; record != null; record = record.next) {
                total++;
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
                    default:
                        throw new IllegalStateException("Unknown user state");
                }
            }

            return new Metrics(pending, installing, active, failed, total);
        }
    }

    private String[] runInstallation(UserRecord record, long generation,
                                     String[] configuration) {
        String[] completedManifest = null;
        Throwable failure = null;

        try {
            completedManifest = copyAndValidateManifest(
                    installer.install(record.name, copyArray(configuration)));
        } catch (RuntimeException exception) {
            failure = exception;
        } catch (Error error) {
            failure = error;
        }

        boolean currentAttempt;
        synchronized (monitor) {
            currentAttempt = record.generation == generation
                    && record.state == State.INSTALLING;
            if (currentAttempt) {
                record.installerThread = null;
                if (failure == null) {
                    record.manifest = completedManifest;
                    record.failure = null;
                    record.state = State.ACTIVE;
                } else {
                    record.manifest = null;
                    record.failure = failure;
                    record.state = State.FAILED;
                }
            }
            monitor.notifyAll();
        }

        if (!currentAttempt) {
            throw superseded(generation);
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        return copyArray(completedManifest);
    }

    private UserRecord requireUserLocked(String user) {
        UserRecord record = findUserLocked(user);
        if (record == null) {
            throw new IllegalArgumentException("Unknown user: " + user);
        }
        return record;
    }

    private UserRecord findUserLocked(String user) {
        for (UserRecord record = users; record != null; record = record.next) {
            if (record.name.equals(user)) {
                return record;
            }
        }
        return null;
    }

    private static void validateUserName(String user) {
        if (user == null || user.length() == 0) {
            throw new IllegalArgumentException("User name must not be null or empty");
        }
    }

    private static String[] copyAndValidateConfiguration(String[] configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("Configuration must not be null");
        }
        String[] copy = new String[configuration.length];
        for (int i = 0; i < configuration.length; i++) {
            if (configuration[i] == null) {
                throw new IllegalArgumentException(
                        "Configuration entries must not be null");
            }
            copy[i] = configuration[i];
        }
        return copy;
    }

    private static String[] copyAndValidateManifest(String[] manifest) {
        if (manifest == null) {
            throw new IllegalStateException("Installer returned a null manifest");
        }
        String[] copy = new String[manifest.length];
        for (int i = 0; i < manifest.length; i++) {
            if (manifest[i] == null) {
                throw new IllegalStateException(
                        "Installer returned a manifest with a null entry");
            }
            copy[i] = manifest[i];
        }
        return copy;
    }

    private static String[] copyArray(String[] array) {
        if (array == null) {
            return null;
        }
        String[] copy = new String[array.length];
        for (int i = 0; i < array.length; i++) {
            copy[i] = array[i];
        }
        return copy;
    }

    private static IllegalStateException superseded(long generation) {
        return new IllegalStateException(
                "Installation generation " + generation + " was superseded");
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
