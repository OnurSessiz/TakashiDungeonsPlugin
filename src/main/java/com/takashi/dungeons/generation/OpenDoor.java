package com.takashi.dungeons.generation;

/**
 * Doldurulmayı bekleyen bir kapı: hangi odanın kaçıncı kapısı, dünyada nerede, nereye bakıyor.
 *
 * <p>Yerleştirme algoritmasının girdisi ({@code generation.md} §5.1). Yön ve konum
 * {@link LayoutNode}'dan türetilip burada dondurulur — çağıran her seferinde
 * rotasyon hesabı yapmasın diye.
 *
 * @param nodeId    kapının ait olduğu odanın layout içindeki id'si
 * @param doorIndex odanın kapı listesindeki index — {@code generation.md} §8'deki "adres"
 * @param anchor    kapı anchor'ının DÜNYA koordinatı
 * @param outward   kapının DÜNYA çerçevesinde dışa bakan yönü
 */
public record OpenDoor(int nodeId, int doorIndex, Vec3i anchor, Direction outward) {

    /**
     * Bu kapıya bağlanacak çocuğun kapı anchor'ının oturacağı nokta.
     * Sırt sırta konvansiyonu gereği bir blok dışarıda.
     */
    public Vec3i mate() {
        return anchor.plus(outward.step());
    }

    @Override
    public String toString() {
        return "oda#" + nodeId + " kapı#" + doorIndex + " " + anchor + " " + outward.turkish();
    }
}
