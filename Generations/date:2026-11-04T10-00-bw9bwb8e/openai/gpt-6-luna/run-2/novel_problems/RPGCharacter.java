@RPGCharacter.ThreadSafe
public final class RPGCharacter {
    @interface ThreadSafe {}

    public static final int MAX_HEALTH = 100;

    private static final Object ID_LOCK = new Object();
    private static long nextId = 1L;
    private static boolean idsExhausted = false;

    private final String name;
    private final long id;

    private int health;
    private int attackPower;
    private int defense;
    private int level;
    private long experience;

    public RPGCharacter(String name, int health, int attackPower, int defense) {
        if (name == null) {
            throw new IllegalArgumentException("Name must not be null");
        }
        if (name.length() == 0) {
            throw new IllegalArgumentException("Name must not be empty");
        }
        if (health < 1 || health > MAX_HEALTH) {
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
                throw new IllegalStateException("Character IDs are exhausted");
            }

            long allocated = nextId;
            if (allocated == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextId = allocated + 1L;
            }
            return allocated;
        }
    }

    public EncounterResult attack(RPGCharacter other) {
        if (other == null) {
            throw new IllegalArgumentException("Target must not be null");
        }
        if (other == this) {
            throw new IllegalArgumentException("A character cannot attack itself");
        }

        RPGCharacter first = this.id < other.id ? this : other;
        RPGCharacter second = this.id < other.id ? other : this;

        synchronized (first) {
            synchronized (second) {
                if (this.health == 0 || other.health == 0) {
                    Snapshot attackerSnapshot = snapshotLocked(
                            this.health, this.attackPower, this.defense,
                            this.level, this.experience);
                    Snapshot targetSnapshot = other.snapshotLocked(
                            other.health, other.attackPower, other.defense,
                            other.level, other.experience);
                    return new EncounterResult(0, false, attackerSnapshot, targetSnapshot);
                }

                int rawDamage = this.attackPower > other.defense
                        ? this.attackPower - other.defense
                        : 0;
                int appliedDamage = rawDamage < other.health ? rawDamage : other.health;
                int resultingTargetHealth = other.health - appliedDamage;
                boolean causedDeath = other.health > 0 && resultingTargetHealth == 0;

                Progression attackerProgression = causedDeath
                        ? calculateProgression(
                                this.level, this.experience,
                                this.attackPower, this.defense, 10L)
                        : new Progression(
                                this.level, this.experience,
                                this.attackPower, this.defense);

                Snapshot attackerSnapshot = snapshotLocked(
                        this.health,
                        attackerProgression.attackPower,
                        attackerProgression.defense,
                        attackerProgression.level,
                        attackerProgression.experience);
                Snapshot targetSnapshot = other.snapshotLocked(
                        resultingTargetHealth,
                        other.attackPower,
                        other.defense,
                        other.level,
                        other.experience);
                EncounterResult result = new EncounterResult(
                        appliedDamage, causedDeath, attackerSnapshot, targetSnapshot);

                if (causedDeath) {
                    this.level = attackerProgression.level;
                    this.experience = attackerProgression.experience;
                    this.attackPower = attackerProgression.attackPower;
                    this.defense = attackerProgression.defense;
                }
                other.health = resultingTargetHealth;

                return result;
            }
        }
    }

    public int heal() {
        synchronized (this) {
            if (health == 0) {
                return 0;
            }
            int restoredHealth = health + 10;
            health = restoredHealth > MAX_HEALTH ? MAX_HEALTH : restoredHealth;
            return health;
        }
    }

    public void gainExperience(long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException("Experience amount must be nonnegative");
        }

        synchronized (this) {
            if (health == 0) {
                throw new IllegalStateException("A dead character cannot gain experience");
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
            return snapshotLocked(health, attackPower, defense, level, experience);
        }
    }

    private Snapshot snapshotLocked(
            int snapshotHealth,
            int snapshotAttackPower,
            int snapshotDefense,
            int snapshotLevel,
            long snapshotExperience) {
        return new Snapshot(
                name, id, snapshotHealth, snapshotAttackPower, snapshotDefense,
                snapshotLevel, snapshotExperience, snapshotHealth > 0);
    }

    private static Progression calculateProgression(
            int currentLevel,
            long currentExperience,
            int currentAttackPower,
            int currentDefense,
            long amount) {
        long levelsGained = amount / 100L;
        long remainderSum = amount % 100L + currentExperience;
        if (remainderSum >= 100L) {
            levelsGained++;
        }
        long resultingExperience = remainderSum % 100L;

        long resultingLevel = (long) currentLevel + levelsGained;
        long resultingAttackPower = (long) currentAttackPower + 2L * levelsGained;
        long resultingDefense = (long) currentDefense + levelsGained;

        if (resultingLevel > Integer.MAX_VALUE
                || resultingAttackPower > Integer.MAX_VALUE
                || resultingDefense > Integer.MAX_VALUE) {
            throw new ArithmeticException("Experience progression overflows character attributes");
        }

        return new Progression(
                (int) resultingLevel,
                resultingExperience,
                (int) resultingAttackPower,
                (int) resultingDefense);
    }

    private static final class Progression {
        final int level;
        final long experience;
        final int attackPower;
        final int defense;

        Progression(int level, long experience, int attackPower, int defense) {
            this.level = level;
            this.experience = experience;
            this.attackPower = attackPower;
            this.defense = defense;
        }
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
                long experience,
                boolean alive) {
            this.name = name;
            this.id = id;
            this.health = health;
            this.attackPower = attackPower;
            this.defense = defense;
            this.level = level;
            this.experience = experience;
            this.alive = alive;
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
        public final int damage;
        public final boolean causedDeath;
        public final Snapshot attackerSnapshot;
        public final Snapshot targetSnapshot;

        private EncounterResult(
                int damage,
                boolean causedDeath,
                Snapshot attackerSnapshot,
                Snapshot targetSnapshot) {
            this.damage = damage;
            this.causedDeath = causedDeath;
            this.attackerSnapshot = attackerSnapshot;
            this.targetSnapshot = targetSnapshot;
        }

        public int getDamage() {
            return damage;
        }

        public boolean causedDeath() {
            return causedDeath;
        }

        public Snapshot getAttackerSnapshot() {
            return attackerSnapshot;
        }

        public Snapshot getTargetSnapshot() {
            return targetSnapshot;
        }
    }
}
