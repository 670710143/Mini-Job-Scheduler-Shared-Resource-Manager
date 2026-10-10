/**
 * รับงานจาก JobGenerator แล้วจัดเข้า Ready Queue
 *
 * ===== ไฟล์นี้เป็นโครงเปล่า นักศึกษาต้องเขียนเอง =====
 *
 * ข้อกำหนดจากโจทย์ (หัวข้อ 2 และ 4):
 *   - Scheduler เป็น Thread บังคับ ห้ามให้ JobGenerator ใส่งานลง Ready Queue โดยตรง
 *   - รับผิดชอบการจัดลำดับตามนโยบาย FCFS หรือ Priority
 *
 * ข้อควรคิด:
 *   - Scheduler รับงานจาก JobGenerator ผ่านอะไร และรอโดยไม่กิน CPU อย่างไร
 *   - เมื่อ JobGenerator ปล่อยงานครบแล้ว Scheduler รู้ได้อย่างไรว่าควรหยุด
 */

/*
การทำงาน : JobGenerator --(inbox: BlockingQueue)--> Scheduler --(ReadyQueue)--> Worker
สัญญาณหยุด:JobGenerator ใส่ Scheduler.END เป็นชิ้นสุดท้ายเมื่อปล่อยงานครบ
เมื่อ Scheduler จบ (ไม่ว่าด้วยเหตุใด) จะเรียก readyQueue.close() ใน finally  
---> เพื่อให้ Worker ที่หลับอยู่ตื่นมาเห็นว่าไม่มีงานเข้าอีกแล้ว
 */
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
public class Scheduler extends Thread {

    // TODO: เก็บช่องทางรับงานจาก JobGenerator, ReadyQueue ปลายทาง และ logger
    //
    // หมายเหตุ: constructor ด้านล่างยังไม่มี parameter สำหรับ "ช่องทางรับงาน"
    // ให้เพิ่มเข้าไปให้ตรงกับที่ออกแบบไว้ใน JobGenerator
    // เพิ่ม parameter ได้ แต่อย่าเปลี่ยนชื่อคลาส
    public static final Job END = new Job("END", 0 ,Integer.MAX_VALUE,0,ResourceType.NONE,0,-1);
   private final BlockingQueue<Job> inbox;
   private final ReadyQueue readyQueue;
   private final ProjectLogger logger;
    public Scheduler(BlockingQueue<Job> inbox,ReadyQueue readyQueue, ProjectLogger logger) {
        super("scheduler");
        this.inbox = Objects.requireNonNull(inbox);
        this.readyQueue = Objects.requireNonNull(readyQueue);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public void run() {
        try{
            while(true){
                Job job = inbox.take(); //หยิบงานถัดไปจาก inbox
                if(job == END){
                    break;
                }
                readyQueue.add(job);
                log("[Scheduler] " + job.id + " added to -> ReadyQueue (size=" + readyQueue.size() + ")");
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // คืนสถานะ interrupt แล้วออกจากลูป
        }
        finally{
            readyQueue.close(); // ต้องเรียกเสมอ ไม่งั้น Worker ค้างรอ
            log("[Scheduler] : Closed ReadyQueue ");
        }
    }
    
   private void log(String message) {
        logger.systemEvent(message);
    }
}
