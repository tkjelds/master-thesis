@UserSpaceProvisioner.ThreadSafe
public class UserSpaceProvisioner {
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

    public static final class VerificationSnapshot {
        private final State state;
        private final long generation;
        private final String[] manifest;

        private VerificationSnapshot(State state, long generation, String[] manifest) {
            this.state = state;
            this.generation = generation;
            this.manifest = copyStrings(manifest);
        }

        public State getState() {
            return state;
        }

        public long getGeneration() {
            return generation;
        }

        public String[] getManifest() {
            return copyStrings(manifest);
        }
    }

    public static final class Metrics {
        private final int pendingCount;
        private final int installingCount;
        private final int activeCount;
        private final int failedCount;
        private final int totalUsers;

        private Metrics(int pendingCount, int installingCount, int activeCount,
                        int failedCount, int totalUsers) {
            this.pendingCount = pendingCount;
            this.installingCount = installingCount;
            this.activeCount = activeCount;
            this.failedCount = failedCount;
            this.totalUsers = totalUsers;
        }

        public int getPendingCount() {
            return pendingCount;
        }

        public int getInstallingCount() {
            return installingCount;
        }

        public int getActiveCount() {
            return activeCount;
        }

        public int getFailedCount() {
            return failedCount;
        }

        public int getTotalUsers() {
            return totalUsers;
        }
    }

    private static final class Attempt {
        private final long generation;
        private boolean finished;
        private boolean superseded;
        private Throwable failure;
        private String[] manifest;

        private Attempt(long generation) {
            this.generation = generation;
        }

        private void succeed(String[] value) {
            synchronized (this) {
                if (!finished) {
                    manifest = copyStrings(value);
                    finished = true;
                    notifyAll();
                }
            }
        }

        private void fail(Throwable cause) {
            synchronized (this) {
                if (!finished) {
                    failure = cause;
                    finished = true;
                    notifyAll();
                }
            }
        }

        private void supersede() {
            synchronized (this) {
                if (!finished) {
                    superseded = true;
                    finished = true;
                    notifyAll();
                }
            }
        }
    }

    private static final class UserRecord {
        private final String user;
        private String[] configuration;
        private State state;
        private long generation;
        private String[] manifest;
        private Throwable failure;
        private Attempt currentAttempt;
        private UserRecord next;

        private UserRecord(String user, String[] configuration) {
            this.user = user;
            this.configuration = copyStrings(configuration);
            state = State.PENDING;
        }
    }

    private static final class CallbackGuard {
        private final Thread thread;
        private final String user;
        private CallbackGuard next;

        private CallbackGuard(Thread thread, String user, CallbackGuard next) {
            this.thread = thread;
            this.user = user;
            this.next = next;
        }
    }

    private final Installer installer;
    private final Object monitor = new Object();
    private UserRecord users;
    private CallbackGuard callbacks;

    public UserSpaceProvisioner(Installer installer) {
        if (installer == null) {
            throw new IllegalArgumentException("installer is required");
        }
        this.installer = installer;
    }

