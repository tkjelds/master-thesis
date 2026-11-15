@UserSpaceProvisioner.ThreadSafe
public final class UserSpaceProvisioner {
    @interface ThreadSafe {}

    public interface Installer {
        String[] install(String user, String[] configuration);
    }

    public enum State {
        PENDING, INSTALLING, ACTIVE, FAILED
    }

    public static final class Status {
        public final State state;
        public final long generation;

        /** Detached from the provisioner; null unless ACTIVE. */
        public final String[] manifest;

        private Status(State state, long generation, String[] manifest) {
            this.state = state;
            this.generation = generation;
            this.manifest = manifest == null ? null : manifest.clone();
        }
    }

    public static final class Metrics {
        public final long pending;
        public final long installing;
        public final long active;
        public final long failed;
        public final long totalUsers;

        private Metrics(long pending, long installing, long active,
                        long failed) {
            this.pending = pending;
            this.installing = installing;
            this.active = active;
            this.failed = failed;
            this.totalUsers = pending + installing + active + failed;
        }
    }

    private static final class User {
        final String name;
        final User next;

        String[] configuration;
        long generation = 1;
        State state = State.PENDING;
        String[] manifest;
        Attempt current;
        Attempt running;

        User(String name, String[] configuration, User next) {
            this.name = name;
            this.configuration = configuration;
            this.next = next;
        }
    }

    private static final class Attempt {
        final long generation;
        final String[] configuration;
        final Thread owner;

        Attempt nextRunning;
        boolean completed;
        String[] manifest;
        Throwable failure;

        Attempt(long generation, String[] configuration, Thread owner,
                Attempt nextRunning) {
            this.generation = generation;
            this.configuration = configuration;
            this.owner = owner;
            this.nextRunning = nextRunning;
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

    public void register(String user, String[] configuration) {
        validateUserName(user);
        String[] copiedConfiguration = copyConfiguration(configuration);

        synchronized (monitor) {
            if (findUser(user) != null) {
                throw new IllegalArgumentException("User already registered: " + user);
            }
            users = new User(user, copiedConfiguration, users);
        }
    }

    /**
     * Runs a pending installation synchronously, or joins the current attempt.
     * Joining waits interruptibly; interruption does not cancel the attempt.
     */
    public String[] activate(String user) throws InterruptedException {
        validateUserName(user);
        User entry;
        Attempt attempt;

        synchronized (monitor) {
            entry = requireUser(user);
            rejectRecursiveInstallation(entry);

            if (entry.state == State.ACTIVE) {
                return entry.manifest.clone();
            }

            if (entry.state == State.FAILED) {
                return result(entry.current);
            }

            if (entry.state == State.INSTALLING) {
                attempt = entry.current;
                while (!attempt.completed) {
                    monitor.wait();
                }
                return result(attempt);
            }

            attempt = new Attempt(entry.generation, entry.configuration,
                                  Thread.currentThread(), entry.running);
            entry.running = attempt;
            entry.current = attempt;
            entry.state = State.INSTALLING;
        }

        return execute(entry, attempt);
    }

    /**
     * Immediately supersedes any unfinished attempt, then runs the replacement
     * synchronously. Superseded callers need not wait for the old callback.
     */
    public String[] reinstall(String user, String[] configuration) {
        validateUserName(user);
        String[] copiedConfiguration = copyConfiguration(configuration);
        User entry;
        Attempt attempt;

        synchronized (monitor) {
            entry = requireUser(user);
            rejectRecursiveInstallation(entry);

            if (entry.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("Generation overflow: " + user);
            }

            long nextGeneration = entry.generation + 1;
            attempt = new Attempt(nextGeneration, copiedConfiguration,
                                  Thread.currentThread(), entry.running);

            Attempt previous = entry.current;
            IllegalStateException supersession = null;
            if (previous != null && !previous.completed) {
                supersession = superseded(entry, previous);
            }

            if (supersession != null) {
                previous.failure = supersession;
                previous.completed = true;
            }

            entry.configuration = copiedConfiguration;
            entry.generation = nextGeneration;
            entry.manifest = null;
            entry.current = attempt;
            entry.running = attempt;
            entry.state = State.INSTALLING;
            monitor.notifyAll();
        }

        return execute(entry, attempt);
    }

    public Status verify(String user) {
        validateUserName(user);

        synchronized (monitor) {
            User entry = requireUser(user);
            return new Status(entry.state, entry.generation, entry.manifest);
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (monitor) {
            long pending = 0;
            long installing = 0;
            long active = 0;
            long failed = 0;

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

    private String[] execute(User entry, Attempt attempt) {
        String[] manifest = null;
        Throwable failure = null;

        try {
            String[] suppliedConfiguration = attempt.configuration.clone();
            manifest = copyManifest(
                    installer.install(entry.name, suppliedConfiguration));
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }

        synchronized (monitor) {
            removeRunningAttempt(entry, attempt);

            if (!attempt.completed) {
                if (entry.current != attempt
                        || entry.generation != attempt.generation) {
                    attempt.failure = superseded(entry, attempt);
                } else {
                    attempt.manifest = manifest;
                    attempt.failure = failure;
                    entry.manifest = failure == null ? manifest : null;
                    entry.state = failure == null ? State.ACTIVE : State.FAILED;
                }
                attempt.completed = true;
            }

            monitor.notifyAll();
            return result(attempt);
        }
    }

    /* Called only while holding monitor. */
    private static String[] result(Attempt attempt) {
        if (attempt.failure instanceof RuntimeException) {
            throw (RuntimeException) attempt.failure;
        }
        if (attempt.failure instanceof Error) {
            throw (Error) attempt.failure;
        }
        return attempt.manifest.clone();
    }

    /* Running attempts include superseded callbacks until they actually exit. */
    private static void rejectRecursiveInstallation(User entry) {
        Thread caller = Thread.currentThread();
        for (Attempt attempt = entry.running;
                attempt != null; attempt = attempt.nextRunning) {
            if (attempt.owner == caller) {
                throw new IllegalStateException(
                        "Installer cannot activate or reinstall its own user: "
                                + entry.name);
            }
        }
    }

    private static void removeRunningAttempt(User entry, Attempt target) {
        Attempt previous = null;
        Attempt current = entry.running;

        while (current != null) {
            if (current == target) {
                if (previous == null) {
                    entry.running = current.nextRunning;
                } else {
                    previous.nextRunning = current.nextRunning;
                }
                current.nextRunning = null;
                return;
            }
            previous = current;
            current = current.nextRunning;
        }
    }

    private static IllegalStateException superseded(User entry, Attempt attempt) {
        return new IllegalStateException(
                "Installation superseded for user " + entry.name
                        + ", generation " + attempt.generation);
    }

    /* User lookup and all mutable user/attempt access require monitor. */
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

    private static void validateUserName(String user) {
        if (user == null || user.length() == 0) {
            throw new IllegalArgumentException("Username must not be null or empty");
        }
    }

    private static String[] copyConfiguration(String[] configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("Configuration must not be null");
        }

        String[] copy = configuration.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == null) {
                throw new IllegalArgumentException(
                        "Configuration entry must not be null at index " + i);
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
                throw new IllegalStateException(
                        "Installer returned a null manifest entry at index " + i);
            }
        }
        return copy;
    }
}
