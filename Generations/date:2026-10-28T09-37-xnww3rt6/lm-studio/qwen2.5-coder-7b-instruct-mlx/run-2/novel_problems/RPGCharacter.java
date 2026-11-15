@RPGCharacter.ThreadSafe
class RPGCharacter {
    @interface ThreadSafe {}

    private final String name;
    private final int id;
    private volatile long health;
    private volatile int attackPower;
    private volatile int defense;
    private int level;
    private long experience;
    private static final int MAX_HEALTH = 100;
    private static long nextId = 0;

    public RPGCharacter(String name, int health, int attackPower, int defense) {
        if (name == null || health <= 0 || attackPower < 0 || defense < 0) {
            throw new IllegalArgumentException("Invalid arguments");
        }
        this.name = name;
        this.id = (int) nextId++;
        this.health = health;
        this.attackPower = attackPower;
        this.defense = defense;
        this.level = 1;
    }

    public EncounterResult attack(RPGCharacter other) {
        synchronized (this) {
            if (other == null || this == other || health <= 0 || other.health <= 0) {
                return new EncounterResult(this, other);
            }
        }

        int damage = Math.max(0, attackPower - other.defense);

        synchronized (other) {
            if (this.health <= 0 || other.health <= 0) {
                return new EncounterResult(this, other);
            }

            long otherHealth = Math.max(0, other.health - damage);

            if (otherHealth == 0) {
                synchronized (this) {
                    experience += 10;
                    if (experience >= level * 100L) {
                        experience -= level * 100L;
                        attackPower += 2 * level;
                        defense += level;
                        level++;
                    }
                }
            }

            other.health = otherHealth;

            return new EncounterResult(this, other);
        }
    }

    public synchronized void heal() {
        if (health == 0) {
            return;
        }
        health = Math.min(MAX_HEALTH, health + 10);
    }

    public synchronized void gainExperience(long amount) {
        if (health <= 0) {
            throw new IllegalStateException("Character is dead");
        }
        experience += amount;
        if (experience >= level * 100L) {
            experience -= level * 100L;
            attackPower += 2 * level;
            defense += level;
            level++;
        }
    }

    public EncounterResult snapshot() {
        synchronized (this) {
            return new EncounterResult(this, null);
        }
    }

    private static class EncounterResult {
        private final RPGCharacter attacker;
        private final RPGCharacter defender;

        public EncounterResult(RPGCharacter attacker, RPGCharacter defender) {
            this.attacker = attacker;
            this.defender = defender;
        }

        public RPGCharacter getAttacker() {
            return attacker;
        }

        public RPGCharacter getDefender() {
            return defender;
        }
    }
}
