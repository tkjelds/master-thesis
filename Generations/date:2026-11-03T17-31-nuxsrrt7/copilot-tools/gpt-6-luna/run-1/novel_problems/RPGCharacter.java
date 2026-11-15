@RPGCharacter.ThreadSafe
public final class RPGCharacter {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static long nextId = 1L;
    private static boolean idsExhausted;

    private final String name;
    private final long id;

    private int health;
    private int attackPower;
    private int defense;
    private int level;
    private long experience;

    public RPGCharacter(String name, int health, int attackPower, int defense) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("Name must be nonempty");
        }
        if (health < 1 || health > 100) {
            throw new IllegalArgumentException("Health must be in 1..100");
        }
        if (attackPower < 0 || defense < 0) {
            throw new IllegalArgumentException("Attack power and defense must be nonnegative");
        }

        this.name = name;
        this.id = allocateId();
        this.health = health;
        this.attackPower = attackPower;
        this.defense = defense;
        this.level = 1;
        this.experience = 0L;
    }

    private static long allocateId() {
        synchronized (ID_LOCK) {
            if (idsExhausted) {
                throw new IllegalStateException("Character ID space exhausted");
            }

            long allocatedId = nextId;
            if (allocatedId == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextId = allocatedId + 1L;
            }
            return allocatedId;
        }
    }

    public EncounterResult attack(RPGCharacter other) {
        if (other == null) {
            throw new IllegalArgumentException("Target must not be null");
        }
        if (other == this) {
            throw new IllegalArgumentException("A character cannot attack itself");
        }

        RPGCharacter first = id < other.id ? this : other;
        RPGCharacter second = id < other.id ? other : this;

        synchronized (first) {
            synchronized (second) {
                if (health == 0 || other.health == 0) {
                    return new EncounterResult(
                            0,
                            false,
                            snapshotWhileLocked(),
                            other.snapshotWhileLocked());
                }

                long potentialDamage = (long) attackPower - (long) other.defense;
                if (potentialDamage < 0L) {
                    potentialDamage = 0L;
                }
                int appliedDamage = potentialDamage >= other.health
                        ? other.health
                        : (int) potentialDamage;
                int resultingHealth = other.health - appliedDamage;
                boolean causedDeath = other.health > 0 && resultingHealth == 0;

                Progression reward = null;
                if (causedDeath) {
                    reward = calculateProgression(
                            level, experience, attackPower, defense, 10L);
                }

                other.health = resultingHealth;
                if (reward != null) {
                    level = reward.level;
                    experience = reward.experience;
                    attackPower = reward.attackPower;
                    defense = reward.defense;
                }

                return new EncounterResult(
                        appliedDamage,
                        causedDeath,
                        snapshotWhileLocked(),
                        other.snapshotWhileLocked());
            }
        }
    }

    public int heal() {
        synchronized (this) {
            if (health == 0) {
                return 0;
            }
            health = health >= 90 ? 100 : health + 10;
            return health;
        }
    }

    public void gainExperience(long amount) {
        synchronized (this) {
            if (health == 0) {
                throw new IllegalStateException("A dead character cannot gain experience");
            }
            if (amount < 0L) {
                throw new IllegalArgumentException("Experience amount must be nonnegative");
            }

            Progression progression = calculateProgression(
                    level, experience, attackPower, defense, amount);
            level = progression.level;
            experience = progression.experience;
            attackPower = progression.attackPower;
            defense = progression.defense;
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return snapshotWhileLocked();
        }
    }

    private Snapshot snapshotWhileLocked() {
        return new Snapshot(
                name, id, health, attackPower, defense, level, experience);
    }

    private static Progression calculateProgression(
            int currentLevel,
            long currentExperience,
            int currentAttackPower,
            int currentDefense,
            long amount) {
        long levelsGained = amount / 100L;
        long remainingPoints = currentExperience + (amount % 100L);
        levelsGained += remainingPoints / 100L;
        long resultingExperience = remainingPoints % 100L;

        long resultingLevel = (long) currentLevel + levelsGained;
        if (resultingLevel > Integer.MAX_VALUE) {
            throw new ArithmeticException("Level overflow");
        }

        long resultingAttackPower = (long) currentAttackPower + levelsGained * 2L;
        long resultingDefense = (long) currentDefense + levelsGained;
        if (resultingAttackPower > Integer.MAX_VALUE
                || resultingDefense > Integer.MAX_VALUE) {
            throw new ArithmeticException("Attack power or defense overflow");
        }

        return new Progression(
                (int) resultingLevel,
                resultingExperience,
                (int) resultingAttackPower,
                (int) resultingDefense);
    }

    public static final class Snapshot {
        public final String name;
        public final long id;
        public final int health;
        public final int attackPower;
        public final int defense;
        public final int level;
        public final long experience;
        public final boolean alive;

        private Snapshot(
                String name,
                long id,
                int health,
                int attackPower,
                int defense,
                int level,
                long experience) {
            this.name = name;
            this.id = id;
            this.health = health;
            this.attackPower = attackPower;
            this.defense = defense;
            this.level = level;
            this.experience = experience;
            this.alive = health > 0;
        }

        public String getName() {
            return name;
        }

        public long getId() {
            return id;
        }

        public int getHealth() {
            return health;
        }

        public int getAttackPower() {
            return attackPower;
        }

        public int getDefense() {
            return defense;
        }

        public int getLevel() {
            return level;
        }

        public long getExperience() {
            return experience;
        }

        public boolean isAlive() {
            return alive;
        }
    }

    public static final class EncounterResult {
        public final int appliedDamage;
        public final boolean causedDeath;
        public final Snapshot attackerSnapshot;
        public final Snapshot targetSnapshot;

        private EncounterResult(
                int appliedDamage,
                boolean causedDeath,
                Snapshot attackerSnapshot,
                Snapshot targetSnapshot) {
            this.appliedDamage = appliedDamage;
            this.causedDeath = causedDeath;
            this.attackerSnapshot = attackerSnapshot;
            this.targetSnapshot = targetSnapshot;
        }

        public int getAppliedDamage() {
            return appliedDamage;
        }

        public boolean isCausedDeath() {
            return causedDeath;
        }

        public Snapshot getAttackerSnapshot() {
            return attackerSnapshot;
        }

        public Snapshot getTargetSnapshot() {
            return targetSnapshot;
        }
    }

    private static final class Progression {
        private final int level;
        private final long experience;
        private final int attackPower;
        private final int defense;

        private Progression(int level, long experience, int attackPower, int defense) {
            this.level = level;
            this.experience = experience;
            this.attackPower = attackPower;
            this.defense = defense;
        }
    }
}
