@UserSpaceProvisioner.ThreadSafe
public final class UserSpaceProvisioner {
    @interface ThreadSafe {}

    public interface Installer {
        String[] install(String user, String[] configuration);
    }

    public enum State {
        PENDING, INSTALLING, ACTIVE, FAILED
    }

    public static final class StatusSnapshot {
        public final State state;
        public final long generation;
        private final String[] manifest;

        private StatusSnapshot(State state, long generation, String[] manifest) {
            this.state = state;
            this.generation = generation;
            this.manifest = manifest;
        }

        public String[] getManifest() {
            return manifest == null ? null : manifest.clone();
        }
    }

    public static final class Metrics {
        public final long pending;
        public final long installing;
        public final long active;
        public final long failed;
        public final long total;

        private Metrics(long pending, long installing, long active,
                        long failed, long total) {
            this.pending = pending;
            this.installing = installing;
            this.active = active;
            this.failed = failed;
            this.total = total;
        }
    }

    private static final int RUNNING = 0;
    private static final int SUCCEEDED = 1;
    private static final int ATTEMPT_FAILED = 2;
    private static final int SUPERSEDED = 3;

    private static final class Attempt {
        int outcome = RUNNING;
        String[] manifest;
        Throwable failure;
    }

    private static final class User {
        final String name;
        User next;
        String[] configuration;
        long generation;
        State state;
        String[] manifest;
        Attempt attempt;

        User(String name, String[] configuration) {
            this.name = name;
            this.configuration = configuration;
            this.generation = 1;
            this.state = State.PENDING;
        }
    }

    /*
     * Tracks callbacks executing on each thread, including nested callbacks
     * for different users. Recursive activation or reinstallation of the same
     * user is rejected rather than allowing a callback to wait for itself.
     */
    private static final class CallbackFrame {
        final Thread thread;
        final User user;
        CallbackFrame next;

        CallbackFrame(Thread thread, User user) {
            this.thread = thread;
            this.user = user;
        }
    }

    private final Object lock = new Object();
    private final Installer installer;
    private User users;
    private CallbackFrame callbacks;

    public UserSpaceProvisioner(Installer installer) {
        if (installer == null) {
            throw new IllegalArgumentException("Installer must not be null");
        }
        this.installer = installer;
    }

    public void register(String user, String[] configuration) {
        validateName(user);
        String[] copy = copyConfiguration(configuration);

        synchronized (lock) {
            if (findUser(user) != null) {
                throw new IllegalArgumentException("User already registered: " + user);
            }
            User entry = new User(user, copy);
            entry.next = users;
            users = entry;
        }
    }

    /*
     * An interrupted join does not cancel the installation. Other callers
     * may continue waiting for or observing its result.
     */
    public String[] activate(String user) throws InterruptedException {
        validateName(user);
        User entry;
        Attempt attempt;
        String[] callbackConfiguration;
        CallbackFrame frame;

        synchronized (lock) {
            entry = requireUser(user);
            rejectRecursiveCallback(entry);

            if (entry.state == State.ACTIVE) {
                return entry.manifest.clone();
            }
            if (entry.state == State.FAILED) {
                throw failureException(entry.attempt.failure);
            }
            if (entry.state == State.INSTALLING) {
                attempt = entry.attempt;
                while (attempt.outcome == RUNNING) {
                    lock.wait();
                }
                return attemptResult(attempt);
            }

            callbackConfiguration = entry.configuration.clone();
            attempt = new Attempt();
            frame = new CallbackFrame(Thread.currentThread(), entry);
            entry.attempt = attempt;
            entry.state = State.INSTALLING;
            addCallback(frame);
        }

        return runInstallation(entry, attempt, callbackConfiguration, frame);
    }

    public String[] reinstall(String user, String[] configuration) {
        validateName(user);
        String[] copy = copyConfiguration(configuration);
        String[] callbackConfiguration = copy.clone();
        User entry;
        Attempt attempt;
        CallbackFrame frame;

        synchronized (lock) {
            entry = requireUser(user);
            rejectRecursiveCallback(entry);
            if (entry.generation == 9223372036854775807L) {
                throw new IllegalStateException("Generation overflow");
            }

            attempt = new Attempt();
            frame = new CallbackFrame(Thread.currentThread(), entry);

            if (entry.state == State.INSTALLING) {
                entry.attempt.outcome = SUPERSEDED;
            }
            entry.configuration = copy;
            entry.generation++;
            entry.manifest = null;
            entry.attempt = attempt;
            entry.state = State.INSTALLING;
            addCallback(frame);
            lock.notifyAll();
        }

        return runInstallation(entry, attempt, callbackConfiguration, frame);
    }

