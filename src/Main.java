
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
 
/**
 * Main / Controller: สร้าง object, เริ่มระบบ, รอให้จบ, สั่งหยุด และพิมพ์ผลสรุป
 *
 *   JobGenerator --> arrivalQueue --> Scheduler --> ReadyQueue --> Worker x N
 *                                                                   |
 *                                                   ResourceManager (Semaphore)
 *                                                   Statistics + Logger
 *   Monitor --> อ่านสถานะระบบทุก ~1,000 ms
 *
 * หลักการปิดระบบ (ไม่ใช้ System.exit, ไม่เดาเวลาด้วย sleep):
 *   สัญญาณ "ส่งงานครบ"   = JobGenerator.POISON (poison pill) ใน arrivalQueue ซึ่งเป็น FIFO
 *   สัญญาณ "ไม่มีงานเพิ่ม" = readyQueue.close() แล้ว Worker เจอ take() คืน null
 *   การยืนยันว่า "เสร็จจริง" = join() Worker ครบ แล้วเทียบ completedCount กับ jobs.size()
 *
 * หลักการความปลอดภัย: ทุกเส้นทาง (ปกติ / Thread ตาย / start ไม่สำเร็จ / main ถูก interrupt)
 * ต้องผ่านขั้นตอนที่ 6 (shutdown) เสมอ เพื่อไม่ให้มี Thread ค้างจน JVM ไม่ปิด
 */
public class Main {
 
    public static void main(String[] args) {
 
        // ---------- 1. อ่านและตรวจ argument ก่อนเริ่ม Thread ใดๆ ----------
        Config config;
        try {
            config = Config.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            System.err.println(Config.USAGE);
            return;
        }
 
        // ---------- 2. โหลด workload จากไฟล์ภายนอก ----------
        List<Job> jobs;
        try {
            jobs = WorkloadLoader.load(config.workloadPath);
        } catch (Exception e) {
            System.err.println("อ่านไฟล์ workload ไม่สำเร็จ: " + e.getMessage());
            System.err.println();
            System.err.println(Config.USAGE);
            return;
        }
        if (jobs.isEmpty()) {
            System.err.println("ไฟล์ workload ไม่มี Job เลย: " + config.workloadPath);
            return;
        }
 
        ProjectLogger logger = new ProjectLogger();   // ตัวเดียว เริ่มจับเวลาทันที
        logger.systemStart(config);
 
        // ---------- 3. สร้างส่วนประกอบของระบบ (ยังไม่มี Thread ใดเริ่ม) ----------
        ResourceManager resources =
                new ResourceManager(config.printerPermits, config.databasePermits);
        ReadyQueue readyQueue = new ReadyQueue(config.policy);
        Statistics statistics = new Statistics();
 
        BlockingQueue<Job> arrivalQueue = new LinkedBlockingQueue<>();
 
        // JobGenerator ต้องปล่อยงานตามลำดับเวลา จึงส่งสำเนาที่เรียงตาม arrivalMs -> sequence
        List<Job> arrivalOrder = new ArrayList<>(jobs);
        arrivalOrder.sort(Comparator
                .comparingLong((Job j) -> j.arrivalMs)
                .thenComparingInt(j -> j.sequence));
 
        JobGenerator generator = new JobGenerator(arrivalOrder, arrivalQueue, logger);
        Scheduler scheduler = new Scheduler(arrivalQueue, readyQueue, logger);
        Monitor monitor = new Monitor(readyQueue, resources, statistics, logger);
        List<Worker> workers = new ArrayList<>();
        for (int i = 1; i <= config.workers; i++) {
            String name = "worker-" + i;
            workers.add(new Worker(name, readyQueue, resources, statistics, logger));
        }
 
        // Thread ที่ตายด้วย exception ที่ไม่ได้จับ จะถูกบันทึกลง log แทนที่จะหายเงียบ
        Thread.UncaughtExceptionHandler crashHandler = (t, e) -> {
            logger.systemEvent("THREAD_CRASH " + t.getName() + ": " + e);
            e.printStackTrace();
        };
 
        List<Thread> started = new ArrayList<>();   // เฉพาะ Thread ที่ start() สำเร็จจริง
 
        try {
            // ---------- 4. สร้างและเริ่ม Thread ----------
            // ฝั่งรับงาน (Worker, Scheduler, Monitor) เริ่มก่อน ฝั่งผลิตงาน (JobGenerator) เริ่มสุดท้าย
            for (Worker w : workers) {
                startThread(w, started, crashHandler);
            }
            startThread(scheduler, started, crashHandler);
            startThread(monitor, started, crashHandler);
            startThread(generator, started, crashHandler);
 
            // ---------- 5. รอจนงานเสร็จครบ (ตามทิศทางที่งานไหล) ----------
            generator.join();                    // JobGenerator จบแล้ว (ปกติหรือตายก็ตาม)
            arrivalQueue.offer(JobGenerator.POISON);   // กันกรณี generator ตายก่อนส่ง END (ส่งซ้ำไม่เสียหาย)
 
            scheduler.join();                    // Scheduler จบแล้ว
            readyQueue.close();                  // กันกรณี Scheduler ตายก่อน close (close ซ้ำไม่เสียหาย)
 
            for (Worker w : workers) {
                w.join();                        // Worker ทำงานค้างจนหมดและออกจากลูปแล้ว
            }
 
        } catch (InterruptedException e) {
            logger.systemEvent("ERROR: main ถูกขัดจังหวะ สั่งหยุดทุก Thread");
        } catch (Throwable t) {
            logger.systemEvent("ERROR: เริ่มระบบไม่สำเร็จหรือเกิดข้อผิดพลาด: " + t);
        } finally {
            // ---------- 6. สั่งหยุดและยืนยันว่าทุก Thread หยุดจริง (ทำเสมอทุกเส้นทาง) ----------
            shutdown(started);
        }
 
        // ---------- 7. สรุปผล ----------
        long makespanMs = computeMakespan(jobs, logger);
        int completed = statistics.completedCount();
 
        if (completed != jobs.size()) {
            logger.systemEvent("ERROR: งานเสร็จ " + completed + " จาก " + jobs.size());
        }
        if (readyQueue.size() > 0) {
            logger.systemEvent("ERROR: ReadyQueue ยังเหลือ " + readyQueue.size() + " งาน");
        }
        if (statistics.runningCount() != 0) {
            logger.systemEvent("ERROR: running ไม่เป็นศูนย์ (" + statistics.runningCount() + ")");
        }
 
        statistics.printSummary(jobs, makespanMs);
        logger.systemStop(completed, jobs.size());
 
        // ไม่เรียก System.exit(): main() จบและไม่มี Thread ค้าง -> JVM ปิดเอง
    }
 
