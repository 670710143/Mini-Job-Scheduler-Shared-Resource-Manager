/**
 * ควบคุมสิทธิ์การใช้ทรัพยากรร่วมของทั้งระบบ
 *
 * ===== ไฟล์นี้เป็นโครงเปล่า นักศึกษาต้องเขียนเอง =====
 *
 * ข้อกำหนดจากโจทย์ที่เกี่ยวกับคลาสนี้:
 *   - หัวข้อ 5: ใช้ Semaphore ควบคุม PRINTER และ DATABASE
 *     จำนวน permit มาจาก command line (Config)
 *     ในส่วนบังคับให้สร้าง Semaphore แบบ fair = true
 *   - Worker ทุกตัวต้องใช้ ResourceManager object เดียวกัน
 *   - หัวข้อ 7: permit ต้องไม่สูญหายหรือค้าง แม้เกิด exception
 *     หรือถูก interrupt ระหว่างถือ resource
 *
 * คำถามที่จะถูกถามใน Demo:
 *   - ทำไมต้อง fair = true และถ้าเปลี่ยนเป็น false จะเกิดอะไรขึ้น
 *   - ถ้า Thread ถูก interrupt หลัง acquire สำเร็จแต่ก่อน release
 *     โค้ดของกลุ่มยังคืน permit ได้หรือไม่
 */

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

public class ResourceManager {

    // TODO: เก็บ Semaphore ของ PRINTER และ DATABASE
    private final Map<ResourceType, Semaphore> semaphores = new EnumMap<>(ResourceType.class);
    private final Map<ResourceType, Integer> totals = new EnumMap<>(ResourceType.class);

    public ResourceManager(int printerPermits, int databasePermits) {
        // TODO
        semaphores.put(ResourceType.PRINTER, new Semaphore(printerPermits, true));
        semaphores.put(ResourceType.DATABASE, new Semaphore(databasePermits, true));
        totals.put(ResourceType.PRINTER, printerPermits);
        totals.put(ResourceType.DATABASE, databasePermits);
    }

    /** ขอสิทธิ์ใช้ทรัพยากร จะรอจนกว่าจะได้ */
    public void acquire(ResourceType type) throws InterruptedException {
        // TODO
        semaphores.get(type).acquire();
    }

    /** คืนสิทธิ์ใช้ทรัพยากร */
    public void release(ResourceType type) {
        // TODO
        semaphores.get(type).release();
    }

    /**
     * ข้อความสั้น ๆ บอกสถานะการใช้ทรัพยากร สำหรับส่งให้ ProjectLogger.monitor()
     * เช่น "printer=1/1 database=0/2"
     */
    public String status() {
        // TODO
        return "printer=" + available(ResourceType.PRINTER) + "/" + totals.get(ResourceType.PRINTER)
             + " database=" + available(ResourceType.DATABASE) + "/" + totals.get(ResourceType.DATABASE);
    }

    private int available(ResourceType type) {
        return semaphores.get(type).availablePermits();
    }
}