    public StatusSnapshot verify(String user) {
        validateName(user);
        synchronized (lock) {
            User entry = requireUser(user);
            String[] manifest = entry.manifest == null
                    ? null : entry.manifest.clone();
            return new StatusSnapshot(entry.state, entry.generation, manifest);
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (lock) {
            long pending = 0;
            long installing = 0;
            long active = 0;
            long failed = 0;
            long total = 0;

            for (User entry = users; entry != null; entry = entry.next) {
                total++;
                switch (entry.state) {
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
                        throw new AssertionError("Unknown state");
                }
            }
            return new Metrics(pending, installing, active, failed, total);
        }
    }

    private String[] runInstallation(User entry, Attempt attempt,
                                     String[] configuration,
                                     CallbackFrame frame) {
        String[] manifest = null;
        Throwable failure = null;

        try {
            String[] installed = installer.install(entry.name, configuration);
            manifest = copyManifest(installed);
        } catch (RuntimeException exception) {
            failure = exception;
        } catch (Error error) {
            failure = error;
        }

        synchronized (lock) {
            removeCallback(frame);

            if (attempt.outcome == SUPERSEDED) {
                throw new IllegalStateException("Installation was superseded");
            }

            if (failure == null) {
                attempt.manifest = manifest;
                attempt.outcome = SUCCEEDED;
                entry.manifest = manifest;
                entry.state = State.ACTIVE;
            } else {
                attempt.failure = failure;
                attempt.outcome = ATTEMPT_FAILED;
                entry.manifest = null;
                entry.state = State.FAILED;
            }
            lock.notifyAll();
            return attemptResult(attempt);
        }
    }

    /* Called only while holding lock. */
    private static String[] attemptResult(Attempt attempt) {
        if (attempt.outcome == SUCCEEDED) {
            return attempt.manifest.clone();
        }
        if (attempt.outcome == ATTEMPT_FAILED) {
            throw failureException(attempt.failure);
        }
        if (attempt.outcome == SUPERSEDED) {
            throw new IllegalStateException("Installation was superseded");
        }
        throw new IllegalStateException("Installation is still running");
    }

    private static RuntimeException failureException(Throwable failure) {
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        return (RuntimeException) failure;
    }

    private static void validateName(String user) {
        if (user == null || user.length() == 0) {
            throw new IllegalArgumentException("User name must not be null or empty");
        }
    }

    private static String[] copyConfiguration(String[] configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("Configuration must not be null");
        }
        String[] copy = configuration.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == null) {
                throw new IllegalArgumentException("Configuration entry must not be null");
            }
        }
        return copy;
    }

    private static String[] copyManifest(String[] manifest) {
        if (manifest == null) {
            throw new IllegalStateException("Installer returned a null manifest");
        }
        String[] copy = manifest.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == null) {
                throw new IllegalStateException("Installer returned a null manifest entry");
            }
        }
        return copy;
    }

    /* The following helpers are called only while holding lock. */
    private User findUser(String name) {
        for (User entry = users; entry != null; entry = entry.next) {
            if (entry.name.equals(name)) {
                return entry;
            }
        }
        return null;
    }

    private User requireUser(String name) {
        User entry = findUser(name);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown user: " + name);
        }
        return entry;
    }

    private void rejectRecursiveCallback(User user) {
        Thread current = Thread.currentThread();
        for (CallbackFrame frame = callbacks; frame != null; frame = frame.next) {
            if (frame.thread == current && frame.user == user) {
                throw new IllegalStateException(
                        "Installer cannot activate or reinstall its own user");
            }
        }
    }

    private void addCallback(CallbackFrame frame) {
        frame.next = callbacks;
        callbacks = frame;
    }

    private void removeCallback(CallbackFrame frame) {
        CallbackFrame previous = null;
        CallbackFrame current = callbacks;
        while (current != null) {
            if (current == frame) {
                if (previous == null) {
                    callbacks = current.next;
                } else {
                    previous.next = current.next;
                }
                return;
            }
            previous = current;
            current = current.next;
        }
        throw new AssertionError("Missing callback frame");
    }
}
