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
        private final State status;
        private final long generation;
        private final String[] manifest;

        private UserSnapshot(State status, long generation, String[] manifest) {
            this.status = status;
            this.generation = generation;
            this.manifest = copyArray(manifest);
        }

        public State getStatus() {
            return status;
        }

        public long getGeneration() {
            return generation;
        }

        public String[] getManifest() {
            return copyArray(manifest);
        }
    }

    public static final class MetricsSnapshot {
        private final int pending;
        private final int installing;
        private final int active;
        private final int failed;
        private final int totalUsers;

        private MetricsSnapshot(int pending, int installing, int active,
                                int failed, int totalUsers) {
            this.pending = pending;
            this.installing = installing;
            this.active = active;
            this.failed = failed;
            this.totalUsers = totalUsers;
        }

        public int getPendingCount() {
            return pending;
        }

        public int getInstallingCount() {
            return installing;
        }

        public int getActiveCount() {
            return active;
        }

        public int getFailedCount() {
            return failed;
        }

        public int getTotalUsers() {
            return totalUsers;
        }
    }

    private static final class Attempt {
        private final long generation;
        private boolean done;
        private boolean superseded;
        private String[] manifest;
        private RuntimeException failure;

        private Attempt(long generation) {
            this.generation = generation;
        }
    }

    private static final class UserRecord {
        private final String user;
        private UserRecord next;
        private String[] configuration;
        private long generation;
        private State state;
        private String[] manifest;
        private RuntimeException failure;
        private Attempt currentAttempt;

        private UserRecord(String user, String[] configuration) {
            this.user = user;
            this.configuration = configuration;
            this.generation = 0L;
            this.state = State.PENDING;
        }
    }

    private final Object monitor = new Object();
    private final Installer installer;
    private UserRecord users;

    public UserSpaceProvisioner(Installer installer) {
        if (installer == null) {
            throw new IllegalArgumentException("installer must not be null");
        }
        this.installer = installer;
    }

    public void register(String user, String[] configuration) {
        validateUser(user);
        String[] copiedConfiguration = copyAndValidateConfiguration(configuration);

        synchronized (monitor) {
            if (findUser(user) != null) {
                throw new IllegalArgumentException("User is already registered: " + user);
            }

            UserRecord record = new UserRecord(user, copiedConfiguration);
            record.next = users;
            users = record;
        }
    }

    public String[] activate(String user) throws InterruptedException {
        validateUser(user);

        UserRecord record;
        Attempt attempt;
        String[] configuration = null;
        boolean startInstallation = false;

        synchronized (monitor) {
            record = requireUser(user);

            if (record.state == State.ACTIVE) {
                return copyArray(record.manifest);
            }
            if (record.state == State.FAILED) {
                throw record.failure;
            }

            if (record.state == State.PENDING) {
                attempt = new Attempt(record.generation);
                record.currentAttempt = attempt;
                record.state = State.INSTALLING;
                configuration = copyArray(record.configuration);
                startInstallation = true;
            } else {
                attempt = record.currentAttempt;
            }
        }

        if (startInstallation) {
            runInstallation(record, attempt, configuration);
        } else {
            synchronized (monitor) {
                while (!attempt.done) {
                    monitor.wait();
                }
            }
        }

        synchronized (monitor) {
            return resultForAttempt(attempt);
        }
    }

    public String[] reinstall(String user, String[] configuration) {
        validateUser(user);
        String[] copiedConfiguration = copyAndValidateConfiguration(configuration);

        UserRecord record;
        Attempt attempt;

        synchronized (monitor) {
            record = requireUser(user);

            if (record.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("User generation overflow");
            }

            if (record.state == State.INSTALLING
                    && record.currentAttempt != null
                    && !record.currentAttempt.done) {
                record.currentAttempt.superseded = true;
                record.currentAttempt.done = true;
                monitor.notifyAll();
            }

            record.generation++;
            record.configuration = copiedConfiguration;
            record.manifest = null;
            record.failure = null;
            record.state = State.INSTALLING;

            attempt = new Attempt(record.generation);
            record.currentAttempt = attempt;
        }

        runInstallation(record, attempt, copyArray(copiedConfiguration));

        synchronized (monitor) {
            return resultForAttempt(attempt);
        }
    }

    public UserSnapshot verify(String user) {
        validateUser(user);

        synchronized (monitor) {
            UserRecord record = requireUser(user);
            return new UserSnapshot(record.state, record.generation, record.manifest);
        }
    }

    public MetricsSnapshot snapshotMetrics() {
        synchronized (monitor) {
            int pending = 0;
            int installing = 0;
            int active = 0;
            int failed = 0;
            int total = 0;

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
                }
            }

            return new MetricsSnapshot(pending, installing, active, failed, total);
        }
    }

    private void runInstallation(UserRecord record, Attempt attempt,
                                 String[] configuration) {
        String[] installedManifest = null;
        RuntimeException installationFailure = null;

        try {
            String[] result = installer.install(record.user, copyArray(configuration));
            if (result == null) {
                throw new IllegalStateException("Installer returned a null manifest");
            }
            installedManifest = copyArray(result);
        } catch (RuntimeException exception) {
            installationFailure = exception;
        }

        synchronized (monitor) {
            if (record.currentAttempt == attempt
                    && record.generation == attempt.generation
                    && record.state == State.INSTALLING
                    && !attempt.done) {
                if (installationFailure != null) {
                    record.state = State.FAILED;
                    record.manifest = null;
                    record.failure = installationFailure;
                    attempt.failure = installationFailure;
                } else {
                    record.state = State.ACTIVE;
                    record.manifest = installedManifest;
                    record.failure = null;
                    attempt.manifest = installedManifest;
                }

                attempt.done = true;
                monitor.notifyAll();
            }
        }
    }

    private String[] resultForAttempt(Attempt attempt) {
        if (attempt.superseded) {
            throw new IllegalStateException("Installation was superseded");
        }
        if (attempt.failure != null) {
            throw attempt.failure;
        }
        return copyArray(attempt.manifest);
    }

    private UserRecord requireUser(String user) {
        UserRecord record = findUser(user);
        if (record == null) {
            throw new IllegalArgumentException("Unknown user: " + user);
        }
        return record;
    }

    private UserRecord findUser(String user) {
        for (UserRecord record = users; record != null; record = record.next) {
            if (record.user.equals(user)) {
                return record;
            }
        }
        return null;
    }

    private static void validateUser(String user) {
        if (user == null || user.length() == 0) {
            throw new IllegalArgumentException("User must not be null or empty");
        }
    }

    private static String[] copyAndValidateConfiguration(String[] configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("Configuration must not be null");
        }

        String[] copy = copyArray(configuration);
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == null) {
                throw new IllegalArgumentException("Configuration entries must not be null");
            }
        }
        return copy;
    }

    private static String[] copyArray(String[] array) {
        return array == null ? null : array.clone();
    }
}
