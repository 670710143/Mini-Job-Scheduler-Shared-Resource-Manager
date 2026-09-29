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

    // TODO: เก็บ ReadyQueue, ResourceManager, Statistics และ logger

    public Worker(String name, ReadyQueue readyQueue, ResourceManager resources,
                  Statistics statistics, ProjectLogger logger) {
        super(name);
        // TODO
        throw new UnsupportedOperationException("TODO: Worker constructor");
    }

    @Override
    public void run() {
        // TODO: วนรับงานและเรียก processJob จนกว่าจะได้รับสัญญาณให้หยุด
    }

    /** ทำงานหนึ่งชิ้นให้จบตามลำดับ 5 ขั้นด้านบน */
    private void processJob(Job job) throws InterruptedException {
        // TODO
        throw new UnsupportedOperationException("TODO: Worker.processJob");
    }
}
