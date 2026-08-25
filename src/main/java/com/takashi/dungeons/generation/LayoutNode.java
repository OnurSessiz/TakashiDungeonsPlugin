package com.takashi.dungeons.generation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Yerleşime kabul edilmiş bir oda — geometrisi + kapılarının graf durumu.
 *
 * <p><b>Neden {@link PlacedRoom}'dan ayrı:</b> {@code PlacedRoom} değişmez saf geometri ve
 * yerleştirme <i>kabul edilmeden önce</i> hesaplanıyor (çakışma testi için kutusu lazım).
 * Kapıların bağlı/boş/ölü durumu ise kabulden <i>sonra</i> doğuyor ve zamanla değişiyor.
 * İkisini tek nesnede birleştirmek "yarı kurulmuş oda" hâli üretirdi — reddedilen bir
 * adayın da kapı durumu olurdu.
 *
 * <p>{@link #depth} girişten kaç oda uzakta olduğu. 1C kullanmıyor; 1D'nin kritik path
 * uzunluğunu garanti etmesi için burada tutuluyor ({@code generation.md} §6.2).
 */
public final class LayoutNode {

    private final int id;
    private final PlacedRoom room;
    private final int depth;
    private final DoorState[] doorStates;
    /** Kapı index'i → bağlandığı node id, bağlı değilse -1. */
    private final int[] linkedNode;
    /** Kapı index'i → karşı odadaki kapı index'i, bağlı değilse -1. */
    private final int[] linkedDoor;

    LayoutNode(int id, PlacedRoom room, int depth) {
        this.id = id;
        this.room = room;
        this.depth = depth;
        int doors = room.doorCount();
        this.doorStates = new DoorState[doors];
        this.linkedNode = new int[doors];
        this.linkedDoor = new int[doors];
        Arrays.fill(doorStates, DoorState.BOS);
        Arrays.fill(linkedNode, -1);
        Arrays.fill(linkedDoor, -1);
    }

    public int id() {
        return id;
    }

    public PlacedRoom room() {
        return room;
    }

    public int depth() {
        return depth;
    }

    public Aabb bounds() {
        return room.bounds();
    }

    public RoomTemplate template() {
        return room.template();
    }

    public int doorCount() {
        return doorStates.length;
    }

    public DoorState doorState(int index) {
        return doorStates[index];
    }

    public int linkedNode(int index) {
        return linkedNode[index];
    }

    /** Bu odanın henüz denenmemiş kapıları — yan dal buralardan büyür. */
    public List<OpenDoor> openDoors() {
        List<OpenDoor> open = new ArrayList<>();
        for (int i = 0; i < doorStates.length; i++) {
            if (doorStates[i] == DoorState.BOS) {
                open.add(new OpenDoor(id, i, room.doorAnchor(i), room.doorOutward(i)));
            }
        }
        return open;
    }

    /** Tıpa basılacak kapılar — {@code generation.md} §7. 1D kullanacak. */
    public List<Integer> deadDoors() {
        List<Integer> dead = new ArrayList<>();
        for (int i = 0; i < doorStates.length; i++) {
            if (doorStates[i] == DoorState.OLU) {
                dead.add(i);
            }
        }
        return dead;
    }

    void markDead(int index) {
        require(index, DoorState.BOS);
        doorStates[index] = DoorState.OLU;
    }

    void link(int index, int otherNode, int otherDoor) {
        require(index, DoorState.BOS);
        doorStates[index] = DoorState.BAGLI;
        linkedNode[index] = otherNode;
        linkedDoor[index] = otherDoor;
    }

    /**
     * Durum geçişleri tek yönlü: BOŞ → BAĞLI ya da BOŞ → ÖLÜ. Geri dönüş yok.
     * Aynı kapıya iki oda bağlamak sessizce ikinciyi kaybettirirdi — patlatıyoruz.
     */
    private void require(int index, DoorState expected) {
        if (doorStates[index] != expected) {
            throw new IllegalStateException("oda#" + id + " kapı#" + index + " durumu "
                    + doorStates[index] + ", beklenen " + expected
                    + " (" + template().name() + ")");
        }
    }

    @Override
    public String toString() {
        return "#" + id + " " + room + " derinlik=" + depth + " kapılar=" + Arrays.toString(doorStates);
    }
}
