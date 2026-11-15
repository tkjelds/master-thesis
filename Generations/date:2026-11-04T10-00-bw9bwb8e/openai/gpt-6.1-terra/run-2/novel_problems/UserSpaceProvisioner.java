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
        public final String[] manifest;

        private Status(State state, long generation, String[] manifest) {
            this.state = state;
            this.generation = generation;
            this.manifest = manifest;
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

    private static final class Attempt {
        final long generation;
        boolean done;
        boolean superseded;
        String[] manifest;
        RuntimeException failure;

        Attempt(long generation) {
            this.generation = generation;
        }
    }

    private static final class User {
        final String name;
        String[] configuration;
        long generation;
        State state;
        String[] manifest;
        RuntimeException failure;
        Attempt attempt;
        User next;

        User(String name, String[] configuration, User next) {
            this.name = name;
            this.configuration = configuration;
            this.generation = 1;
            this.state = State.PENDING;
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

    public void register(String user, String[] configuration) {
        validateUser(user);
        String[] copiedConfiguration = copyConfiguration(configuration);

        synchronized (monitor) {
            if (findUser(user) != null) {
                throw new IllegalArgumentException("User already registered: " + user);
            }
            users = new User(user, copiedConfiguration, users);
        }
    }

    public String[] activate(String user) throws InterruptedException {
        validateUser(user);
        User record;
        Attempt attempt;
        String[] callbackConfiguration = null;
        boolean start = false;

        synchronized (monitor) {
            record = requireUser(user);
            if (record.state == State.ACTIVE) {
                return copyArray(record.manifest);
            }
            if (record.state == State.FAILED) {
                throw record.failure;
            }
            if (record.state == State.PENDING) {
                callbackConfiguration = copyArray(record.configuration);
                attempt = new Attempt(record.generation);
                record.attempt = attempt;
                record.state = State.INSTALLING;
                start = true;
            } else {
                attempt = record.attempt;
            }
        }

        if (start) {
            return runInstallation(record, attempt, callbackConfiguration);
        }
        return awaitAttempt(attempt);
    }

    public String[] reinstall(String user, String[] configuration) {
        validateUser(user);
        String[] copiedConfiguration = copyConfiguration(configuration);
        String[] callbackConfiguration = copyArray(copiedConfiguration);
        User record;
        Attempt attempt;

        synchronized (monitor) {
            record = requireUser(user);
            if (record.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("Generation overflow for user: " + user);
            }

            attempt = new Attempt(record.generation + 1);
            if (record.state == State.INSTALLING) {
                record.attempt.superseded = true;
                record.attempt.done = true;
            }

            record.configuration = copiedConfiguration;
            record.generation = attempt.generation;
            record.manifest = null;
            record.failure = null;
            record.attempt = attempt;
            record.state = State.INSTALLING;
            monitor.notifyAll();
        }

        return runInstallation(record, attempt, callbackConfiguration);
    }

    public Status verify(String user) {
        validateUser(user);
        synchronized (monitor) {
            User record = requireUser(user);
            return new Status(record.state, record.generation,
                    record.manifest == null ? null : copyArray(record.manifest));
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (monitor) {
            long pending = 0;
            long installing = 0;
            long active = 0;
            long failed = 0;
            long total = 0;

            for (User record = users; record != null; record = record.next) {
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

    private String[] runInstallation(User record, Attempt attempt,
                                     String[] configuration) {
        String[] manifest = null;
        RuntimeException failure = null;

        try {
            manifest = copyManifest(installer.install(record.name, configuration));
        } catch (RuntimeException exception) {
            failure = exception;
        } catch (Throwable throwable) {
            failure = new IllegalStateException("Installer failed", throwable);
        }

        synchronized (monitor) {
            if (!attempt.done) {
                if (record.attempt != attempt
                        || record.generation != attempt.generation
                        || record.state != State.INSTALLING) {
                    attempt.superseded = true;
                } else if (failure != null) {
                    attempt.failure = failure;
                    record.failure = failure;
                    record.manifest = null;
                    record.state = State.FAILED;
                } else {
                    attempt.manifest = manifest;
                    record.manifest = manifest;
                    record.failure = null;
                    record.state = State.ACTIVE;
                }
                attempt.done = true;
                monitor.notifyAll();
            }
            return attemptResult(attempt);
        }
    }

    private String[] awaitAttempt(Attempt attempt) throws InterruptedException {
        synchronized (monitor) {
            while (!attempt.done) {
                monitor.wait();
            }
            return attemptResult(attempt);
        }
    }

    // Called only while holding monitor.
    private String[] attemptResult(Attempt attempt) {
        if (attempt.superseded) {
            throw new IllegalStateException("Installation was superseded");
        }
        if (attempt.failure != null) {
            throw attempt.failure;
        }
        return copyArray(attempt.manifest);
    }

    // Called only while holding monitor.
    private User findUser(String name) {
        for (User record = users; record != null; record = record.next) {
            if (record.name.equals(name)) {
                return record;
            }
        }
        return null;
    }

    // Called only while holding monitor.
    private User requireUser(String name) {
        User record = findUser(name);
        if (record == null) {
            throw new IllegalArgumentException("Unknown user: " + name);
        }
        return record;
    }

    private static void validateUser(String user) {
        if (user == null || user.length() == 0) {
            throw new IllegalArgumentException("User must not be null or empty");
        }
    }

    private static String[] copyConfiguration(String[] configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("Configuration must not be null");
        }
        String[] copy = new String[configuration.length];
        for (int i = 0; i < configuration.length; i++) {
            String entry = configuration[i];
            if (entry == null) {
                throw new IllegalArgumentException("Configuration entry must not be null");
            }
            copy[i] = entry;
        }
        return copy;
    }

    private static String[] copyManifest(String[] manifest) {
        if (manifest == null) {
            throw new IllegalStateException("Installer returned a null manifest");
        }
        String[] copy = new String[manifest.length];
        for (int i = 0; i < manifest.length; i++) {
            String entry = manifest[i];
            if (entry == null) {
                throw new IllegalStateException("Installer returned a null manifest entry");
            }
            copy[i] = entry;
        }
        return copy;
    }

    private static String[] copyArray(String[] array) {
        String[] copy = new String[array.length];
        for (int i = 0; i < array.length; i++) {
            copy[i] = array[i];
        }
        return copy;
    }
}
