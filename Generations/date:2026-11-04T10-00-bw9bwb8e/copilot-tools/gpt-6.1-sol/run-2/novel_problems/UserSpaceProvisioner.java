@UserSpaceProvisioner.ThreadSafe
public final class UserSpaceProvisioner {
    @interface ThreadSafe {}

    public interface Installer {
        String[] install(String user, String[] configuration);
    }

    public enum State {
        PENDING, INSTALLING, ACTIVE, FAILED
    }

    public static final class UserSnapshot {
        private final State state;
        private final long generation;
        private final String[] manifest;

        private UserSnapshot(State state, long generation, String[] manifest) {
            this.state = state;
            this.generation = generation;
            this.manifest = copy(manifest);
        }

        public State getState() {
            return state;
        }

        public long getGeneration() {
            return generation;
        }

        public String[] getManifest() {
            return copy(manifest);
        }
    }

    public static final class Metrics {
        public final long pending;
        public final long installing;
        public final long active;
        public final long failed;
        public final long totalUsers;

        private Metrics(long pending, long installing, long active, long failed) {
            this.pending = pending;
            this.installing = installing;
            this.active = active;
            this.failed = failed;
            this.totalUsers = pending + installing + active + failed;
        }
    }

    private static final class Attempt {
        final long generation;
        final String[] configuration;
        final Thread owner;
        Attempt nextRunning;

        boolean completed;
        boolean superseded;
        String[] manifest;
        Throwable failure;

        Attempt(long generation, String[] configuration,
                Thread owner, Attempt nextRunning) {
            this.generation = generation;
            this.configuration = configuration;
            this.owner = owner;
            this.nextRunning = nextRunning;
        }
    }

    private static final class User {
        final String name;
        final User next;

        long generation = 1L;
        State state = State.PENDING;
        String[] configuration;
        Attempt current;
        Attempt running;

        User(String name, String[] configuration, User next) {
            this.name = name;
            this.configuration = configuration;
            this.next = next;
        }
    }

    private final Object monitor = new Object();
    private final Installer installer;
    private User users;

    public UserSpaceProvisioner(Installer installer) {
        if (installer == null) {
            throw new IllegalArgumentException("Installer must not be null");
        }
        this.installer = installer;
    }

    /** Registers generation 1 without invoking the installer. */
    public void register(String user, String[] configuration) {
        validateUser(user);
        String[] savedConfiguration = copyConfiguration(configuration);

        synchronized (monitor) {
            if (find(user) != null) {
                throw new IllegalArgumentException("User already registered: " + user);
            }
            users = new User(user, savedConfiguration, users);
        }
    }

    /**
     * Starts a pending installation or joins the current attempt.
     * Waiting callers may be interrupted without cancelling the installation.
     */
    public String[] activate(String user) throws InterruptedException {
        validateUser(user);

        User entry;
        Attempt attempt;
        boolean execute = false;

        synchronized (monitor) {
            entry = requireUser(user);
            rejectRecursiveInstallation(entry);

            if (entry.state == State.ACTIVE) {
                return copy(entry.current.manifest);
            }
            if (entry.state == State.FAILED) {
                throw propagate(entry.current.failure);
            }

            if (entry.state == State.PENDING) {
                attempt = new Attempt(
                        entry.generation, entry.configuration,
                        Thread.currentThread(), entry.running);
                entry.current = attempt;
                entry.running = attempt;
                entry.state = State.INSTALLING;
                execute = true;
            } else {
                attempt = entry.current;
            }
        }

        if (execute) {
            executeInstallation(entry, attempt);
        }
        return awaitResult(entry, attempt);
    }

    /**
     * Replaces the configuration and starts the next generation immediately.
     * An older in-flight attempt is superseded, not cancelled.
     */
    public String[] reinstall(String user, String[] configuration)
            throws InterruptedException {
        validateUser(user);
        String[] savedConfiguration = copyConfiguration(configuration);

        User entry;
        Attempt attempt;

        synchronized (monitor) {
            entry = requireUser(user);
            rejectRecursiveInstallation(entry);

            if (entry.generation == 0x7fffffffffffffffL) {
                throw new IllegalStateException(
                        "Generation overflow for user: " + user);
            }

            long nextGeneration = entry.generation + 1L;
            attempt = new Attempt(
                    nextGeneration, savedConfiguration,
                    Thread.currentThread(), entry.running);

            if (entry.state == State.INSTALLING) {
                entry.current.superseded = true;
                entry.current.completed = true;
            }

            entry.configuration = savedConfiguration;
            entry.generation = nextGeneration;
            entry.current = attempt;
            entry.running = attempt;
            entry.state = State.INSTALLING;
            monitor.notifyAll();
        }

        executeInstallation(entry, attempt);
        return awaitResult(entry, attempt);
    }

