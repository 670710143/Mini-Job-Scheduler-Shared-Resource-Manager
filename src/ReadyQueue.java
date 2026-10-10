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
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
 
/**
 * ReadyQueue: คิวงานที่รอ Worker หยิบไปทำ
 *
 * การออกแบบ: PriorityQueue + ReentrantLock + Condition + flag closed
 *   - นโยบาย FCFS / PRIORITY ต่างกันแค่ Comparator
 *   - Worker ที่ไม่มีงานจะ await() บน Condition (ถูกพัก ไม่ busy waiting)
 *   - close() ใช้บอกว่า "จะไม่มีงานเข้าอีกแล้ว" -> take() คืน null เมื่อคิวว่างและปิดแล้ว
 *
 * ทำไมไม่ใช้ PriorityBlockingQueue + poison pill:
 *   poison pill ต้องถูกจัดลำดับตาม Comparator ด้วย อาจถูกหยิบก่อนงานจริง
 *   การใช้ flag closed ตรงไปตรงมากว่า และงานที่ค้างอยู่จะถูกทำจนหมดก่อน Worker หยุด
 *
 * Tie-break: ตัดสินจากข้อมูลของ Job เท่านั้น (ไม่ขึ้นกับว่า Thread ใดเข้าคิวก่อน)
 *   FCFS     : actualArrivalMs -> id
 *   PRIORITY : priority (1 สูงสุด) -> actualArrivalMs -> id
 *
 * ต้องเซ็ต job.actualArrivalMs ก่อนเรียก add()
 */
public class ReadyQueue {

    private final Config.Policy policy;
    private final PriorityQueue<Job> queue; //เก็บงาน เรียงตาม Comparator
    private final ReentrantLock lock = new ReentrantLock(); //กุญแจ ให้เข้าคิวได้ทีละ Thread
    private final Condition notEmpty = lock.newCondition(); //จุดรอของ Worker ที่คิวว่าง เอาไว้ปลุก Worker
    private boolean closed = false;   // อ่าน/เขียนภายใต้ lock เท่านั้น
    // 1. รับมาว่าทำงานแบบไหนเก็บไว้ที่ตัวแปรระดับคลาส
    public ReadyQueue(Config.Policy policy) {
        this.policy = policy;
        this.queue = new PriorityQueue<>(11, comparatorFor(policy));
    }
    // 2. เมื่อเลือกแบบการทำงาน -> จัดการตามแบบนั้น
    private static Comparator<Job> comparatorFor(Config.Policy policy) {
        Comparator<Job> byArrival = Comparator
                .comparingLong((Job j) -> j.actualArrivalMs)
                .thenComparing(j -> j.id);
        if (policy == Config.Policy.FCFS) {
            return byArrival;
        }
        return Comparator
                .comparingInt((Job j) -> j.priority)
                .thenComparing(byArrival);
    }
    /* 3. เพิ่มงานเข้าคิว เรียกโดย Scheduler จัดคิวงานให้
          หลังจากนั้นเรียก worker มาทำงาน โดยใช้ notEmpty.signal(); ส่งสัญญาณบอก
    
    */
    public void add(Job job) {
        lock.lock(); //ล็อกการทำงาน
        try {
            if (closed) {
                throw new IllegalStateException(">> ReadyQueue ถูกปิดแล้ว ไม่รับงานเพิ่ม");
            }
            queue.offer(job);
            notEmpty.signal();   // งานเข้า 1 ชิ้น ปลุก Worker 1 ตัวพอ
        } 
        finally {
            lock.unlock();
        }
    }
 
    /**
     * หยิบงานถัดไปตามนโยบาย เรียกโดย Worker Thread
     *  - คิวว่างและยังไม่ปิด : พัก Thread (ไม่กิน CPU) จนกว่าจะมีงานหรือถูกปิด
     *  - คิวว่างและปิดแล้ว  : คืน null = สัญญาณให้ Worker จบการทำงาน
     *  - ถูก interrupt      : โยน InterruptedException
     */
    public Job take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty()) {
                if (closed) {
                    return null;
                }
                notEmpty.await();   // ปล่อยล็อกและพักจนถูก signal
            }
            return queue.poll(); //หยิบงานออกจากคิวแล้วลบ
        } finally {
            lock.unlock();
        }
    }
 
    /**
     * ประกาศว่าไม่มีงานเข้าอีกแล้ว (เรียกโดย JobGenerator หลังสร้างงานครบ)
     * ปลุก Worker ทุกตัวที่รออยู่เพื่อให้เห็นว่าคิวปิดและจบการทำงาน
     * งานที่ยังค้างในคิวจะถูกหยิบทำจนหมดก่อน
     */
    public void close() {
        lock.lock();
        try {
            closed = true;
            notEmpty.signalAll(); //ปลุก worker ทุกตัวให้ตื่น
        } finally {
            lock.unlock();
        }
    }
 
    /** จำนวนงานที่รออยู่ตอนนี้ ใช้โดย Monitor */
    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }
 
    public Config.Policy getPolicy() {
        return policy;
    }
 
    /** สำเนาสำหรับ log/Monitor เรียงตามนโยบาย */
    public List<Job> snapshot() {
        lock.lock();
        try {
            List<Job> copy = new ArrayList<>(queue);
            copy.sort(queue.comparator());
            return copy;
        } finally {
            lock.unlock();
        }
    }
}




/*
========================================= อธิบายเพิ่มเติม =========================================
[Condition Class] มี 3 method หลัก
    await()	พัก Thread นี้ และ ปล่อยล็อกให้อัตโนมัติ รอจนกว่าจะถูกปลุก
    signal()	ปลุก Thread ที่รออยู่ 1 ตัว
    signalAll()	ปลุก Thread ที่รออยู่ ทุกตัว
//--------------------------------------------------------------------------------//
[ReentrantLock ] 
    กุญแจ (lock) ให้ทีละ Thread เท่านั้นเข้าไปทำงานในส่วนที่ล็อกไว้ได้
    lock.lock();
    try {
        // ส่วนที่ต้องการให้ทีละ Thread เข้า
    } finally {
        lock.unlock();   // ต้องคืนเสมอ แม้เกิด exception
    }
    -> ต่างจาก synchronized ตรงที่ ต้องคืนกุญแจเองเสมอ
   method สำคัญ
    lock()	ขอกุญแจ ถ้าไม่ได้จะรอไปเรื่อยๆ	ใช้ทั่วไป
    unlock()	คืนกุญแจ	ต้องเรียกใน finally เสมอ
    lockInterruptibly()	ขอกุญแจ แต่ถ้าถูก interrupt() ระหว่างรอจะเลิกรอและโยน InterruptedException	Thread ที่ต้องหยุดได้ เช่น take() ของ Worker
    tryLock()	ลองขอกุญแจ ถ้าไม่ได้คืน false ทันที ไม่รอ	อยากหลีกเลี่ยงการรอ/deadlock
    tryLock(time, unit)	รอกุญแจแค่ช่วงเวลาที่กำหนด	รอได้แต่ไม่นาน
    newCondition()	สร้างจุดรอ (Condition) ผูกกับล็อกนี้	เมื่อต้องรอเงื่อนไข เช่น "คิวไม่ว่าง"
*/
