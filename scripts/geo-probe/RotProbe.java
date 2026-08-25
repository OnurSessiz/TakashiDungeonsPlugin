import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.transform.AffineTransform;

/**
 * generation.md §3 / §11-1: SchematicService.pasteBlocking'in kullandigi transform'un
 * yon isaretini olcer. Paste, clipboard origin'ine gore yerel offset v'yi
 * to + transform.apply(v) noktasina yaziyor; dolayisiyla burada olculen sey
 * pastelenen bloklarin gittigi yerin ta kendisi.
 */
public class RotProbe {

    static final String[] NAME = {"KUZEY", "DOGU", "GUNEY", "BATI"};
    // yon index: K=0 D=1 G=2 B=3   -> adim vektoru
    static final int[][] STEP = {{0,-1},{1,0},{0,1},{-1,0}};

    public static void main(String[] args) {
        System.out.println("=== SchematicService ile ayni transform: rotateY(-derece) ===\n");

        for (int deg : new int[]{0, 90, 180, 270}) {
            AffineTransform t = new AffineTransform();
            if (deg != 0) t = (AffineTransform) t.rotateY(-deg);

            System.out.println("--- rotateY(-" + deg + ")  [R=" + (deg/90) + "] ---");

            // 1) genel nokta: (x,y,z) nereye gidiyor
            BlockVector3 p = t.apply(BlockVector3.at(3, 7, 5).toVector3()).toBlockPoint();
            System.out.println("  nokta (3,7,5) -> (" + p.x() + "," + p.y() + "," + p.z() + ")");

            // 2) her yon birim vektoru nereye donuyor
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

        // 3) test_corner senaryosu: K+D kapili oda 90 derece dondurulunce hangi kapilar?
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

        // 4) spec §3'teki nokta formulleri birebir tutuyor mu
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

    /** birim vektorden yon index'i; degilse -1 */
    static int dirOf(BlockVector3 v) {
        for (int d = 0; d < 4; d++) {
            if (v.x() == STEP[d][0] && v.z() == STEP[d][1]) return d;
        }
        return -1;
    }
}
