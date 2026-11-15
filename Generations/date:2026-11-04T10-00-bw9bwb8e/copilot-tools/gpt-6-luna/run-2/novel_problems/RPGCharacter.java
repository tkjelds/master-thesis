@RPGCharacter.ThreadSafe
public final class RPGCharacter {
    @interface ThreadSafe {}

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
            throw new IllegalArgumentException("Name must be non-null and nonempty");
        }
        if (health < 1 || health > 100) {
            throw new IllegalArgumentException("Health must be between 1 and 100");
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
        synchronized (RPGCharacter.class) {
            if (idsExhausted) {
                throw new IllegalStateException("Character ID space exhausted");
            }

            long allocatedId = nextId;
            if (nextId == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextId++;
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

        if (id < other.id) {
            synchronized (this) {
                synchronized (other) {
                    return resolveEncounter(other);
                }
            }
        } else {
            synchronized (other) {
                synchronized (this) {
                    return resolveEncounter(other);
                }
            }
        }
    }

    private EncounterResult resolveEncounter(RPGCharacter other) {
        if (health == 0 || other.health == 0) {
            return new EncounterResult(
                    0,
                    false,
                    snapshotLocked(),
                    other.snapshotLocked());
        }

        int damage = attackPower > other.defense
                ? attackPower - other.defense
                : 0;
        int resultingHealth = damage >= other.health
                ? 0
                : other.health - damage;
        boolean causedDeath = other.health > 0 && resultingHealth == 0;

        ExperienceUpdate update = causedDeath ? calculateExperienceUpdate(10L) : null;

        other.health = resultingHealth;
        if (update != null) {
            applyExperienceUpdate(update);
        }

        return new EncounterResult(
                damage,
                causedDeath,
                snapshotLocked(),
                other.snapshotLocked());
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
        if (amount < 0L) {
            throw new IllegalArgumentException("Experience gain must be nonnegative");
        }

        synchronized (this) {
            if (health == 0) {
                throw new IllegalStateException("A dead character cannot gain experience");
            }

            applyExperienceUpdate(calculateExperienceUpdate(amount));
        }
    }

    private ExperienceUpdate calculateExperienceUpdate(long amount) {
        long levelsGained = amount / 100L;
        long remainderSum = experience + amount % 100L;
        levelsGained += remainderSum / 100L;
        long newExperience = remainderSum % 100L;

        long newLevel = (long) level + levelsGained;
        long newAttackPower = (long) attackPower + levelsGained * 2L;
        long newDefense = (long) defense + levelsGained;

        if (newLevel > Integer.MAX_VALUE
                || newAttackPower > Integer.MAX_VALUE
                || newDefense > Integer.MAX_VALUE) {
            throw new ArithmeticException("Experience gain would overflow character stats");
        }

        return new ExperienceUpdate(
                (int) newLevel,
                (int) newAttackPower,
                (int) newDefense,
                newExperience);
    }

    private void applyExperienceUpdate(ExperienceUpdate update) {
        level = update.level;
        attackPower = update.attackPower;
        defense = update.defense;
        experience = update.experience;
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return snapshotLocked();
        }
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(
                name,
                id,
                health,
                attackPower,
                defense,
                level,
                experience,
                health > 0);
    }

    private static final class ExperienceUpdate {
        private final int level;
        private final int attackPower;
        private final int defense;
        private final long experience;

        private ExperienceUpdate(
                int level,
                int attackPower,
                int defense,
                long experience) {
            this.level = level;
            this.attackPower = attackPower;
            this.defense = defense;
            this.experience = experience;
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
}