    /** Returns a detached snapshot; non-ACTIVE snapshots have no manifest. */
    public UserSnapshot verify(String user) {
        validateUser(user);

        synchronized (monitor) {
            User entry = requireUser(user);
            return new UserSnapshot(
                    entry.state,
                    entry.generation,
                    entry.state == State.ACTIVE ? entry.current.manifest : null);
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (monitor) {
            long pending = 0L;
            long installing = 0L;
            long active = 0L;
            long failed = 0L;

            for (User entry = users; entry != null; entry = entry.next) {
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
                }
            }

            return new Metrics(pending, installing, active, failed);
        }
    }

    private void executeInstallation(User entry, Attempt attempt) {
        String[] manifest = null;
        Throwable failure = null;

        try {
            String[] result = installer.install(
                    entry.name, copy(attempt.configuration));
            manifest = copyManifest(result);
        } catch (RuntimeException exception) {
            failure = exception;
        } catch (Error error) {
            // Complete the attempt even when the callback throws an Error.
            failure = error;
        }

        synchronized (monitor) {
            removeRunningAttempt(entry, attempt);

            if (entry.current == attempt) {
                attempt.manifest = manifest;
                attempt.failure = failure;
                attempt.completed = true;
                entry.state = failure == null ? State.ACTIVE : State.FAILED;
            }
            // Superseded attempts were completed by reinstall.
            monitor.notifyAll();
        }
    }

    private String[] awaitResult(User entry, Attempt attempt)
            throws InterruptedException {
        synchronized (monitor) {
            while (!attempt.completed) {
                monitor.wait();
            }

            if (attempt.superseded) {
                throw new IllegalStateException(
                        "Installation superseded for user " + entry.name
                                + ", generation " + attempt.generation);
            }
            if (attempt.failure != null) {
                throw propagate(attempt.failure);
            }
            return copy(attempt.manifest);
        }
    }

    // These helpers access mutable user state only while holding monitor.
    private User find(String name) {
        for (User entry = users; entry != null; entry = entry.next) {
            if (entry.name.equals(name)) {
                return entry;
            }
        }
        return null;
    }

    private User requireUser(String name) {
        User entry = find(name);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown user: " + name);
        }
        return entry;
    }

    private void rejectRecursiveInstallation(User entry) {
        Thread caller = Thread.currentThread();
        for (Attempt attempt = entry.running;
                attempt != null; attempt = attempt.nextRunning) {
            if (attempt.owner == caller) {
                throw new IllegalStateException(
                        "Installer cannot recursively activate or reinstall "
                                + "its own user: " + entry.name);
            }
        }
    }

    private void removeRunningAttempt(User entry, Attempt target) {
        Attempt previous = null;
        Attempt attempt = entry.running;

        while (attempt != null) {
            if (attempt == target) {
                if (previous == null) {
                    entry.running = attempt.nextRunning;
                } else {
                    previous.nextRunning = attempt.nextRunning;
                }
                attempt.nextRunning = null;
                return;
            }
            previous = attempt;
            attempt = attempt.nextRunning;
        }
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        return (RuntimeException) failure;
    }

    private static void validateUser(String user) {
        if (user == null || user.length() == 0) {
            throw new IllegalArgumentException(
                    "Username must not be null or empty");
        }
    }

    private static String[] copyConfiguration(String[] configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("Configuration must not be null");
        }

        String[] result = copy(configuration);
        for (int i = 0; i < result.length; i++) {
            if (result[i] == null) {
                throw new IllegalArgumentException(
                        "Configuration entry must not be null: " + i);
            }
        }
        return result;
    }

    private static String[] copyManifest(String[] manifest) {
        if (manifest == null) {
            throw new IllegalStateException("Installer returned a null manifest");
        }

        String[] result = copy(manifest);
        for (int i = 0; i < result.length; i++) {
            if (result[i] == null) {
                throw new IllegalStateException(
                        "Installer returned a null manifest entry: " + i);
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
}
