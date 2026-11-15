RPGCharacter {

    // --- Internal State and Constants ---

    private static final int MAX_HEALTH = 100;
    private static final int LEVEL_UP_XP = 100;
    private static final int XP_PER_KILL = 10;

    // Unique ID for deadlock avoidance in encounters
    private final long id;

    // Volatile fields ensure visibility across threads for simple reads/writes
    private volatile String name;
    private volatile int health;
    private volatile long experience;
    private volatile int level;
    private volatile int baseAttackPower; // Attack power derived from starting attributes + levels
    private volatile int defense;

    // Synchronization lock object for all state changes
    private final Object monitor = new Object();

    /**
     * Constructs a new RPGCharacter.
     * @param name The character's name. Must be nonempty.
     * @param initialHealth The starting health (1-100).
     * @param attack The base attack power. Must be nonnegative.
     * @param defense The base defense. Must be nonnegative.
     */
    public RPGCharacter(String name, int initialHealth, int attack, int defense) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Name must be nonempty.");
        }
        if (initialHealth < 1 || initialHealth > 100) {
            throw new IllegalArgumentException("Initial health must be between 1 and 100.");
        }
        if (attack < 0 || defense < 0) {
            throw new IllegalArgumentException("Attack and defense must be nonnegative.");
        }

        // Assign a unique ID. In a real system, this would come from a sequence generator.
        // For self-contained implementation simplicity, we use hashCode combination if needed, 
        // but since this object is instance-specific, its address/identity serves as a unique ID surrogate here.
        // We use System.identityHashCode to simulate a globally unique, stable ID for deadlock ordering.
        this.id = System.identityHashCode(this) + hashCode(); 

        this.name = name;
        this.health = initialHealth;
        this.baseAttackPower = attack; // Initial attribute
        this.defense = defense;
        this.level = 1;
        this.experience = 0L;
    }

    // --- Helper Methods (Internal Consistency Checks) ---

    private boolean isAlive() {
        return this.health > 0;
    }

    /**
     * Calculates the character's current effective attack power.
     * Attack increases by 2 per level gained after level 1 (base level attributes).
     */
    private int getCurrentAttackPower() {
        // Base attack power + (level - 1) * 2 adjustment for levels > 1
        // Since baseAttackPower stores the starting attack value, and subsequent attacks are calculated based on levels:
        return this.baseAttackPower + (this.level - 1) * 2;
    }

    /**
     * Calculates the character's current effective defense.
     */
    private int getCurrentDefense() {
        // Base defense + (level - 1) * 1 adjustment for levels > 1
        return this.defense + (this.level - 1);
    }

    /**
     * Atomically checks if the character is currently alive. Used primarily before critical operations.
     */
    private boolean checkAlive() {
        return this.health > 0;
    }

    // --- Public API Methods ---

    /**
     * Applies an attack against another RPGCharacter. This is a synchronous, atomic encounter simulation.
     * @param other The target character. Must not be null or self.
     * @return An EncounterResult describing the outcome of the attack.
     */
    public EncounterResult attack(RPGCharacter other) {
        if (other == null || this == other) {
            // Reject null targets or self-attacks gracefully based on contract interpretation.
            // Since the result is typed, we return a specific failure state if possible, 
            // but matching the contract requires returning the result object.
            // If we must return a valid EncounterResult, a "no-op" indicates the operation couldn't proceed.
            // Assuming the caller expects a successful structure indicating failure to act:
            return new EncounterResult(null, null, 0, 0, this.getSnapshot(), other.getSnapshot());
        }

        // Deadlock avoidance: Always acquire locks in a canonical order (by ID)
        final RPGCharacter self = this;
        final RPGCharacter target = other;

        // Determine lock order based on unique IDs to prevent circular waiting
        RPGCharacter firstLocker = (self.id < target.id) ? self : target;
        RPGCharacter secondLocker = (self.id < target.id) ? target : self;

        // Acquire locks in canonical order
        synchronized (firstLocker.monitor) {
            synchronized (secondLocker.monitor) {

                // --- Simulation Body ---

                if (!checkAlive() || !target.checkAlive()) {
                    // Either character is dead upon entering the critical section (or was already accounted for)
                    return new EncounterResult(null, null, 0, 0, this.getSnapshot(), target.getSnapshot());
                }

                // 1. Calculate effective stats based on potentially current state (pre-attack)
                final int attackerAttack = getCurrentAttackPower();
                final int defenderDefense = target.getCurrentDefense();

                // 2. Compute damage dealt
                int damageDealt = Math.max(0, attackerAttack - defenderDefense);

                // 3. Apply damage and check for kill
                if (damageDealt > 0) {
                    final int oldHealth = target.health;
                    int newHealth = Math.max(0, target.health - damageDealt);

                    // Track if the transition to zero health occurred during this specific encounter
                    final boolean wasKillerBlow = oldHealth > 0 && newHealth == 0;

                    // Apply health change
                    target.health = newHealth;

                    // 4. Handle XP reward (must happen atomically with health change)
                    if (wasKillerBlow) {
                        // The attacker receives XP if the target died this encounter.
                        gainExperienceInternal(XP_PER_KILL); // This method handles the leveling logic internally
                    }
                } else {
                    // No damage dealt, no XP awarded for this encounter's passage.
                }

                // 5. Generate result objects AFTER all state mutations are complete and successful
                final int damageTakenByTarget = damageDealt; // Target took this much damage in principle
                final int damageTakenBySelf = 0; // Self didn't take damage in this method call path

                // Note: The contract implies the result should describe *this* character's perspective if they were attacked, 
                // but since this method is `this.attack(other)`, we focus on the action taken by 'this'.
                // The result object must contain the full context of the encounter.

                return new EncounterResult(
                    this.name, // Attacker's name (this)
                    target.id, // Target ID
                    damageDealt, 
                    wasKillerBlow ? true : false, 
                    this.getSnapshot(), // Attacker's Post-Encounter Snapshot
                    target.getSnapshot()  // Target's Post-Encounter Snapshot
                );
            }
        }
    }

    /**
     * Attempts to heal the character. Atomically restores health up to 10, capped at MAX_HEALTH.
     * @return The character's resulting health after healing. Returns current health if operation failed/unnecessary.
     */
    public int heal() {
        synchronized (this.monitor) {
            if (!checkAlive()) {
                // Dead characters remain at zero and cannot be resurrected.
                return 0;
            }

            final int currentHealth = this.health;
            // Restore up to 10, capped at MAX_HEALTH
            int newHealth = Math.min(MAX_HEALTH, currentHealth + 10);
            this.health = newHealth;
            return newHealth;
        }
    }

    /**
     * Gains experience for the character. This method is public and must handle potential concurrency issues 
     * regarding lethality (which would have been checked in attack()).
     * @param amount The experience points to gain. Must be nonnegative.
     * @throws IllegalStateException If the character is dead and attempts to gain XP.
     */
    public void gainExperience(long amount) {
        if (amount < 0) return; // Should not happen based on contract, but defensive check.

        synchronized (this.monitor) {
            if (!checkAlive()) {
                throw new IllegalStateException("Dead character cannot gain experience.");
            }
            gainExperienceInternal(amount);
        }
    }

    /**
     * Internal, synchronized method to apply experience and manage level progression.
     * Must be called while holding the monitor lock.
     */
    private void gainExperienceInternal(long amount) {
        if (amount == 0) return;

        this.experience += amount;
        int pointsToAdd = (int) amount; // Since we are only caring about the remainder mod 100 for level-up simulation in this batch application

        // Apply leveling up logic iteratively while points are available
        while (this.experience >= LEVEL_UP_XP) {
            // Consume 100 points and increase level
            this.experience -= LEVEL_UP_XP;
            this.level += 1;

            // Stats increase upon leveling (must happen *after* XP consumption)
            // Leveling adds 2 attack power and 1 defense.
            this.baseAttackPower += 2; // Attack scales additively based on base stat + level adjustments
            this.defense += 1;
        }
    }

    /**
     * Returns an immutable, consistent snapshot of the character's current state. This operation is thread-safe
     * because it reads values under the monitor lock, ensuring atomicity across all fields being read simultaneously.
     * @return A detached snapshot object.
     */
    public Snapshot snapshot() {
        synchronized (this.monitor) {
            // Read all dependent fields atomically under the monitor lock to guarantee consistency 
            // between them (e.g., experience correlates correctly with level).
            return new Snapshot(this);
        }
    }

    // --- Inner/Nested Classes (Required for Contract Compliance) ---

    /**
     * Represents the immutable, detached state of a character at a point in time.
     */
    public class Snapshot {
        public final String name;
        public final long id; // Needed for reciprocal identification in results
        public final int health;
        public final int attackPower; // Effective, current power
        public final int defense;    // Current defense value
        public final int level;
        public final long experience;
        public final boolean alive;

        Snapshot(RPGCharacter character) {
            this.name = character.name;
            this.id = character.id;
            this.health = character.health;
            // Calculate effective stats at the time of snapshot creation for complete context
            this.attackPower = character.getCurrentAttackPower(); 
            this.defense = character.getCurrentDefense();
            this.level = character.level;
            this.experience = character.experience;
            this.alive = character.health > 0;
        }
    }

    /**
     * Represents the result of an encounter. Must contain both post-encounter snapshots.
     */
    public class EncounterResult {
        // Contextual information about the encounter itself
        public final String attackerName; // Name of 'this' character if this was the initiator
        public final long targetId;       // ID of the target in the encounter
        public final int damageDealt;     // Damage inflicted by this character upon target in this encounter
        public final boolean targetDied;   // Did the attack cause death?

        // Post-encounter states (immutable copies)
        public final Snapshot attackerPostEncounter; // Snapshot of 'this' character AFTER the encounter
        public final Snapshot targetPostEncounter;   // Snapshot of the target AFTER the encounter

        // Constructor for attack(RPGCharacter other) usage
        EncounterResult(String attackerName, long targetId, int damageDealt, boolean targetDied, Snapshot afterSelf, Snapshot afterTarget) {
            this.attackerName = attackerName;
            this.targetId = targetId;
            this.damageDealt = damageDealt;
            this.targetDied = targetDied;
            // Crucially, the snapshots captured here must be copies taken *at the moment of encounter conclusion*
            this.attackerPostEncounter = afterSelf; 
            this.targetPostEncounter = afterTarget;
        }
    }
}
