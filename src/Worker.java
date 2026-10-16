
/**
 * Thread ที่ดึงงานจาก Ready Queue ไปทำจนเสร็จ
 *
 * ===== ไฟล์นี้เป็นโครงเปล่า นักศึกษาต้องเขียนเอง =====
 *
 * ลำดับการทำงานของ Job หนึ่งชิ้น บังคับตามหัวข้อ 6 ของโจทย์:
 *   1. รับงานจาก Ready Queue แล้วบันทึกเวลาเริ่ม
 *   2. จำลองงานหลักด้วย Thread.sleep(job.workMs)
 *   3. ถ้า job.resource != NONE ให้บันทึกเวลาเริ่มรอ แล้ว acquire
 *   4. จำลองการถือครองด้วย Thread.sleep(job.resourceMs)
 *   5. release แล้วบันทึกเวลาจบ
 *
 * ห้ามสลับขั้นที่ 2 กับ 3 เพราะจะทำให้ผลของทุกกลุ่มเทียบกันไม่ได้
 *
 * จุดที่มักพลาด:
 *   - ถ้า exception หรือ interrupt เกิดขึ้นหลัง acquire แต่ก่อน release
 *     permit จะค้างถาวรและระบบจะแขวน ต้องออกแบบให้คืนได้เสมอ
 *   - Worker ต้องหยุดเองได้เมื่อไม่มีงานเหลือแล้ว ไม่ใช่วนรอตลอดไป
 */

public class Worker extends Thread {

    private final ReadyQueue readyQueue;
    private final ResourceManager resources;
    private final Statistics statistics;
    private final ProjectLogger logger;

    public Worker(String name, ReadyQueue readyQueue, ResourceManager resources,
            Statistics statistics, ProjectLogger logger) {
        super(name);
        this.readyQueue = readyQueue;
        this.resources = resources;
        this.statistics = statistics;
        this.logger = logger;
    }

    @Override
    public void run() {
        try {
            Job job;
            while ((job = readyQueue.take()) != null) {   // null = คิวว่างและปิดแล้ว
                statistics.jobStarted(job);
                try {
                    processJob(job);   // recordCompletion อยู่ท้าย processJob
                } finally {
                    statistics.jobFinished(job);// ทำเสมอ แม้ exception หรือถูก interrupt
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * ทำงานหนึ่งชิ้นให้จบตามลำดับ 5 ขั้น
     */
    private void processJob(Job job) throws InterruptedException {
        // ขั้น 1: บันทึกเวลาเริ่ม
        job.startTimeMs = logger.now();
        logger.jobStarted(job);
        // ขั้น 2: งานหลัก
        Thread.sleep(job.workMs);

        if (job.resource != ResourceType.NONE) {
            // ขั้น 3: เริ่มรอ แล้ว acquire
            job.resourceWaitStartMs = logger.now();
            resources.acquire(job.resource);
            job.resourceWaitMs = logger.now() - job.resourceWaitStartMs;

            try {
                // ขั้น 4: ถือครอง resource
                Thread.sleep(job.resourceMs);
            } finally {
                // ขั้น 5: คืนเสมอ แม้ถูก interrupt หรือเกิด exception
                resources.release(job.resource);
            }
        }

        // ขั้น 5: บันทึกเวลาจบ แล้วส่งให้ Statistics
        job.completionTimeMs = logger.now();
        statistics.recordCompletion(job);
    }
}
