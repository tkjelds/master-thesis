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
        public final State status;
        public final long generation;
        public final String[] manifest;

        private VerificationSnapshot(State status, long generation, String[] manifest) {
            this.status = status;
            this.generation = generation;
            this.manifest = manifest;
        }
    }

    public static final class MetricsSnapshot {
        public final int pending;
        public final int installing;
        public final int active;
        public final int failed;
        public final int totalUsers;

        private MetricsSnapshot(int pending, int installing, int active, int failed) {
            this.pending = pending;
            this.installing = installing;
            this.active = active;
            this.failed = failed;
            this.totalUsers = pending + installing + active + failed;
        }
    }

    private static final class User {
        final String name;
        String[] configuration;
        long generation;
        State state;
        String[] manifest;
        RuntimeException failure;
        Attempt currentAttempt;
        CallbackFrame callbackFrames;
        User next;

        User(String name, String[] configuration) {
            this.name = name;
            this.configuration = configuration;
            this.generation = 1L;
            this.state = State.PENDING;
        }
    }

    private static final class Attempt {
        final long generation;
        final String[] configuration;
        boolean done;
        boolean superseded;
        String[] manifest;
        RuntimeException failure;

        Attempt(long generation, String[] configuration) {
            this.generation = generation;
            this.configuration = configuration;
        }
    }

    private static final class CallbackFrame {
        final Thread thread;
        CallbackFrame next;

        CallbackFrame(Thread thread, CallbackFrame next) {
            this.thread = thread;
            this.next = next;
        }
    }

    private final Object stateLock = new Object();
    private final Installer installer;
    private User users;

    public UserSpaceProvisioner(Installer installer) {
        if (installer == null) {
            throw new IllegalArgumentException("installer must not be null");
        }
        this.installer = installer;
    }

    public void register(String user, String[] configuration) {
        validateUserName(user);
        String[] copiedConfiguration = copyConfiguration(configuration);

        synchronized (stateLock) {
            if (findUserLocked(user) != null) {
                throw new IllegalStateException("User is already registered: " + user);
            }
            User added = new User(user, copiedConfiguration);
            added.next = users;
            users = added;
        }
    }

    public String[] activate(String user) throws InterruptedException {
        validateUserName(user);
        User target;
        Attempt attempt;

        synchronized (stateLock) {
            target = findUserLocked(user);
            if (target == null) {
                throw unknownUser(user);
            }
            rejectCallbackRecursionLocked(target);

            if (target.state == State.ACTIVE) {
                return copyStrings(target.manifest);
            }
            if (target.state == State.FAILED) {
                throw target.failure;
            }
            if (target.state == State.INSTALLING) {
                attempt = target.currentAttempt;
                while (!attempt.done) {
                    stateLock.wait();
                }
                return completedResultLocked(attempt);
            }

            attempt = new Attempt(target.generation, copyStrings(target.configuration));
            target.currentAttempt = attempt;
            target.state = State.INSTALLING;
            addCallbackFrameLocked(target, Thread.currentThread());
        }

        return runAttempt(target, attempt);
    }

    public String[] reinstall(String user, String[] configuration) {
        validateUserName(user);
        String[] copiedConfiguration = copyConfiguration(configuration);
        User target;
        Attempt attempt;

        synchronized (stateLock) {
            target = findUserLocked(user);
            if (target == null) {
                throw unknownUser(user);
            }
            rejectCallbackRecursionLocked(target);
            if (target.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("User generation overflow: " + user);
            }

            if (target.currentAttempt != null && !target.currentAttempt.done) {
                target.currentAttempt.done = true;
                target.currentAttempt.superseded = true;
            }

            target.generation++;
            target.configuration = copiedConfiguration;
            target.manifest = null;
            target.failure = null;

            attempt = new Attempt(target.generation, copyStrings(copiedConfiguration));
            target.currentAttempt = attempt;
            target.state = State.INSTALLING;
            addCallbackFrameLocked(target, Thread.currentThread());
            stateLock.notifyAll();
        }

        return runAttempt(target, attempt);
    }

    public VerificationSnapshot verify(String user) {
        validateUserName(user);

        synchronized (stateLock) {
            User target = findUserLocked(user);
            if (target == null) {
                throw unknownUser(user);
            }
            String[] copiedManifest = target.state == State.ACTIVE
                    ? copyStrings(target.manifest)
                    : null;
            return new VerificationSnapshot(target.state, target.generation, copiedManifest);
        }
    }

    public MetricsSnapshot snapshotMetrics() {
        synchronized (stateLock) {
            int pending = 0;
            int installing = 0;
            int active = 0;
            int failed = 0;

            for (User user = users; user != null; user = user.next) {
                switch (user.state) {
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

            return new MetricsSnapshot(pending, installing, active, failed);
        }
    }

    private String[] runAttempt(User user, Attempt attempt) {
        String[] installedManifest = null;
        RuntimeException installFailure = null;

        try {
            installedManifest = copyManifest(
                    installer.install(user.name, copyStrings(attempt.configuration)));
        } catch (RuntimeException exception) {
            installFailure = exception;
        }

        synchronized (stateLock) {
            removeCallbackFrameLocked(user, Thread.currentThread());

            if (!attempt.done) {
                if (user.currentAttempt == attempt
                        && user.generation == attempt.generation
                        && user.state == State.INSTALLING) {
                    attempt.manifest = installedManifest;
                    attempt.failure = installFailure;
                    attempt.done = true;

                    if (installFailure == null) {
                        user.manifest = copyStrings(installedManifest);
                        user.failure = null;
                        user.state = State.ACTIVE;
                    } else {
                        user.manifest = null;
                        user.failure = installFailure;
                        user.state = State.FAILED;
                    }
                } else {
                    attempt.done = true;
                    attempt.superseded = true;
                }
            }

            stateLock.notifyAll();
            return completedResultLocked(attempt);
        }
    }

    private String[] completedResultLocked(Attempt attempt) {
        if (attempt.superseded) {
            throw new IllegalStateException("Installation attempt was superseded");
        }
        if (attempt.failure != null) {
            throw attempt.failure;
        }
        return copyStrings(attempt.manifest);
    }

    private void addCallbackFrameLocked(User user, Thread thread) {
        user.callbackFrames = new CallbackFrame(thread, user.callbackFrames);
    }

    private void removeCallbackFrameLocked(User user, Thread thread) {
        CallbackFrame previous = null;
        CallbackFrame frame = user.callbackFrames;

        while (frame != null) {
            if (frame.thread == thread) {
                if (previous == null) {
                    user.callbackFrames = frame.next;
                } else {
                    previous.next = frame.next;
                }
                return;
            }
            previous = frame;
            frame = frame.next;
        }
    }

    private void rejectCallbackRecursionLocked(User user) {
        Thread current = Thread.currentThread();
        for (CallbackFrame frame = user.callbackFrames; frame != null; frame = frame.next) {
            if (frame.thread == current) {
                throw new IllegalStateException(
                        "Installer callbacks cannot activate or reinstall their own user");
            }
        }
    }

    private User findUserLocked(String name) {
        for (User user = users; user != null; user = user.next) {
            if (user.name.equals(name)) {
                return user;
            }
        }
        return null;
    }

    private static void validateUserName(String user) {
        if (user == null || user.length() == 0) {
            throw new IllegalArgumentException("user must not be null or empty");
        }
    }

    private static String[] copyConfiguration(String[] configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        for (int i = 0; i < configuration.length; i++) {
            if (configuration[i] == null) {
                throw new IllegalArgumentException("configuration entries must not be null");
            }
        }
        return copyStrings(configuration);
    }

    private static String[] copyManifest(String[] manifest) {
        if (manifest == null) {
            throw new IllegalStateException("installer returned a null manifest");
        }
        for (int i = 0; i < manifest.length; i++) {
            if (manifest[i] == null) {
                throw new IllegalStateException("installer returned a manifest with a null entry");
            }
        }
        return copyStrings(manifest);
    }

    private static String[] copyStrings(String[] values) {
        String[] copy = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            copy[i] = values[i];
        }
        return copy;
    }

    private static IllegalArgumentException unknownUser(String user) {
        return new IllegalArgumentException("Unknown user: " + user);
    }
}