    /** ตั้ง handler แล้ว start() ถ้าสำเร็จจึงบันทึกลงรายการ (start ล้มเหลวจะโยน exception ขึ้นไป) */
    private static void startThread(Thread t, List<Thread> started,
                                    Thread.UncaughtExceptionHandler handler) {
        t.setUncaughtExceptionHandler(handler);
        t.start();
        started.add(t);
    }
 
    /**
     * สั่ง interrupt Thread ที่ยังมีชีวิต แล้ว join ทุกตัวจนกว่าจะหยุดจริง
     *  - เส้นทางปกติ: ทุก Thread จบเองแล้ว เหลือแค่ Monitor ที่ต้องถูกสั่งหยุด
     *  - เส้นทางผิดพลาด: interrupt ปลุก Thread ที่รออยู่ให้ออกจากการรอ แล้วไปทำ finally ของตัวเอง
     * join แบบทนการถูก interrupt: ถ้า main ถูกขัดจังหวะซ้ำก็ยังรอต่อ แล้วคืนสถานะตอนท้าย
     */
    private static void shutdown(List<Thread> started) {
        for (Thread t : started) {
            if (t.isAlive()) {
                t.interrupt();
            }
        }
        boolean wasInterrupted = false;
        for (Thread t : started) {
            while (true) {
                try {
                    t.join();
                    break;
                } catch (InterruptedException e) {
                    wasInterrupted = true;
                }
            }
        }
        if (wasInterrupted) {
            Thread.currentThread().interrupt();
        }
    }
 
    /**
     * เวลาที่งานชิ้นสุดท้ายเสร็จ = completionTimeMs สูงสุด (นาฬิกา logger.now() เดียวกัน)
     * ไม่รวมเวลาที่ใช้หยุด Monitor/join จึงไม่ทำให้ Throughput ต่ำเกินจริง
     * ถ้าไม่มีงานเสร็จเลย ใช้เวลาปัจจุบันแทน
     */
    private static long computeMakespan(List<Job> jobs, ProjectLogger logger) {
        long last = -1;
        for (Job job : jobs) {
            last = Math.max(last, job.completionTimeMs);
        }
        return last >= 0 ? last : logger.now();
    }
}
 
