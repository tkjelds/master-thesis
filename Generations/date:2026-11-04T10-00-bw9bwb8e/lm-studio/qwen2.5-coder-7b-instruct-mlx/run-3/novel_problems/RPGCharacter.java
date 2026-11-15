@RPGCharacter.ThreadSafe
class RPGCharacter {
    @interface ThreadSafe {}

    private final String name;
    private final long ID;
    private volatile int health;
    private volatile int attackPower;
    private volatile int defense;
    private volatile int level = 1;
    private volatile long experience;
    private final Object lock = new Object();
    private boolean alive = true;

    static private long nextID = 0;
    static private final Object IDLock = new Object();

    public RPGCharacter(String name, int health, int attackPower) {
        if (name == null || health < 1 || health > 100 || attackPower < 0) {
            throw new IllegalArgumentException("Invalid parameters");
        }
        this.name = name;
        synchronized (IDLock) {
            this.ID = nextID++;
        }
        this.health = health;
        this.attackPower = attackPower;
    }

    public RPGCharacter snapshot() {
        return new RPGCharacter(name, ID, health, attackPower, defense, level, experience, alive);
    }

    public Encounter attack(RPGCharacter other) {
        if (other == null || this == other) {
            throw new IllegalArgumentException("Invalid target");
        }
        synchronized (lock) {
            if (!alive || !other.alive) {
                return new Encounter(0, false, this.snapshot(), other.snapshot());
            }
        }

        int damage = Math.max(0, attackPower - other.defense);
        synchronized (other.lock) {
            synchronized (lock) {
                if (!alive || !other.alive) {
                    return new Encounter(0, false, this.snapshot(), other.snapshot());
                }
                int newHealth = Math.max(other.health - damage, 0);
                other.health = newHealth;
                if (newHealth == 0) {
                    giveExperience(other, 10);
                }
            }
        }

        return new Encounter(damage, other.health == 0, this.snapshot(), other.snapshot());
    }

    public void heal() {
        synchronized (lock) {
            if (!alive) {
                return;
            }
            int newHealth = Math.min(100, health + 10);
           .health = newHealth;
        }
    }

    public void gainExperience(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Invalid experience amount");
        }
        synchronized (lock) {
            if (!alive) {
                throw new IllegalStateException("Character is dead");
            }
            experience += amount;
            while (experience >= 100) {
                level++;
                attackPower += 2;
                defense += 1;
                experience -= 100;
            }
        }
    }

    private void giveExperience(RPGCharacter character, int amount) {
        synchronized (character.lock) {
            if (!character.alive) {
                return;
            }
            character.gainExperience(amount);
        }
    }

    private static class Encounter {
        final int damage;
        final boolean isDeath;
        final RPGCharacter attackerSnapshot;
        final RPGCharacter defenderSnapshot;

        Encounter(int damage, boolean isDeath, RPGCharacter attackerSnapshot, RPGCharacter defenderSnapshot) {
            this.damage = damage;
            this.isDeath = isDeath;
            this.attackerSnapshot = attackerSnapshot;
            this.defenderSnapshot = defenderSnapshot;
        }
    }

    private class RPGCharacter {
        final String name;
        final long ID;
        final int health;
        final int attackPower;
        final int defense;
        final int level;
        final long experience;
        final boolean alive;

        RPGCharacter(String name, long ID, int health, int attackPower, int defense, int level, long experience, boolean alive) {
            this.name = name;
            this.ID = ID;
            this.health = health;
            this.attackPower = attackPower;
            this.defense = defense;
            this.level = level;
            this.experience = experience;
            this.alive = alive;
        }
    }
}
