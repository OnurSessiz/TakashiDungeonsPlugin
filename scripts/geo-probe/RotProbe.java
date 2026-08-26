import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.transform.AffineTransform;

/**
 * generation.md §3: measures the direction sign of the transform
 * SchematicService.pasteBlocking uses. A paste writes a local offset v (relative to the
 * clipboard origin) to the point to + transform.apply(v), so what is measured here is exactly
 * where the pasted blocks end up.
 */
public class RotProbe {

    static final String[] NAME = {"KUZEY", "DOGU", "GUNEY", "BATI"};
    // direction index: N=0 E=1 S=2 W=3   -> step vector
    static final int[][] STEP = {{0,-1},{1,0},{0,1},{-1,0}};

    public static void main(String[] args) {
        System.out.println("=== SchematicService ile ayni transform: rotateY(-derece) ===\n");

        for (int deg : new int[]{0, 90, 180, 270}) {
            AffineTransform t = new AffineTransform();
            if (deg != 0) t = (AffineTransform) t.rotateY(-deg);

            System.out.println("--- rotateY(-" + deg + ")  [R=" + (deg/90) + "] ---");

            // 1) a general point: where does (x,y,z) go
            BlockVector3 p = t.apply(BlockVector3.at(3, 7, 5).toVector3()).toBlockPoint();
            System.out.println("  nokta (3,7,5) -> (" + p.x() + "," + p.y() + "," + p.z() + ")");

            // 2) where each direction unit vector turns to
            StringBuilder sb = new StringBuilder("  yonler: ");
            boolean saatYonu = true, tersYonu = true;
            for (int d = 0; d < 4; d++) {
                BlockVector3 in = BlockVector3.at(STEP[d][0], 0, STEP[d][1]);
                BlockVector3 out = t.apply(in.toVector3()).toBlockPoint();
                int od = dirOf(out);
                sb.append(NAME[d]).append("->").append(od < 0 ? "??" : NAME[od]).append("  ");
                if (od != Math.floorMod(d + deg/90, 4)) saatYonu = false;
                if (od != Math.floorMod(d - deg/90, 4)) tersYonu = false;
            }
            System.out.println(sb);
            System.out.println("  d'=(d+R)%4 [saat yonu, spec'teki +1] : " + (saatYonu ? "UYUYOR" : "uymuyor"));
            System.out.println("  d'=(d-R)%4 [ters yon, -1]            : " + (tersYonu ? "UYUYOR" : "uymuyor"));
            System.out.println();
        }

        // 3) the test_corner scenario: rotate an N+E room by 90 degrees -- which doors?
        System.out.println("=== test_corner (kapilar: KUZEY + DOGU) 90 derece dondurulurse ===");
        AffineTransform t90 = (AffineTransform) new AffineTransform().rotateY(-90);
        StringBuilder r = new StringBuilder();
        for (int d : new int[]{0, 1}) {
            BlockVector3 out = t90.apply(BlockVector3.at(STEP[d][0], 0, STEP[d][1]).toVector3()).toBlockPoint();
            r.append(NAME[dirOf(out)]).append(" ");
        }
        System.out.println("  sonuc: " + r.toString().trim());
        System.out.println("  DOGU+GUNEY beklenir  -> spec'teki +1 (saat yonu) DOGRU");
        System.out.println("  BATI+KUZEY cikarsa   -> isaret ters, butun formuller cevrilecek");

        // 4) do the point formulas in spec §3 hold exactly
        System.out.println("\n=== spec §3 nokta formulleri dogrulamasi ===");
        int x = 3, y = 7, z = 5;
        int[][] beklenen = {{x,y,z}, {-z,y,x}, {-x,y,-z}, {z,y,-x}};
        boolean hepsi = true;
        for (int R = 0; R < 4; R++) {
            AffineTransform t = new AffineTransform();
            if (R != 0) t = (AffineTransform) t.rotateY(-R * 90);
            BlockVector3 got = t.apply(BlockVector3.at(x, y, z).toVector3()).toBlockPoint();
            boolean ok = got.x() == beklenen[R][0] && got.y() == beklenen[R][1] && got.z() == beklenen[R][2];
            hepsi &= ok;
            System.out.printf("  R=%d  bekl(%d,%d,%d)  gercek(%d,%d,%d)  %s%n",
                    R, beklenen[R][0], beklenen[R][1], beklenen[R][2],
                    got.x(), got.y(), got.z(), ok ? "OK" : "HATA");
        }
        System.out.println(hepsi ? "\nSPEC §3 NOKTA FORMULLERI DOGRU" : "\nSPEC §3 YANLIS - duzeltilecek");
    }

    /** direction index from a unit vector; -1 if it is not one */
    static int dirOf(BlockVector3 v) {
        for (int d = 0; d < 4; d++) {
            if (v.x() == STEP[d][0] && v.z() == STEP[d][1]) return d;
        }
        return -1;
    }
}
