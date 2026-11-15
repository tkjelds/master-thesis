import java.time.Instant;

@RPGCharacter.ThreadSafe
class RPGCharacter {
    @interface ThreadSafe {}

    private final String name;
    private final long id = System.nanoTime(); // Immutable globally unique character ID
    private volatile int health;
    private final int initialAttackPower;
    private final int initialDefense;
    private volatile int attackPower;
    private volatile int defense;
    private volatile long experience;
    private volatile boolean alive;

    public RPGCharacter(String name, int health, int attackPower, int defense) {
        if (name == null || name.isEmpty() || health <= 0 || attackPower < 0 || defense < 0) {
            throw new IllegalArgumentException("Invalid parameters");
        }
        this.name = name;
        this.health = health;
        this.initialAttackPower = attackPower;
        this.attackPower = attackPower;
        this.initialDefense = defense;
        this.defense = defense;
        this.experience = 0;
        this.alive = true;
    }

    public EncounterResult attack(RPGCharacter other) {
        if (name == null || other == null || this == other || !this.alive || !other.alive) {
            return new EncounterResult(null, false, this.snapshot(), other.snapshot());
        }

        synchronized (this) {
            if (!this.alive || !other.alive) {
                return new EncounterResult(null, false, this.snapshot(), other.snapshot());
            }

            int damage = Math.max(0, attackPower - other.defense);
            if (damage > 0) {
                other.health -= damage;
                if (other.health < 0) {
                    other.health = 0;
                    this.gainExperience(10);
                }
            }

            if (other.health == 0) {
                EncounterResult result = new EncounterResult(new DamageSnapshot(other.name, damage), true, this.snapshot(), other.snapshot());
                updateStats();
                return result;
            } else {
                EncounterResult result = new EncounterResult(new DamageSnapshot(other.name, damage), false, this.snapshot(), other.snapshot());
                updateStats();
                return result;
            }
        }
    }

    private void gainExperience(long amount) {
        synchronized (this) {
            long newExperience = experience + amount;
            int levelsGained = (int) (newExperience / 100);
            if (levelsGained > 0) {
                levelUp(levelsGained * 100);
            }
        }
    }

    private void levelUp(int points) {
        synchronized (this) {
            experience -= points;
            attackPower += 2 * levelsGained;
            defense += levelsGained;
        }
    }

    public synchronized int heal() {
        if (!this.alive) {
            throw new IllegalStateException("Character is dead");
        }

        int healthRestored = Math.min(10, 100 - this.health);
        this.health += healthRestored;
        return this.health;
    }

    private void updateStats() {
        experience += level * 100;
        attackPower = initialAttackPower + (level - 1) * 2;
        defense = initialDefense + (level - 1);
    }

    public Snapshot snapshot() {
        return new Snapshot(name, id, health, attackPower, defense, level, experience, alive);
    }

    private int level;
    private class Snapshot {
        public final String name;
        public final long id;
        public final int health;
        public final int attackPower;
        public final int defense;
        public final int level;
        public final long experience;
        public final boolean alive;

        public Snapshot(String name, long id, int health, int attackPower, int defense, int level, long experience, boolean alive) {
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
                    ", id=" + id +
                    ", health=" + health +
                    ", attackPower=" + attackPower +
                    ", defense=" + defense +
                    ", level=" + level +
                    ", experience=" + experience +
                    ", alive=" + alive +
                    '}';
        }
    }

    private class DamageSnapshot {
        public final String name;
        public final int damage;

        public DamageSnapshot(String name, int damage) {
            this.name = name;
            this.damage = damage;
        }

        @Override
        public String toString() {
            return "DamageSnapshot{" +
                    "name='" + name + '\'' +
                    ", damage=" + damage +
                    '}';
        }
    }

    private class EncounterResult {
        public final DamageSnapshot result;
        public final boolean isDead;
        public final Snapshot attackerSnapshot;
        public final Snapshot otherSnapshot;

        public EncounterResult(DamageSnapshot result, boolean isDead, Snapshot attackerSnapshot, Snapshot otherSnapshot) {
            this.result = result;
            this.isDead = isDead;
            this.attackerSnapshot = attackerSnapshot;
            this.otherSnapshot = otherSnapshot;
        }

        @Override
        public String toString() {
            return "EncounterResult{" +
                    "result=" + result +
                    ", isDead=" + isDead +
                    ", attackerSnapshot=" + attackerSnapshot +
                    ", otherSnapshot=" + otherSnapshot +
                    '}';
        }
    }

    @Override
    public String toString() {
        return "RPGCharacter{" +
                "name='" + name + '\'' +
                ", id=" + id +
                ", health=" + health +
                ", attackPower=" + attackPower +
                ", defense=" + defense +
                ", level=" + (level != 0 ? level : "N/A") +
                ", experience=" + experience +
                ", alive=" + alive +
                '}';
    }
}
