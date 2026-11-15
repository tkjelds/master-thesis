@RPGCharacter.ThreadSafe
public final class RPGCharacter {
    @interface ThreadSafe {}

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
        synchronized (RPGCharacter.class) {
            if (idsExhausted) {
                throw new IllegalStateException("No character IDs remain");
            }

            long allocated = nextId;
            if (nextId == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextId++;
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
                    return new EncounterResult(
                            0, false, snapshotWhileLocked(), other.snapshotWhileLocked());
                }

                long difference = (long) this.attackPower - (long) other.defense;
                int damage = difference <= 0L ? 0 : (difference > other.health
                        ? other.health : (int) difference);
                int resultingHealth = other.health - damage;
                boolean causedDeath = other.health > 0 && resultingHealth == 0;

                Progression reward = null;
                if (causedDeath) {
                    reward = calculateProgression(
                            this.level, this.experience, this.attackPower, this.defense, 10L);
                }

                if (reward != null) {
                    this.level = reward.level;
                    this.experience = reward.experience;
                    this.attackPower = reward.attackPower;
                    this.defense = reward.defense;
                }
                other.health = resultingHealth;

                return new EncounterResult(
                        damage, causedDeath, snapshotWhileLocked(), other.snapshotWhileLocked());
            }
        }
    }

    public int heal() {
        synchronized (this) {
            if (health == 0) {
                return 0;
            }
            int restored = health + 10;
            health = restored > 100 ? 100 : restored;
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
            return snapshotWhileLocked();
        }
    }

    private Snapshot snapshotWhileLocked() {
        return new Snapshot(name, id, health, attackPower, defense,
                level, experience, health > 0);
    }

    private static Progression calculateProgression(
            int currentLevel,
            long currentExperience,
            int currentAttackPower,
            int currentDefense,
            long amount) {
        long levelsGained = amount / 100L;
        long remainder = currentExperience + (amount % 100L);

        if (remainder >= 100L) {
            levelsGained++;
            remainder -= 100L;
        }

        long newLevel = (long) currentLevel + levelsGained;
        long newAttackPower = (long) currentAttackPower + levelsGained * 2L;
        long newDefense = (long) currentDefense + levelsGained;

        if (newLevel > Integer.MAX_VALUE
                || newAttackPower > Integer.MAX_VALUE
                || newDefense > Integer.MAX_VALUE) {
            throw new IllegalStateException("Experience would overflow character attributes");
        }

        return new Progression(
                (int) newLevel, remainder, (int) newAttackPower, (int) newDefense);
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

        private Snapshot(String name, long id, int health, int attackPower,
                         int defense, int level, long experience, boolean alive) {
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

    public static final class EncounterResult {
        public final int damage;
        public final boolean causedDeath;
        public final Snapshot attacker;
        public final Snapshot target;

        private EncounterResult(int damage, boolean causedDeath,
                                Snapshot attacker, Snapshot target) {
            this.damage = damage;
            this.causedDeath = causedDeath;
            this.attacker = attacker;
            this.target = target;
        }
    }
}
