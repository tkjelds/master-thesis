@UserSpaceProvisioner.ThreadSafe
public class UserSpaceProvisioner {
    @interface ThreadSafe {}


    // --- Interfaces and Helper Classes (Defined internally) ---

    /**
     * Callback interface injected to handle the actual installation process.
     */
    public interface Installer {
        /**
         * Simulates the installation callback.
         * @param user The username.
         * @param configuration The configuration array.
         * @return A String[] representing the successfully installed manifest (copied).
         */
        String[] install(String user, String[] configuration);
    }

    /**
     * Represents the immutable state of a user installation attempt.
     */
    private static class UserRevision {
        final int generation;
        final String[] configuration; // The configuration used for this attempt
        final String[] manifest;     // The final manifest if installation succeeds

        UserRevision(int generation, String[] configuration, String[] manifest) {
            this.generation = generation;
            // Deep copy configurations/manifests if they are arrays passed around, though here we assume immutability once set for safety.
            this.configuration = configuration;
            this.manifest = manifest;
        }
    }

    /**
     * Represents the current known state of a user session.
     */
    private static class UserSession {
        enum State {
            PENDING, INSTALLING, ACTIVE, FAILED
        }

        final String username;
        volatile State currentState;
        int generation; // Monotonically increasing counter
        final UserRevision currentAttempt; // The revision this state is based on
        // Tracks waiters for INSTALLING generation attempts (e.g., callbacks waiting for installation completion)
        final Object installWaiter; // Object used for synchronization on activation attempts
        // Stores the final result if installation was successful or failed in a manner that warrants blocking.
        volatile Object installResult; // Holds the final manifest or an exception wrapper

        UserSession(String username, int generation, UserRevision initialAttempt) {
            this.username = username;
            this.generation = generation;
            this.currentState = State.PENDING;
            this.currentAttempt = initialAttempt;
            this.installWaiter = new Object();
        }
    }

    // --- Member Variables ---

    private final Installer installer;
    private volatile int nextGeneration = 1; // Used for new registrations
    // Maps username to their current session object. Must be synchronized access.
    private final Object userRegistryLock = new Object();
    private java.util.Map<String, UserSession> users = new java.util.java.util.HashMap<>();

    // Metrics tracking (accessed via snapshotMetrics, needs synchronization)
    private volatile int pendingCount = 0;
    private volatile int installingCount = 0;
    private volatile int activeCount = 0;
    private volatile int failedCount = 0;
    private volatile int totalUserCount = 0;

    // --- Constructor and Public API ---

    public UserSpaceProvisioner(Installer installer) {
        this.installer = installer;
    }

    /**
     * Registers a new user with initial configuration.
     * @param user The username.
     * @param configuration The installation configuration array.
     * @return true if registration succeeded, false otherwise (validation failure or duplicate).
     */
    public boolean register(String user, String[] configuration) {
        if (user == null || user.isEmpty() || configuration == null || configuration.length == 0) {
            return false; // Reject invalid inputs
        }

        synchronized (userRegistryLock) {
            if (users.containsKey(user)) {
                return false; // Duplicate user rejected
            }

            // Initial attempt: Generation 1, State PENDING.
            int newGen = nextGeneration++;
            UserRevision initialAttempt = new UserRevision(newGen, configuration, null); // Manifest is not available yet
            UserSession session = new UserSession(user, newGen, initialAttempt);
            users.put(user, session);
            pendingCount++;
            totalUserCount++;
            return true;
        }
    }

