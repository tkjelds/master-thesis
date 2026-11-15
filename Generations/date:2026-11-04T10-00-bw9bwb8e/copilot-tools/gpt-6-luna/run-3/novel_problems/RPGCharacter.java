@RPGCharacter.ThreadSafe
public class RPGCharacter {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static long nextId = 1L;
    private static boolean idsExhausted;

    private final long id;
    private final String name;
    private int health;
    private int attackPower;
    private int defense;
    private int level;
    private long experience;

    public RPGCharacter(String name, int health, int attackPower, int defense) {
        if (name == null) {
            throw new NullPointerException("name");
        }
        if (name.length() == 0) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (health < 1 || health > 100) {
            throw new IllegalArgumentException("health must be in 1..100");
        }
        if (attackPower < 0) {
            throw new IllegalArgumentException("attackPower must be nonnegative");
        }
        if (defense < 0) {
            throw new IllegalArgumentException("defense must be nonnegative");
        }

        synchronized (ID_LOCK) {
            if (idsExhausted) {
                throw new IllegalStateException("character IDs exhausted");
            }
            this.id = nextId;
            if (nextId == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextId++;
            }
        }

        this.name = name;
        this.health = health;
        this.attackPower = attackPower;
        this.defense = defense;
        this.level = 1;
        this.experience = 0L;
    }

    public EncounterResult attack(RPGCharacter other) {
        if (other == null) {
            throw new NullPointerException("other");
        }
        if (other == this) {
            throw new IllegalArgumentException("a character cannot attack itself");
        }

        if (id < other.id) {
            synchronized (this) {
                synchronized (other) {
                    return encounterLocked(other);
                }
            }
        } else {
            synchronized (other) {
                synchronized (this) {
                    return encounterLocked(other);
                }
            }
        }
    }

    public int heal() {
        synchronized (this) {
            if (health == 0) {
                return 0;
            }
            health = health > 90 ? 100 : health + 10;
            return health;
        }
    }

    public void gainExperience(long amount) {
        synchronized (this) {
            if (health == 0) {
                throw new IllegalStateException("a dead character cannot gain experience");
            }
            if (amount < 0L) {
                throw new IllegalArgumentException("amount must be nonnegative");
            }
            applyProgression(prepareProgression(amount));
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return snapshotLocked();
        }
    }

    private EncounterResult encounterLocked(RPGCharacter other) {
        if (health == 0 || other.health == 0) {
            return new EncounterResult(0, false, snapshotLocked(), other.snapshotLocked());
        }

        int potentialDamage = attackPower > other.defense
                ? attackPower - other.defense
                : 0;
        int appliedDamage = potentialDamage < other.health ? potentialDamage : other.health;
        int resultingHealth = other.health - appliedDamage;
        boolean causedDeath = other.health > 0 && resultingHealth == 0;

        Progression progression = causedDeath ? prepareProgression(10L) : null;

        other.health = resultingHealth;
        if (progression != null) {
            applyProgression(progression);
        }

        return new EncounterResult(
                appliedDamage,
                causedDeath,
                snapshotLocked(),
                other.snapshotLocked());
    }

    private Progression prepareProgression(long amount) {
        if (amount > Long.MAX_VALUE - experience) {
            throw new ArithmeticException("experience overflow");
        }
        long totalExperience = experience + amount;
        long levelsGained = totalExperience / 100L;

        if (levelsGained > Integer.MAX_VALUE - (long) level) {
            throw new ArithmeticException("level overflow");
        }
        if (levelsGained > (Integer.MAX_VALUE - (long) attackPower) / 2L) {
            throw new ArithmeticException("attack power overflow");
        }
        if (levelsGained > Integer.MAX_VALUE - (long) defense) {
            throw new ArithmeticException("defense overflow");
        }

        return new Progression(
                level + (int) levelsGained,
                attackPower + (int) (levelsGained * 2L),
                defense + (int) levelsGained,
                totalExperience % 100L);
    }

    private void applyProgression(Progression progression) {
        level = progression.level;
        attackPower = progression.attackPower;
        defense = progression.defense;
        experience = progression.experience;
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

    private static final class Progression {
        private final int level;
        private final int attackPower;
        private final int defense;
        private final long experience;

        private Progression(int level, int attackPower, int defense, long experience) {
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
    }

    public static final class EncounterResult {
        public final int damage;
        public final boolean causedDeath;
        public final Snapshot attacker;
        public final Snapshot target;

        private EncounterResult(
                int damage,
                boolean causedDeath,
                Snapshot attacker,
                Snapshot target) {
            this.damage = damage;
            this.causedDeath = causedDeath;
            this.attacker = attacker;
            this.target = target;
        }
    }
}
