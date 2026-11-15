@UserSpaceProvisioner.ThreadSafe
public class UserSpaceProvisioner {
    @interface ThreadSafe {}

    public interface Installer {
        String[] install(String user, String[] configuration);
    }

    public enum UserState {
        PENDING,
        INSTALLING,
        ACTIVE,
        FAILED
    }

    public static final class Verification {
        public final UserState status;
        public final long generation;
        public final String[] manifest;

        private Verification(UserState status, long generation, String[] manifest) {
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

        private Metrics(int pending, int installing, int active, int failed, int totalUsers) {
            this.pending = pending;
            this.installing = installing;
            this.active = active;
            this.failed = failed;
            this.totalUsers = totalUsers;
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

    private static final class CallbackFrame {
        final Thread thread;
        final CallbackFrame next;

        CallbackFrame(Thread thread, CallbackFrame next) {
            this.thread = thread;
            this.next = next;
        }
    }

    private static final class UserRecord {
        final String name;
        UserState state = UserState.PENDING;
        long generation;
        String[] configuration;
        String[] manifest;
        Throwable failure;
        Attempt currentAttempt;
        CallbackFrame callbacks;

        UserRecord(String name, String[] configuration) {
            this.name = name;
            this.configuration = configuration;
        }
    }

    private final Object lock = new Object();
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
        String[] copiedConfiguration = copyConfiguration(configuration);

        synchronized (lock) {
            if (findUser(user) != null) {
                throw new IllegalArgumentException("User is already registered: " + user);
            }
            UserRecord record = new UserRecord(user, copiedConfiguration);
            record.next = null;
            if (users == null) {
                users = record;
            } else {
                UserRecord tail = users;
                while (tail.next != null) {
                    tail = tail.next;
                }
                tail.next = record;
            }
        }
    }

    public String[] activate(String user) throws InterruptedException {
        validateUserName(user);
        UserRecord record;
        Attempt attempt;
        boolean owner = false;

        synchronized (lock) {
            record = requireUser(user);
            rejectCallbackReentry(record);

            if (record.state == UserState.ACTIVE) {
                return copyStrings(record.manifest);
            }
            if (record.state == UserState.FAILED) {
                throwFailure(record.failure);
            }
            if (record.state == UserState.INSTALLING) {
                attempt = record.currentAttempt;
                while (!attempt.done) {
                    lock.wait();
                }
                return resultOrThrow(attempt);
            }

            attempt = new Attempt(record.generation, record.configuration);
            record.currentAttempt = attempt;
            record.state = UserState.INSTALLING;
            record.manifest = null;
            record.failure = null;
            addCallbackFrame(record, Thread.currentThread());
            owner = true;
        }

        if (owner) {
            performInstall(record, attempt);
        }

        synchronized (lock) {
            return resultOrThrow(attempt);
        }
    }

    public String[] reinstall(String user, String[] configuration) {
        validateUserName(user);
        String[] copiedConfiguration = copyConfiguration(configuration);
        UserRecord record;
        Attempt attempt;

        synchronized (lock) {
            record = requireUser(user);
            rejectCallbackReentry(record);

            if (record.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("User generation overflow: " + user);
            }

            if (record.state == UserState.INSTALLING) {
                Attempt oldAttempt = record.currentAttempt;
                oldAttempt.superseded = true;
                oldAttempt.done = true;
                lock.notifyAll();
            }

            record.generation++;
            record.configuration = copiedConfiguration;
            record.manifest = null;
            record.failure = null;
            record.state = UserState.INSTALLING;

            attempt = new Attempt(record.generation, record.configuration);
            record.currentAttempt = attempt;
            addCallbackFrame(record, Thread.currentThread());
        }

        performInstall(record, attempt);

        synchronized (lock) {
            return resultOrThrow(attempt);
        }
    }

    public Verification verify(String user) {
        validateUserName(user);
        synchronized (lock) {
            UserRecord record = requireUser(user);
            String[] manifest = record.state == UserState.ACTIVE
                    ? copyStrings(record.manifest)
                    : null;
            return new Verification(record.state, record.generation, manifest);
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (lock) {
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

            return new Metrics(pending, installing, active, failed, total);
        }
    }

    private void performInstall(UserRecord record, Attempt attempt) {
        String[] installedManifest = null;
        Throwable installFailure = null;

        try {
            installedManifest = copyManifest(
                    installer.install(record.name, copyStrings(attempt.configuration)));
        } catch (RuntimeException | Error failure) {
            installFailure = failure;
        }

        synchronized (lock) {
            if (!attempt.done) {
                if (record.currentAttempt == attempt
                        && record.generation == attempt.generation
                        && record.state == UserState.INSTALLING) {
                    if (installFailure == null) {
                        record.manifest = installedManifest;
                        record.failure = null;
                        record.state = UserState.ACTIVE;
                        attempt.manifest = installedManifest;
                    } else {
                        record.manifest = null;
                        record.failure = installFailure;
                        record.state = UserState.FAILED;
                        attempt.failure = installFailure;
                    }
                    attempt.done = true;
                    lock.notifyAll();
                } else {
                    attempt.superseded = true;
                    attempt.done = true;
                    lock.notifyAll();
                }
            }
            removeCallbackFrame(record, Thread.currentThread());
        }
    }

    private UserRecord findUser(String name) {
        for (UserRecord record = users; record != null; record = record.next) {
            if (record.name.equals(name)) {
                return record;
            }
        }
        return null;
    }

    private UserRecord requireUser(String name) {
        UserRecord record = findUser(name);
        if (record == null) {
            throw new IllegalArgumentException("Unknown user: " + name);
        }
        return record;
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
        String[] copy = new String[configuration.length];
        for (int i = 0; i < configuration.length; i++) {
            if (configuration[i] == null) {
                throw new IllegalArgumentException("Configuration entries must not be null");
            }
            copy[i] = configuration[i];
        }
        return copy;
    }

    private static String[] copyManifest(String[] manifest) {
        if (manifest == null) {
            throw new IllegalStateException("Installer returned a null manifest");
        }
        String[] copy = new String[manifest.length];
        for (int i = 0; i < manifest.length; i++) {
            if (manifest[i] == null) {
                throw new IllegalStateException("Installer returned a manifest with a null entry");
            }
            copy[i] = manifest[i];
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

    private static void throwFailure(Throwable failure) {
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new IllegalStateException("Installation failed", failure);
    }

    private static String[] resultOrThrow(Attempt attempt) {
        if (attempt.superseded) {
            throw new IllegalStateException("Installation attempt was superseded");
        }
        if (attempt.failure != null) {
            throwFailure(attempt.failure);
        }
        return copyStrings(attempt.manifest);
    }

    private static void rejectCallbackReentry(UserRecord record) {
        Thread current = Thread.currentThread();
        for (CallbackFrame frame = record.callbacks; frame != null; frame = frame.next) {
            if (frame.thread == current) {
                throw new IllegalStateException(
                        "Installer callbacks must not activate or reinstall their own user");
            }
        }
    }

    private static void addCallbackFrame(UserRecord record, Thread thread) {
        record.callbacks = new CallbackFrame(thread, record.callbacks);
    }

    private static void removeCallbackFrame(UserRecord record, Thread thread) {
        CallbackFrame previous = null;
        CallbackFrame frame = record.callbacks;
        while (frame != null) {
            if (frame.thread == thread) {
                if (previous == null) {
                    record.callbacks = frame.next;
                } else {
                    previous.next = frame.next;
                }
                return;
            }
            previous = frame;
            frame = frame.next;
        }
    }
}
