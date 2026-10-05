/**
/**
 * คิวงานที่พร้อมถูกหยิบไปทำ
 *
 * ===== ไฟล์นี้เป็นโครงเปล่า นักศึกษาต้องเขียนเอง =====
 *
 * สิ่งที่คลาสนี้ต้องทำได้:
 *   - เก็บงานที่รอ Worker อยู่
 *   - หยิบงานถัดไปตามนโยบายที่เลือก (FCFS หรือ Priority)
 *   - ถูกเรียกจากหลาย Thread พร้อมกันได้อย่างปลอดภัย
 *
 * ข้อกำหนดจากโจทย์ที่เกี่ยวกับคลาสนี้:
 *   - หัวข้อ 4: priority = 1 สูงสุด เมื่อเท่ากันต้องมีกติกาตัดสินลำดับ (tie-break)
 *     ที่ตัดสินจากข้อมูลของ Job ไม่ขึ้นกับว่า Thread ใดเข้าถึงคิวก่อน
 *   - หัวข้อ 7: ห้ามวนลูปเช็กแบบกิน CPU (busy waiting) — Worker ที่ไม่มีงานทำ
 *     ต้องถูกพักไว้ ไม่ใช่วนถามซ้ำ ๆ
 *
 * จะออกแบบเป็นคลาสเดียวที่รับนโยบายเข้ามา หรือแยกเป็นสองคลาส
 * หรือใช้โครงสร้างข้อมูลสำเร็จรูปของ Java ก็ได้ ขอให้อธิบายเหตุผลได้ใน Demo
 */
public class ReadyQueue {

    // TODO: เก็บนโยบาย (Config.Policy) และโครงสร้างข้อมูลที่ใช้เก็บงาน

    public ReadyQueue(Config.Policy policy) {
        // TODO
        throw new UnsupportedOperationException("TODO: ReadyQueue constructor");
    }

    /** ใส่งานเข้าคิว เรียกโดย Scheduler Thread */
    public void add(Job job) {
        // TODO
        throw new UnsupportedOperationException("TODO: ReadyQueue.add");
    }

    /**
     * หยิบงานถัดไปตามนโยบาย เรียกโดย Worker Thread
     *
     * ถ้ายังไม่มีงาน ต้องรอโดยไม่กิน CPU
     * ต้องคิดด้วยว่าจะบอก Worker อย่างไรเมื่อไม่มีงานเหลือแล้วและควรหยุดทำงาน
     */
    public Job take() throws InterruptedException {
        // TODO
        throw new UnsupportedOperationException("TODO: ReadyQueue.take");
    }

    /** จำนวนงานที่รออยู่ตอนนี้ ใช้โดย Monitor — ต้องอ่านได้อย่างปลอดภัย */
    public int size() {
        // TODO
        throw new UnsupportedOperationException("TODO: ReadyQueue.size");
    }
}
*/
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ใครเรียกอะไร (ตามสถาปัตยกรรมในโจทย์หัวข้อ 2):
 *   Scheduler Thread : add(job) ทุกครั้งที่ Job พร้อม, แล้ว close() เมื่อส่ง Job ครบ
 *   Worker Threads   : take() วนจนได้ null
 *   Monitor Thread   : size() / isClosed()
 *
 * นโยบาย (หัวข้อ 4):
 *   FCFS     : Job ที่เข้า Ready Queue ก่อน (add ก่อน) ถูกหยิบก่อน
 *   PRIORITY : priority เลขน้อยสำคัญกว่า (1 = สูงสุด)
 *              เท่ากัน -> arrivalMs น้อยกว่าก่อน -> id น้อยกว่าก่อน
 *              (ตัดสินจากข้อมูลใน Job ล้วน ๆ ไม่ขึ้นกับว่า Thread ไหนมาถึงคิวก่อน)
 *
 * ต้องการจาก Job: getId() (String), getArrivalMs() (long), getPriority() (int)
 * และฟิลด์เหล่านี้ต้อง final / ไม่ถูกแก้หลังสร้าง
 */
public class ReadyQueue {

    private final Config.Policy policy;
    private final Queue<Job> queue;                 // เข้าถึงได้เฉพาะตอนถือ lock
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();   // ห้องรอของ Worker
    private boolean closed = false;                 // อ่าน/เขียนใต้ lock เท่านั้น

    public ReadyQueue(Config.Policy policy) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        switch (policy) {
            case FCFS:
                this.queue = new ArrayDeque<>();
                break;
            case PRIORITY:
                this.queue = new PriorityQueue<>(priorityOrder());
                break;
            default:
                throw new IllegalArgumentException("Unsupported policy: " + policy);
        }
    }

    /** priority (น้อยก่อน) -> arrivalMs (น้อยก่อน) -> id (น้อยก่อน) */
    private static Comparator<Job> priorityOrder() {
        return Comparator
                .comparingInt(Job::getPriority)
                .thenComparingLong(Job::getArrivalMs)
                .thenComparing(Job::getId);
    }

    /** Scheduler เรียก: ใส่ Job ที่พร้อมทำเข้าคิว */
    public void add(Job job) {
        Objects.requireNonNull(job, "job must not be null");
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("ReadyQueue is closed");
            }
            queue.add(job);
            notEmpty.signal();      // งานเพิ่ม 1 ชิ้น ปลุก Worker 1 ตัว
        } finally {
            lock.unlock();
        }
    }

    /**
     * Worker เรียก: หยิบ Job ถัดไปตามนโยบาย
     * ถ้ายังไม่มีงานจะหลับรอ (ไม่ busy-wait)
     *
     * @return Job หรือ null เมื่อคิวว่างและถูก close() แล้ว = Worker ควรจบการทำงาน
     * @throws InterruptedException ถ้าถูก interrupt ระหว่างรอ
     */
    public Job take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty()) {
                if (closed) {
                    return null;
                }
                notEmpty.await();
            }
            return queue.poll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Scheduler เรียกหลังส่ง Job ครบแล้ว: "จะไม่มีงานใหม่อีก"
     * Job ที่ค้างในคิวยังถูกหยิบจนหมดตามปกติ แล้ว Worker ถึงจะได้ null
     * เรียกซ้ำได้ และทำงานถูกต้องไม่ว่ามี Worker กี่ตัว (รวมที่เพิ่มทีหลัง)
     */
    public void close() {
        lock.lock();
        try {
            closed = true;
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Monitor เรียก: จำนวน Job ที่รออยู่ (snapshot ณ ขณะอ่าน) */
    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    public boolean isClosed() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    public Config.Policy getPolicy() {
        return policy;
    }
}
