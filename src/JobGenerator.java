import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
 
/**
 * ปล่อยงานเข้าสู่ระบบตามเวลา arrivalMs ของแต่ละ Job (ฉบับร่าง)
 */
public class JobGenerator extends Thread {
 
    /** สัญญาณ "ปล่อยครบแล้ว" Scheduler ต้องเทียบด้วย == ไม่ใช่เทียบ id */
    public static final Job POISON =
            new Job("__POISON__", 0, 1, 0, ResourceType.NONE, 0, -1); //ประกาศสัญญาณ
 
    private final List<Job> jobs;              // สำเนาที่เรียงแล้ว
    private final BlockingQueue<Job> out;      // ช่องทางส่งไปยัง Scheduler
    private final ProjectLogger logger;
 
    public JobGenerator(List<Job> jobs, BlockingQueue<Job> out, ProjectLogger logger) {
        super("generator");
        // WorkloadLoader คืนลิสต์ที่แก้ไขไม่ได้ จึงต้องเรียงบนสำเนา
        //ทำสำเนาก่อนแล้วเรียงตาม arrivalMs เพราะลิสต์จาก WorkloadLoader แก้ไขไม่ได้
        // และถ้า arrivalMs เท่ากันจะใช้ sequence ตัดสิน
        this.jobs = new ArrayList<>(jobs); 
        this.jobs.sort(Comparator
                .comparingLong((Job j) -> j.arrivalMs)
                .thenComparingInt(j -> j.sequence));
        this.out = out;
        this.logger = logger;
    }
 
    @Override
    public void run() {
        try {
            for (Job job : jobs) {
                // คำนวณเวลาที่เหลือจากนาฬิกากลางทุกรอบ ความคลาดเคลื่อนจึงไม่สะสม
                // และวนเช็คซ้ำหลังตื่น เผื่อ sleep ตื่นเร็วกว่ากำหนด
                long remaining;
                while ((remaining = job.arrivalMs - logger.now()) > 0) { //รอจนถึง arrivalMS
                    Thread.sleep(remaining);
                }
 
                // เขียนเวลาจริงก่อนส่ง เพื่อให้ Worker ที่หยิบงานไปเห็นค่านี้แน่นอน
                job.actualArrivalMs = logger.now(); //บันทึกเวลาจริง
                logger.jobArrived(job); //เรียก logger
                out.put(job); //ส่งงานต่อไปยัง Scheduler
            }
            out.put(POISON); // ส่งสัญญาณหลังปล่อยงานครบทุกชิ้น
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

//ส่วนที่ต้องเขียนต่อ:
//Scheduler ต้องตรวจจับ JobGenerator.POISON แล้วหยุดรับงาน
//Worker ต้องรู้ว่าระบายงานใน ReadyQueue หมดแล้วจึงหยุดได้
//Main ต้องรอจนงานเสร็จครบ jobs.size() ชิ้น แล้วสั่งหยุดและ join() ทุก Thread
