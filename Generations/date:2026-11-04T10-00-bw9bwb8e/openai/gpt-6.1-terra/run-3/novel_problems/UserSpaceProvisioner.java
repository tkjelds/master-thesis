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
        public final State state;
        public final long generation;
        public final String[] manifest;

        private UserSnapshot(State state, long generation, String[] manifest) {
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
        final String[] configuration;
        boolean done;
        boolean superseded;
        String[] manifest;
        Throwable failure;

        Attempt(String[] configuration) {
            this.configuration = configuration;
        }
    }

    private static final class User {
        final String name;
        User next;
        String[] configuration;
        long generation;
        State state;
        String[] manifest;
        Throwable failure;
        Attempt attempt;

        User(String name, String[] configuration) {
            this.name = name;
            this.configuration = configuration;
            this.generation = 1;
            this.state = State.PENDING;
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
        User newUser = new User(user, copiedConfiguration);

        synchronized (monitor) {
            if (findUser(user) != null) {
                throw new IllegalArgumentException("User already registered: " + user);
            }
            newUser.next = users;
            users = newUser;
        }
    }

    public String[] activate(String user) throws InterruptedException {
        validateUser(user);
        User record;
        Attempt attempt;

        synchronized (monitor) {
            record = requireUser(user);

            if (record.state == State.ACTIVE) {
                return copyArray(record.manifest);
            }
            if (record.state == State.FAILED) {
                throwFailure(record.failure);
            }
            if (record.state == State.INSTALLING) {
                attempt = record.attempt;
                while (!attempt.done) {
                    monitor.wait();
                }
                return attemptResult(attempt);
            }

            attempt = new Attempt(copyArray(record.configuration));
            record.attempt = attempt;
            record.state = State.INSTALLING;
        }

        return runAttempt(record, attempt);
    }

    public String[] reinstall(String user, String[] configuration)
            throws InterruptedException {
        validateUser(user);
        String[] copiedConfiguration = copyConfiguration(configuration);
        String[] installerConfiguration = copyArray(copiedConfiguration);
        User record;
        Attempt attempt = new Attempt(installerConfiguration);

        synchronized (monitor) {
            record = requireUser(user);
            if (record.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("Generation overflow");
            }

            if (record.state == State.INSTALLING) {
                Attempt oldAttempt = record.attempt;
                oldAttempt.superseded = true;
                oldAttempt.done = true;
            }

            record.configuration = copiedConfiguration;
            record.generation++;
            record.manifest = null;
            record.failure = null;
            record.attempt = attempt;
            record.state = State.INSTALLING;
            monitor.notifyAll();
        }

        return runAttempt(record, attempt);
    }

    public UserSnapshot verify(String user) {
        validateUser(user);
        synchronized (monitor) {
            User record = requireUser(user);
            String[] manifest = record.manifest == null
                    ? null : copyArray(record.manifest);
            return new UserSnapshot(record.state, record.generation, manifest);
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (monitor) {
            long pending = 0;
            long installing = 0;
            long active = 0;
            long failed = 0;
            long total = 0;

            for (User current = users; current != null; current = current.next) {
                total++;
                switch (current.state) {
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

    private String[] runAttempt(User user, Attempt attempt) {
        String[] manifest = null;
        Throwable failure = null;

        try {
            String[] installed = installer.install(user.name, attempt.configuration);
            manifest = copyManifest(installed);
        } catch (Throwable thrown) {
            if (thrown instanceof RuntimeException || thrown instanceof Error) {
                failure = thrown;
            } else {
                failure = new IllegalStateException("Installation failed", thrown);
            }
        }

        synchronized (monitor) {
            if (!attempt.done) {
                if (failure == null) {
                    attempt.manifest = manifest;
                    user.manifest = manifest;
                    user.state = State.ACTIVE;
                } else {
                    attempt.failure = failure;
                    user.failure = failure;
                    user.state = State.FAILED;
                }
                attempt.done = true;
                monitor.notifyAll();
            }
            return attemptResult(attempt);
        }
    }

    private static String[] attemptResult(Attempt attempt) {
        if (attempt.superseded) {
            throw new IllegalStateException("Installation attempt was superseded");
        }
        if (attempt.failure != null) {
            throwFailure(attempt.failure);
        }
        return copyArray(attempt.manifest);
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

    private User requireUser(String name) {
        User user = findUser(name);
        if (user == null) {
            throw new IllegalArgumentException("Unknown user: " + name);
        }
        return user;
    }

    private User findUser(String name) {
        for (User current = users; current != null; current = current.next) {
            if (current.name.equals(name)) {
                return current;
            }
        }
        return null;
    }

    private static void validateUser(String user) {
        if (user == null || user.length() == 0) {
            throw new IllegalArgumentException("Username must not be null or empty");
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

    private static String[] copyArray(String[] source) {
        String[] copy = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }
}