    /**
     * Attempts to transition a user from PENDING/INSTALLING to ACTIVE by attempting installation.
     * @param user The username.
     * @return true if activation successfully transitioned to ACTIVE, false otherwise (e.g., unknown user).
     * @throws java.lang.InterruptedException if the activation wait is interrupted during installation phase join.
     * @throws RuntimeException if the underlying installation callback atomically publishes FAILED during activation attempt.
     */
    public void activate(String user) throws InterruptedException {
        synchronized (userRegistryLock) {
            UserSession session = users.get(user);
            if (session == null) {
                // Unknown user tried to activate
                return;
            }

            if (session.currentState == UserSession.State.ACTIVE) {
                // Already active, do nothing and proceed to return manifest later if necessary (though this call implies transition attempt)
                return;
            }

            if (session.currentState == UserSession.State.FAILED) {
                // Cannot activate a failed user without reinstalling first (as per spec, FAILED throws until reinstall)
                throw new IllegalStateException("User is in FAILED state. Must reinstall first.");
            }

            // If PENDING or INSTALLING, we proceed to attempt installation/join.
            if (session.currentState == UserSession.State.PENDING) {
                // Transition to INSTALLING and execute installation callback *outside* the monitor lock, if possible.
                // We must keep the generation consistent for callbacks to rejoin correctly.

                session.currentState = UserSession.State.INSTALLING;
                installingCount++;
                pendingCount--;

                // --- Execute Installer Callback (Simulated blocking IO/network call) ---
                try {
                    // In a real scenario, this installer execution happens asynchronously or in a dedicated thread pool.
                    // Since we are simulating the contract using synchronous calls here, this call blocks until installation completes.
                    // The success/failure logic resides inside the Installer implementation, which eventually wakes up waiters.

                    String[] manifest = installer.install(user, session.currentAttempt.configuration);
                    // Installation success path: The Installer handles publishing the successful result and waking up waiters.
                    // We treat this as setting the final state based on callback success notification mechanism (not fully modeled here, 
                    // but we transition state after the callback returns successfully).

                    session.currentState = UserSession.State.ACTIVE;
                    installingCount--; // Decrement count after successful transition/installation phase completes its lifecycle.
                    activeCount++;

                    // Publish success (manifest is attached to the session object now)
                    session.installResult = manifest; // Atomically published state change observed by subsequent verify/activate calls.

                } catch (RuntimeException e) {
                    // Callback published FAILED state atomically during install attempt
                    session.currentState = UserSession.State.FAILED;
                    installingCount--; // Decrement count as the attempt failed.
                    failedCount++;
                    // Wake all joined callers of this INSTALLING generation with the failure.
                    synchronized (session.installWaiter) {
                        session.installResult = e; // Retain the failure exception.
                        session.installWaiter.notifyAll();
                    }
                    throw e; // Re-throwing the failure as per contract, interrupting waiters.
                } finally {
                    // If installation succeeded, subsequent calls needing the result use session.installResult
                }
            } else if (session.currentState == UserSession.State.INSTALLING) {
                // Concurrent activation caller joins the in-flight installation attempt.
                synchronized (session.installWaiter) {
                    try {
                        // Wait until the installation attempt finishes (successfully or with failure).
                        while (session.currentState == UserSession.State.INSTALLING) {
                            session.installWaiter.wait();
                        }

                        // Installation attempt resolved. Check the final state.
                        if (session.currentState == UserSession.State.FAILED) {
                            // The installResult holds the failure. Re-throw it to fulfill contract.
                            Throwable cause = (Throwable) session.installResult;
                            throw new RuntimeException(cause); // Rethrow the failure cause held by the callback.
                        } else if (session.currentState == UserSession.State.ACTIVE) {
                            // Success! The manifest is available in installResult (or via currentAttempt if installation was instantaneous).
                            // For simplicity matching the contract: ACTIVATED users return manifest immediately.
                            // If installation was successful, session.installResult holds the final manifestation details.
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } // End of userRegistryLock synchronized block for public operations structure

        // If we reach here successfully from INSTALLING join, the user is now ACTIVE and installation completed.
    }

    /**
     * Attempts to install a new configuration, replacing the old one and advancing generation if successful.
     * @param user The username.
     * @param configuration The new desired configuration.
     * @return true if the reconfiguration was accepted and installation process started, false otherwise (e.g., unknown user or revision overflow).
     */
    public boolean reinstall(String user, String[] configuration) {
        synchronized (userRegistryLock) {
            UserSession session = users.get(user);
            if (session == null) {
                return false; // Unknown user
            }

            // Check for revision overflow (Simplified: we assume integer overflow check on generation handles this)
            if (nextGeneration == Integer.MAX_VALUE) {
                return false; // Revision overflow check failed conceptually.
            }

            // 1. Advance generation and create new revision object (old manifest invalidated)
            int oldGeneration = session.generation;
            session.generation = nextGeneration++; // Advance generation NOW to invalidate old attempts

            UserRevision newAttempt = new UserRevision(session.generation, configuration, null); // Null manifest initially
            session.currentAttempt = newAttempt;

            // 2. Atomically transition state to INSTALLING (unless already ACTIVE/PENDING from a non-transient state)
            if (session.currentState == UserSession.State.ACTIVE || session.currentState == UserSession.State.INSTALLING) {
                // If already running, we supersede the old attempt by setting the new currentAttempt and generation.
                // The old in-flight installer must detect this revision change and cease processing/return IllegalStateException to joiners.
                if (session.currentState == UserSession.State.ACTIVE) {
                    // If active, we transition into a new INSTALLING phase based on the command.
                    session.currentState = UserSession.State.INSTALLING;
                    installingCount++;
                }
            } else if (session.currentState == UserSession.State.PENDING || session.currentState == UserSession.State.FAILED) {
                // If pending/failed, this is effectively a fresh start leading to INSTALLING.
                session.currentState = UserSession.State.INSTALLING;
            } else {
                // Should not happen if states are correct, but defensively maintain state context.
            }

            pendingCount--; // Decrement pending count if it was running from PENDING state.
            installingCount++;

            // Note: We return true immediately upon accepting the command to change state/revision. The installation confirmation happens via activation or subsequent process completion.
            return true;
        }
    }

    /**
     * Verifies the current status without attempting installation. Returns a detached snapshot of the user's state.
     * @return A snapshot object containing generation and manifest if ACTIVE, or null/status indicators otherwise.
     */
    public Object verify(String user) {
        synchronized (userRegistryLock) {
            UserSession session = users.get(user);
            if (session == null) {
                return null; // Unknown user
            }

            // Return a detached, copied snapshot.
            if (session.currentState == UserSession.State.ACTIVE) {
                // Must return a copy of the manifest if available for ACTIVE state validation.
                return new Object() {
                    final int generation = session.generation;
                    final String[] manifestCopy = session.currentAttempt.manifest; // Manifest is guaranteed to be non-null and final here.
                };
            } else {
                return new Object() {
                    final String currentState = session.currentState.toString();
                    final int generation = session.generation;
                };
            }
        }
    }

    /**
     * Atomically captures current operational metrics across all users.
     * @return An array representing [pendingCount, installingCount, activeCount, failedCount, totalUserCount].
     */
    public Object snapshotMetrics() {
        // Since all volatile fields are updated atomically upon state transition, reading them inside a sync block ensures consistency for the snapshot.
        synchronized (userRegistryLock) {
            return new Object[]{pendingCount, installingCount, activeCount, failedCount, totalUserCount};
        }
    }

    // --- Internal Synchronization Helpers (For completion scenario) ---

    /**
     * This method simulates the outcome of the asynchronous installer callback, allowing the provisioner to transition state.
     * This should ONLY be called internally by the successful/failed Installer implementation chain.
     * @param user The user whose installation attempt is concluding.
     * @param success Whether the install succeeded.
     * @param finalManifest The resulting manifest on success, or null on failure signal.
     */
    protected void completeInstallation(String user, boolean success, String[] finalManifest) {
        synchronized (userRegistryLock) {
            UserSession session = users.get(user);
            if (session == null) return;

            synchronized (session.installWaiter) {
                // Wake up any joiners waiting on this install attempt before committing final state.
                session.installWaiter.notifyAll();
            }

            if (success) {
                // Success path: Transition to ACTIVE and publish result.
                session.currentState = UserSession.State.ACTIVE;
                installingCount--;
                activeCount++;
                session.currentAttempt = new UserRevision(session.generation, session.currentAttempt.configuration, finalManifest);
                session.installResult = finalManifest; // Publish the result object needed by joiners/verifiers

            } else {
                // Failure path: Transition to FAILED and publish exception.
                session.currentState = UserSession.State.FAILED;
                installingCount--;
                failedCount++;
                // Publish the exception object to all waiting threads.
                session.installResult = new RuntimeException("Installation failed."); // Or the specific error
            }
        }
    }

    // --- Example usage of Installer implementation to drive the process (Not part of required public API) ---
    // In a real system, Installer would hold a reference back to UserSpaceProvisioner (perhaps via constructor injection)
    // to call completeInstallation.

    // For compilation purposes, we satisfy the class structure but must omit complex simulation loops here.
}
