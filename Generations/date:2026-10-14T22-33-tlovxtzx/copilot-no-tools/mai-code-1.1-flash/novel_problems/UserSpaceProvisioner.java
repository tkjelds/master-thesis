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

        public VerificationSnapshot(State state, long generation, String[] manifest) {
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

        public Metrics(int pendingCount, int installingCount, int activeCount, int failedCount, int totalUsers) {
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

    private static final class ReentryGuard {
        private final Thread thread;
        private final String user;
        private ReentryGuard next;

        ReentryGuard(Thread thread, String user, ReentryGuard next) {
            this.thread = thread;
            this.user = user;
            this.next = next;
        }
    }

    private static final class Attempt {
        private final long generation;
        private boolean finished;
        private boolean superseded;
        private RuntimeException failure;
        private String[] result;

        Attempt(long generation) {
            this.generation = generation;
        }

        synchronized void setSuccess(String[] manifest) {
            this.result = copyStrings(manifest);
            this.finished = true;
            this.notifyAll();
        }

        synchronized void setFailure(RuntimeException failure) {
            this.failure = failure;
            this.finished = true;
            this.notifyAll();
        }

        synchronized void setSuperseded() {
            this.superseded = true;
            this.failure = new IllegalStateException("installation superseded");
            this.finished = true;
            this.notifyAll();
        }
    }

    private static final class UserRecord {
        private final String user;
        private String[] configuration;
        private State state;
        private long generation;
        private String[] manifest;
        private RuntimeException failure;
        private Attempt currentAttempt;
        private UserRecord next;

        UserRecord(String user, String[] configuration) {
            this.user = user;
            this.configuration = copyStrings(configuration);
            this.state = State.PENDING;
            this.generation = 0L;
            this.manifest = null;
            this.failure = null;
            this.currentAttempt = null;
            this.next = null;
        }
    }

    private final Installer installer;
    private final Object monitor;
    private UserRecord head;
    private ReentryGuard reentryHead;

    public UserSpaceProvisioner(Installer installer) {
        if (installer == null) {
            throw new IllegalArgumentException("installer is required");
        }
        this.installer = installer;
        this.monitor = new Object();
        this.head = null;
        this.reentryHead = null;
    }

    public void register(String user, String[] configuration) {
        validateUserName(user);
        validateConfiguration(configuration);
        synchronized (monitor) {
            if (findUserLocked(user) != null) {
                throw new IllegalArgumentException("duplicate user: " + user);
            }
            UserRecord record = new UserRecord(user, configuration);
            record.next = head;
            head = record;
        }
    }

    public String[] activate(String user) {
        validateUserName(user);
        ensureNotRecursive(user);
        UserRecord record;
        Attempt attempt;
        String[] configCopy;

        synchronized (monitor) {
            record = findUserLocked(user);
            if (record == null) {
                throw new IllegalArgumentException("unknown user: " + user);
            }

            if (record.state == State.ACTIVE) {
                return copyStrings(record.manifest);
            }
            if (record.state == State.FAILED) {
                if (record.failure == null) {
                    throw new IllegalStateException("failed user without failure detail");
                }
                throw record.failure;
            }
            if (record.state == State.INSTALLING) {
                Attempt current = record.currentAttempt;
                if (current == null) {
                    throw new IllegalStateException("installing user without active attempt");
                }
                return awaitAttempt(current);
            }

            long newGeneration = nextGenerationLocked(record);
            record.state = State.INSTALLING;
            record.manifest = null;
            record.failure = null;
            record.currentAttempt = new Attempt(newGeneration);
            attempt = record.currentAttempt;
            configCopy = copyStrings(record.configuration);
        }

        return completeInstall(user, record, attempt, configCopy);
    }

    public void reinstall(String user, String[] configuration) {
        validateUserName(user);
        validateConfiguration(configuration);
        ensureNotRecursive(user);

        UserRecord record;
        Attempt newAttempt;
        String[] configCopy;

        synchronized (monitor) {
            record = findUserLocked(user);
            if (record == null) {
                throw new IllegalArgumentException("unknown user: " + user);
            }

            if (record.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("generation overflow");
            }

            Attempt oldAttempt = record.currentAttempt;
            if (oldAttempt != null) {
                synchronized (oldAttempt) {
                    if (!oldAttempt.finished) {
                        oldAttempt.setSuperseded();
                    }
                }
            }

            long newGeneration = record.generation + 1L;
            record.generation = newGeneration;
            record.configuration = copyStrings(configuration);
            record.state = State.INSTALLING;
            record.manifest = null;
            record.failure = null;
            newAttempt = new Attempt(newGeneration);
            record.currentAttempt = newAttempt;
            configCopy = copyStrings(record.configuration);
        }

        completeInstall(user, record, newAttempt, configCopy);
    }

    public VerificationSnapshot verify(String user) {
        validateUserName(user);
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

            UserRecord cursor = head;
            while (cursor != null) {
                total++;
                if (cursor.state == State.PENDING) {
                    pending++;
                } else if (cursor.state == State.INSTALLING) {
                    installing++;
                } else if (cursor.state == State.ACTIVE) {
                    active++;
                } else if (cursor.state == State.FAILED) {
                    failed++;
                }
                cursor = cursor.next;
            }

            return new Metrics(pending, installing, active, failed, total);
        }
    }

    private String[] completeInstall(String user, UserRecord record, Attempt attempt, String[] config) {
        beginInstallCallback(user);
        String[] manifest = null;
        RuntimeException failure = null;
        try {
            manifest = installer.install(user, copyStrings(config));
            if (manifest == null) {
                throw new IllegalStateException("invalid manifest");
            }
            for (int i = 0; i < manifest.length; i++) {
                if (manifest[i] == null) {
                    throw new IllegalStateException("invalid manifest");
                }
            }
        } catch (RuntimeException ex) {
            failure = ex;
        } finally {
            endInstallCallback(user);
        }

        synchronized (monitor) {
            if (record.currentAttempt != attempt) {
                synchronized (attempt) {
                    attempt.setSuperseded();
                }
                throw new IllegalStateException("superseded attempt");
            }

            if (failure != null) {
                record.state = State.FAILED;
                record.manifest = null;
                record.failure = failure;
                synchronized (attempt) {
                    attempt.setFailure(failure);
                }
                return null;
            }

            record.state = State.ACTIVE;
            record.manifest = copyStrings(manifest);
            record.failure = null;
            synchronized (attempt) {
                attempt.setSuccess(record.manifest);
            }
            return copyStrings(record.manifest);
        }
    }

    private String[] awaitAttempt(Attempt attempt) {
        while (true) {
            synchronized (attempt) {
                while (!attempt.finished) {
                    try {
                        attempt.wait();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted while waiting for install", ex);
                    }
                }
                if (attempt.superseded) {
                    throw new IllegalStateException("installation superseded");
                }
                if (attempt.failure != null) {
                    throw attempt.failure;
                }
                if (attempt.result == null) {
                    throw new IllegalStateException("install result missing");
                }
                return copyStrings(attempt.result);
            }
        }
    }

    private long nextGenerationLocked(UserRecord record) {
        if (record.generation >= Long.MAX_VALUE) {
            throw new IllegalStateException("generation overflow");
        }
        long next = record.generation + 1L;
        record.generation = next;
        return next;
    }

    private void ensureNotRecursive(String user) {
        synchronized (monitor) {
            Thread current = Thread.currentThread();
            ReentryGuard cursor = reentryHead;
            while (cursor != null) {
                if (cursor.thread == current && cursor.user.equals(user)) {
                    throw new IllegalStateException("recursive installation for user: " + user);
                }
                cursor = cursor.next;
            }
        }
    }

    private void beginInstallCallback(String user) {
        synchronized (monitor) {
            Thread current = Thread.currentThread();
            ReentryGuard cursor = reentryHead;
            while (cursor != null) {
                if (cursor.thread == current && cursor.user.equals(user)) {
                    throw new IllegalStateException("recursive installation for user: " + user);
                }
                cursor = cursor.next;
            }
            reentryHead = new ReentryGuard(current, user, reentryHead);
        }
    }

    private void endInstallCallback(String user) {
        synchronized (monitor) {
            Thread current = Thread.currentThread();
            ReentryGuard previous = null;
            ReentryGuard cursor = reentryHead;
            while (cursor != null) {
                if (cursor.thread == current && cursor.user.equals(user)) {
                    if (previous == null) {
                        reentryHead = cursor.next;
                    } else {
                        previous.next = cursor.next;
                    }
                    return;
                }
                previous = cursor;
                cursor = cursor.next;
            }
        }
    }

    private UserRecord findUserLocked(String user) {
        UserRecord cursor = head;
        while (cursor != null) {
            if (cursor.user.equals(user)) {
                return cursor;
            }
            cursor = cursor.next;
        }
        return null;
    }

    private static void validateUserName(String user) {
        if (user == null || user.length() == 0 || user.trim().length() == 0) {
            throw new IllegalArgumentException("user name is required");
        }
    }

    private static void validateConfiguration(String[] configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration is required");
        }
        for (int i = 0; i < configuration.length; i++) {
            if (configuration[i] == null) {
                throw new IllegalArgumentException("configuration entries must be non-null");
            }
        }
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
}