    public void register(String user, String[] configuration) {
        validateUser(user);
        String[] copiedConfiguration = validatedCopy(configuration);

        synchronized (monitor) {
            if (findUserLocked(user) != null) {
                throw new IllegalArgumentException("duplicate user: " + user);
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
        boolean runInstaller = false;

        synchronized (monitor) {
            rejectRecursiveCallbackLocked(user);
            record = findUserLocked(user);
            if (record == null) {
                throw new IllegalArgumentException("unknown user: " + user);
            }

            if (record.state == State.ACTIVE) {
                return copyStrings(record.manifest);
            }
            if (record.state == State.FAILED) {
                throwFailure(record.failure);
            }
            if (record.state == State.INSTALLING) {
                attempt = record.currentAttempt;
                if (attempt == null) {
                    throw new IllegalStateException("installing user has no attempt");
                }
            } else {
                long generation = nextGeneration(record.generation);
                record.generation = generation;
                record.state = State.INSTALLING;
                record.manifest = null;
                record.failure = null;
                attempt = new Attempt(generation);
                record.currentAttempt = attempt;
                configuration = copyStrings(record.configuration);
                runInstaller = true;
            }
        }

        if (runInstaller) {
            return executeInstall(user, record, attempt, configuration);
        }
        return awaitAttempt(attempt);
    }

    public void reinstall(String user, String[] configuration) {
        validateUser(user);
        String[] copiedConfiguration = validatedCopy(configuration);
        UserRecord record;
        Attempt attempt;
        String[] installConfiguration;

        synchronized (monitor) {
            rejectRecursiveCallbackLocked(user);
            record = findUserLocked(user);
            if (record == null) {
                throw new IllegalArgumentException("unknown user: " + user);
            }

            long generation = nextGeneration(record.generation);
            if (record.state == State.INSTALLING && record.currentAttempt != null) {
                record.currentAttempt.supersede();
            }

            record.generation = generation;
            record.configuration = copiedConfiguration;
            record.manifest = null;
            record.failure = null;
            record.state = State.INSTALLING;
            attempt = new Attempt(generation);
            record.currentAttempt = attempt;
            installConfiguration = copyStrings(record.configuration);
        }

        executeInstall(user, record, attempt, installConfiguration);
    }

    public VerificationSnapshot verify(String user) {
        validateUser(user);
        synchronized (monitor) {
            UserRecord record = findUserLocked(user);
            if (record == null) {
                throw new IllegalArgumentException("unknown user: " + user);
            }
            return new VerificationSnapshot(record.state, record.generation, record.manifest);
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (monitor) {
            int pending = 0;
            int installing = 0;
            int active = 0;
            int failed = 0;
            int total = 0;
            UserRecord record = users;

            while (record != null) {
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
                        throw new IllegalStateException("unknown user state");
                }
                record = record.next;
            }
            return new Metrics(pending, installing, active, failed, total);
        }
    }

    private String[] executeInstall(String user, UserRecord record, Attempt attempt,
                                   String[] configuration) {
        String[] installedManifest = null;
        Throwable installFailure = null;

        beginCallback(user);
        try {
            String[] result = installer.install(user, copyStrings(configuration));
            if (!isValidManifest(result)) {
                throw new IllegalStateException("installer returned an invalid manifest");
            }
            installedManifest = copyStrings(result);
        } catch (RuntimeException exception) {
            installFailure = exception;
        } catch (Error error) {
            installFailure = error;
        } finally {
            endCallback(user);
        }

        synchronized (monitor) {
            if (record.currentAttempt != attempt || record.generation != attempt.generation) {
                attempt.supersede();
                throw new IllegalStateException("installation attempt was superseded");
            }

            if (installFailure != null) {
                record.state = State.FAILED;
                record.manifest = null;
                record.failure = installFailure;
                attempt.fail(installFailure);
                throwFailure(installFailure);
            }

            record.manifest = copyStrings(installedManifest);
            record.failure = null;
            record.state = State.ACTIVE;
            attempt.succeed(record.manifest);
            return copyStrings(record.manifest);
        }
    }

    private String[] awaitAttempt(Attempt attempt) throws InterruptedException {
        synchronized (attempt) {
            while (!attempt.finished) {
                attempt.wait();
            }
            if (attempt.superseded) {
                throw new IllegalStateException("installation attempt was superseded");
            }
            if (attempt.failure != null) {
                throwFailure(attempt.failure);
            }
            if (attempt.manifest == null) {
                throw new IllegalStateException("completed installation has no manifest");
            }
            return copyStrings(attempt.manifest);
        }
    }

    private void beginCallback(String user) {
        synchronized (monitor) {
            rejectRecursiveCallbackLocked(user);
            callbacks = new CallbackGuard(Thread.currentThread(), user, callbacks);
        }
    }

    private void endCallback(String user) {
        synchronized (monitor) {
            Thread current = Thread.currentThread();
            CallbackGuard previous = null;
            CallbackGuard guard = callbacks;
            while (guard != null) {
                if (guard.thread == current && guard.user.equals(user)) {
                    if (previous == null) {
                        callbacks = guard.next;
                    } else {
                        previous.next = guard.next;
                    }
                    return;
                }
                previous = guard;
                guard = guard.next;
            }
        }
    }

    private void rejectRecursiveCallbackLocked(String user) {
        Thread current = Thread.currentThread();
        CallbackGuard guard = callbacks;
        while (guard != null) {
            if (guard.thread == current && guard.user.equals(user)) {
                throw new IllegalStateException(
                        "installer callbacks cannot activate or reinstall their own user");
            }
            guard = guard.next;
        }
    }

    private UserRecord findUserLocked(String user) {
        UserRecord record = users;
        while (record != null) {
            if (record.user.equals(user)) {
                return record;
            }
            record = record.next;
        }
        return null;
    }

    private static long nextGeneration(long generation) {
        if (generation == Long.MAX_VALUE) {
            throw new IllegalStateException("generation overflow");
        }
        return generation + 1L;
    }

    private static void validateUser(String user) {
        if (user == null || user.length() == 0) {
            throw new IllegalArgumentException("user name must not be null or empty");
        }
    }

    private static String[] validatedCopy(String[] values) {
        if (values == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        String[] copy = copyStrings(values);
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == null) {
                throw new IllegalArgumentException("configuration entries must not be null");
            }
        }
        return copy;
    }

    private static boolean isValidManifest(String[] manifest) {
        if (manifest == null) {
            return false;
        }
        for (int i = 0; i < manifest.length; i++) {
            if (manifest[i] == null) {
                return false;
            }
        }
        return true;
    }

    private static String[] copyStrings(String[] source) {
        if (source == null) {
            return null;
        }
        String[] copy = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static void throwFailure(Throwable failure) {
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new IllegalStateException("installation failed", failure);
    }
}
