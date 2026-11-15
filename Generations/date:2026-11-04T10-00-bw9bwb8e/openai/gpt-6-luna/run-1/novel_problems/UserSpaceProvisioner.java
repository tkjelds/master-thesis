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
        public final int pending;
        public final int installing;
        public final int active;
        public final int failed;
        public final int totalUsers;

        private Metrics(int pending, int installing, int active, int failed,
                        int totalUsers) {
            this.pending = pending;
            this.installing = installing;
            this.active = active;
            this.failed = failed;
            this.totalUsers = totalUsers;
        }
    }

    private static final int ATTEMPT_PENDING = 0;
    private static final int ATTEMPT_SUCCEEDED = 1;
    private static final int ATTEMPT_FAILED = 2;
    private static final int ATTEMPT_SUPERSEDED = 3;

    private static final class Attempt {
        final long generation;
        final Thread owner;
        int outcome;
        String[] manifest;
        Throwable failure;

        Attempt(long generation, Thread owner) {
            this.generation = generation;
            this.owner = owner;
            this.outcome = ATTEMPT_PENDING;
        }
    }

    private static final class UserRecord {
        final String name;
        State state;
        long generation;
        String[] configuration;
        String[] manifest;
        Throwable failure;
        Attempt currentAttempt;

        UserRecord(String name, String[] configuration) {
            this.name = name;
            this.configuration = configuration;
            this.generation = 1L;
            this.state = State.PENDING;
        }
    }

    private final Object lock = new Object();
    private final Installer installer;
    private UserRecord[] users = new UserRecord[8];
    private int userCount;

    public UserSpaceProvisioner(Installer installer) {
        if (installer == null) {
            throw new IllegalArgumentException("installer must not be null");
        }
        this.installer = installer;
    }

    public void register(String user, String[] configuration) {
        validateUser(user);
        String[] copiedConfiguration = copyConfiguration(configuration);

        synchronized (lock) {
            if (findUser(user) != null) {
                throw new IllegalArgumentException("duplicate user: " + user);
            }
            if (userCount == users.length) {
                UserRecord[] expanded = new UserRecord[users.length * 2];
                for (int i = 0; i < users.length; i++) {
                    expanded[i] = users[i];
                }
                users = expanded;
            }
            users[userCount++] = new UserRecord(user, copiedConfiguration);
        }
    }

    public String[] activate(String user) throws InterruptedException {
        UserRecord record;
        Attempt attempt;
        boolean startInstallation = false;

        synchronized (lock) {
            record = requireUser(user);

            if (record.state == State.ACTIVE) {
                return copyArray(record.manifest);
            }
            if (record.state == State.FAILED) {
                throwFailure(record.failure);
            }
            if (record.state == State.PENDING) {
                record.state = State.INSTALLING;
                record.failure = null;
                attempt = new Attempt(record.generation, Thread.currentThread());
                record.currentAttempt = attempt;
                startInstallation = true;
            } else {
                attempt = record.currentAttempt;
                if (attempt != null && attempt.owner == Thread.currentThread()) {
                    throw new IllegalStateException(
                            "an installer callback cannot activate its own user");
                }
                while (attempt.outcome == ATTEMPT_PENDING) {
                    lock.wait();
                }
            }
        }

        if (startInstallation) {
            runInstallation(record, attempt);
        }
        return resultOf(attempt);
    }

    public String[] reinstall(String user, String[] configuration) {
        validateUser(user);
        String[] copiedConfiguration = copyConfiguration(configuration);
        UserRecord record;
        Attempt attempt;

        synchronized (lock) {
            record = requireUser(user);

            if (record.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("user generation overflow");
            }
            if (record.state == State.INSTALLING
                    && record.currentAttempt != null
                    && record.currentAttempt.owner == Thread.currentThread()) {
                throw new IllegalStateException(
                        "an installer callback cannot reinstall its own user");
            }

            if (record.state == State.INSTALLING
                    && record.currentAttempt != null
                    && record.currentAttempt.outcome == ATTEMPT_PENDING) {
                record.currentAttempt.outcome = ATTEMPT_SUPERSEDED;
                lock.notifyAll();
            }

            record.generation++;
            record.configuration = copiedConfiguration;
            record.manifest = null;
            record.failure = null;
            record.state = State.INSTALLING;
            attempt = new Attempt(record.generation, Thread.currentThread());
            record.currentAttempt = attempt;
        }

        runInstallation(record, attempt);
        return resultOf(attempt);
    }

    public UserSnapshot verify(String user) {
        synchronized (lock) {
            UserRecord record = requireUser(user);
            return new UserSnapshot(record.state, record.generation,
                    record.state == State.ACTIVE ? copyArray(record.manifest) : null);
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
                }
            }
            return new Metrics(pending, installing, active, failed, userCount);
        }
    }

    private void runInstallation(UserRecord record, Attempt attempt) {
        String[] installedManifest = null;
        Throwable installationFailure = null;

        try {
            String[] callbackResult = installer.install(
                    record.name, copyArray(record.configuration));
            installedManifest = copyManifest(callbackResult);
        } catch (RuntimeException exception) {
            installationFailure = exception;
        } catch (Error error) {
            installationFailure = error;
        }

        synchronized (lock) {
            if (attempt.outcome == ATTEMPT_PENDING) {
                if (record.currentAttempt == attempt
                        && record.generation == attempt.generation
                        && record.state == State.INSTALLING) {
                    if (installationFailure == null) {
                        attempt.manifest = installedManifest;
                        attempt.outcome = ATTEMPT_SUCCEEDED;
                        record.manifest = copyArray(installedManifest);
                        record.failure = null;
                        record.state = State.ACTIVE;
                    } else {
                        attempt.failure = installationFailure;
                        attempt.outcome = ATTEMPT_FAILED;
                        record.manifest = null;
                        record.failure = installationFailure;
                        record.state = State.FAILED;
                    }
                } else {
                    attempt.outcome = ATTEMPT_SUPERSEDED;
                }
            }
            lock.notifyAll();
        }
    }

    private String[] resultOf(Attempt attempt) {
        synchronized (lock) {
            if (attempt.outcome == ATTEMPT_SUPERSEDED) {
                throw new IllegalStateException("installation attempt was superseded");
            }
            if (attempt.outcome == ATTEMPT_FAILED) {
                throwFailure(attempt.failure);
            }
            if (attempt.outcome != ATTEMPT_SUCCEEDED) {
                throw new IllegalStateException("installation attempt is incomplete");
            }
            return copyArray(attempt.manifest);
        }
    }

    private UserRecord requireUser(String user) {
        if (user == null || user.length() == 0) {
            throw new IllegalArgumentException("user must not be null or empty");
        }
        UserRecord record = findUser(user);
        if (record == null) {
            throw new IllegalArgumentException("unknown user: " + user);
        }
        return record;
    }

    private UserRecord findUser(String user) {
        for (int i = 0; i < userCount; i++) {
            if (users[i].name.equals(user)) {
                return users[i];
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
        String[] copy = new String[configuration.length];
        for (int i = 0; i < configuration.length; i++) {
            if (configuration[i] == null) {
                throw new IllegalArgumentException(
                        "configuration entries must not be null");
            }
            copy[i] = configuration[i];
        }
        return copy;
    }

    private static String[] copyManifest(String[] manifest) {
        if (manifest == null) {
            throw new IllegalStateException("installer returned a null manifest");
        }
        String[] copy = new String[manifest.length];
        for (int i = 0; i < manifest.length; i++) {
            if (manifest[i] == null) {
                throw new IllegalStateException(
                        "installer returned a manifest containing null");
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
