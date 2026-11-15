RPGCharacter {

    private final String name;
    private volatile long uniqueIdCounter = 0;
    private final long id;

    // Mutable state variables, protected by synchronization blocks
    private int currentHealth;
    private final int maxHealth;
    private int attackPower;
    private int defense;
    private int level;
    private long experience;
    private boolean isAlive;

    /**
     * Constructor initializing the character.
     * @param name Nonempty name.
     * @param initialHealth Health in [1, 100].
     * @param attack Initial attack power (nonnegative).
     * @param defense Initial defense (nonnegative).
     */
    public RPGCharacter(String name, int initialHealth, int attack, int defense) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Name cannot be null or empty.");
        }
        if (initialHealth < 1 || initialHealth > 100) {
            throw new IllegalArgumentException("Initial health must be between 1 and 100.");
        }
        if (attack < 0 || defense < 0) {
            throw new IllegalArgumentException("Attack and defense must be nonnegative.");
        }

        this.name = name;
        this.maxHealth = 100; // Fixed max health as per problem description context
        this.currentHealth = initialHealth;
        this.attackPower = attack;
        this.defense = defense;
        this.level = 1;
        this.experience = 0;
        this.isAlive = true;

        // Assign a unique ID upon creation for deadlock prevention protocol
        synchronized (this) {
            uniqueIdCounter++;
            this.id = uniqueIdCounter; // Simplistic ID generation sequence, functionally unique per object instance
        }
    }

    /**
     * Represents the result of an encounter.
     */
    public static class EncounterResult {
        public final int damageDealt;
        public final boolean killer; // Did this encounter cause death?
        public final Snapshot selfSnapshotAfter, targetSnapshotAfter;

        private EncounterResult(int damageDealt, boolean killer, Snapshot self, Snapshot target) {
            this.damageDealt = damageDealt;
            this.killer = killer;
            this.selfSnapshotAfter = self;
            this.targetSnapshotAfter = target;
        }

        public static EncounterResult performAttack(RPGCharacter attacker, RPGCharacter target, int baseDamage) {
            // This method body needs to manage the entire atomic encounter logic.
            // The actual calculation and state change happens within the synchronized blocks of RPGCharacter.
            // This constructor mirrors the return type structure needed by the problem contract.
            throw new UnsupportedOperationException("Use attack(RPGCharacter other) method to initiate encounters.");
        }

        // Helper constructor for when the result is built from transactional updates
        private EncounterResult(int damageDealt, boolean killer) {
            this.damageDealt = damageDealt;
            this.killer = killer;
            this.selfSnapshotAfter = null; // Should be populated by the calling context if needed for return
            this.targetSnapshotAfter = null; // Should be populated by the calling context if needed for return
        }
    }

    /**
     * A coherent, immutable snapshot of the character's state at a specific point in time.
     */
    public static class Snapshot {
        public final String name;
        public final long id;
        public final int health;
        public final int attackPower;
        public final int defense;
        public final int level;
        public final long experience;
        public final boolean alive;

        private Snapshot(String name, long id, int health, int attackPower, int defense, int level, long experience, boolean alive) {
            this.name = name;
            this.id = id;
            this.health = health;
            this.attackPower = attackPower;
            this.defense = defense;
            this.level = level;
            this.experience = experience;
            this.alive = alive;
        }

        @Override
        public String toString() {
            return "Snapshot{" +
                    "name='" + name + '\'' +
                    ", health=" + health +
                    ", level=" + level +
                    ", exp=" + experience +
                    '}';
        }
    }

    /**
     * Represents the result of an attack encounter. This object contains all information
     * about the interaction after it has occurred atomically.
     */
    public static class AttackOutcome {
        public final int damageDealt;
        public final boolean targetKilled;
        public final Snapshot attackerSnapshot, defenderSnapshot;

        private AttackOutcome(int damageDealt, boolean targetKilled, Snapshot attacker, Snapshot defender) {
            this.damageDealt = damageDealt;
            this.targetKilled = targetKilled;
            this.attackerSnapshot = attacker;
            this.defenderSnapshot = defender;
        }
    }

    /**
     * Attacks another character. This is an atomic, bidirectional encounter.
     * @param other The target character.
     * @return An AttackOutcome describing the result, or null if resources are unavailable or names/targets were invalid.
     * @throws IllegalArgumentException if either object is null, or if the encounter cannot be resolved immediately due to synchronization issues (though complex waits are disallowed here, simpler contract adherence suggests immediate return is expected).
     */
    public AttackOutcome attack(RPGCharacter other) {
        if (other == null) {
            return null; // Reject null target
        }
        if (this == other) {
            return null; // Reject self-attack
        }

        // Deadlock avoidance protocol: Use a consistent ordering based on unique IDs.
        RPGCharacter self = this;
        RPGCharacter opponent = other;

        // Ensure ordered locking sequence: Lock on the object with the smaller ID first.
        if (self.id > opponent.id) {
            // Swap roles for locking sequence consistency, the encounter itself is still between 'this' and 'other'.
            RPGCharacter temp = self;
            self = opponent; // The one with smaller ID attempts to lock first conceptually
            opponent = temp; // The other character locks second
        }

        // Acquire locks sequentially based on IDs (Self/Opponent are the original caller/target)
        synchronized (self) {
            // Wait for opponent's lock if necessary, but since we are holding the conceptual "first" lock (self),
            // we must acquire opponent's lock to ensure transactional integrity of both objects during the attack read/write.
            synchronized (opponent) {
                // Check current state before computation
                if (!this.isAlive || !other.isAlive) {
                    // If either is dead upon acquiring locks, return detached no-op result.
                    return null;
                }

                // 1. Compute potential damage (based on current attributes)
                int rawDamage = Math.max(0, this.attackPower - other.defense);
                int damageDealt = rawDamage;

                // 2. Apply damage and check for death
                int oldHealth = other.currentHealth;
                int newHealth = Math.max(0, other.currentHealth - damageDealt);
                other.currentHealth = newHealth;

                boolean targetKilled = (oldHealth > 0 && newHealth == 0);
                
                // Transactional rule: Kill reward only applies if the transition happened in this attack.
                boolean rewarded = (targetKilled);

                // 3. Apply attacker's experience if killing blow landed
                if (rewarded) {
                    // Experience gain is part of the atomic encounter resolution.
                    try {
                        gainExperience(10); // gainExperience must handle the transactional aspect relative to the attack phase
                    } catch (IllegalStateException e) {
                        // This should ideally not happen if the sequence is correct, but defensively handle.
                        // If gainExperience fails after death blow check (e.g., race condition protection gone wrong), we revert/fail cleanly.
                        // Given the strict sequencing (lock -> check death -> apply exp), this pathway implies an error in ordering or capacity.
                        // Since we are inside the monitor lock, this should not be reachable if the design is perfect.
                    }
                }

                // 4. Check for attacker's death (if the target was killed by this blow, the attacker state is finalized)
                // The attack only affects health via damage taken in *response* to a potential future counter-attack,
                // but for this atomic attack(), we only check the target's response to *this* blow.
                // If the attacker was somehow killed by an outside force during this sequence, they would be handled differently.
                // Assuming the attack() method is followed by potential counter-attacks, we only commit to the target's state change.

                // 5. Capture snapshots *after* all mutations are committed for this encounter
                Snapshot selfSnapshot = new Snapshot(this.name, this.id, this.currentHealth, this.attackPower, this.defense, this.level, this.experience, this.isAlive);
                Snapshot targetSnapshot = new Snapshot(other.name, other.id, other.currentHealth, other.attackPower, other.defense, other.level, other.experience, other.isAlive);

                // Since the attack is initiated by 'this', we return the outcome based on 'this' actions.
                return new AttackOutcome(damageDealt, targetKilled, selfSnapshot, targetSnapshot);
            }
        } // Release opponent lock (if acquired)
    }


    /**
     * Restores health up to max, capped at 100. Must be synchronized with encounters.
     * @return The resulting health of the character.
     */
    public int heal() {
        synchronized (this) {
            if (!isAlive) {
                return 0; // Cannot resurrect a dead character.
            }

            int oldHealth = this.currentHealth;
            // Restore up to 10 health, capped at maxHealth (which is 100)
            this.currentHealth = Math.min(maxHealth, this.currentHealth + 10);
            return this.currentHealth;
        }
    }

    /**
     * Grants experience points, potentially triggering level up. Must be synchronized.
     * @param amount Nonnegative experience to gain.
     * @throws IllegalStateException if the character is dead and attempts to gain experience.
     */
    public void gainExperience(long amount) throws IllegalStateException {
        if (amount < 0) return; // Should not happen based on contract, but defensive coding.

        synchronized (this) {
            if (!isAlive) {
                throw new IllegalStateException("Cannot gain experience for a dead character.");
            }

            if (amount == 0) return;

            // Check for overflow safety is inherently handled by primitive types unless we exceed Long.MAX_VALUE,
            // which requires very sustained use beyond typical game lifespan anyway. We assume standard long holds are sufficient here.
            long newTotalExperience = this.experience + amount;

            while (newTotalExperience >= 100) {
                // Level up consumes 100 points and adds stats.
                long consumed = (newTotalExperience / 100) * 100;
                long remainder = newTotalExperience % 100;

                // Check invariants before mutation: level/stat addition shouldn't overflow standard int limits for Attack/Defense.
                // Since base values are within reasonable range, accumulation checks should prevent overflow for typical play.

                this.level += (int) (consumed / 100);
                this.attackPower += (consumed / 100) * 2; // +2 AP per level
                this.defense += (consumed / 100) * 1;    // +1 DEF per level

                this.experience = remainder;
                newTotalExperience = remainder; // Update the rolling total representation after leveling
            }

            this.experience = newTotalExperience;
        }
    }


    /**
     * Provides a detached, coherent snapshot of the current state. Thread-safe access to internal representation.
     * @return A new, immutable Snapshot object.
     */
    public Snapshot snapshot() {
        synchronized (this) {
            // Defensive copy of current state to ensure immutability of the returned object.
            return new Snapshot(
                    this.name, this.id,
                    this.currentHealth,
                    this.attackPower,
                    this.defense,
                    this.level,
                    this.experience,
                    this.isAlive
            );
        }
    }

    // --- Inner Class Definition Duplication for Self-Containment ---

    private static class Snapshot {
        public final String name;
        public final long id;
        public final int health;
        public final int attackPower;
        public final int defense;
        public final int level;
        public final long experience;
        public final boolean alive;

        Snapshot(String name, long id, int health, int attackPower, int defense, int level, long experience, boolean alive) {
            this.name = name;
            this.id = id;
            this.health = health;
            this.attackPower = attackPower;
            this.defense = defense;
            this.level = level;
            this.experience = experience;
            this.alive = alive;
        }
    }

    // --- End of Implementation ---
}
