/**
 * ข้อมูลของงานหนึ่งชิ้น
 *
 * ฟิลด์ทั้งหมดในไฟล์นี้มาจากไฟล์ workload CSV โดยตรง และถูกกำหนดครั้งเดียว
 * ตอนโหลด จึงประกาศเป็น final และปลอดภัยเมื่อหลาย Thread อ่านพร้อมกัน
 *
 * ไฟล์นี้เป็นโค้ดตั้งต้นที่อาจารย์แจก แต่ต่างจากไฟล์อื่นตรงที่
 * นักศึกษา "ต้องแก้" โดยเพิ่มฟิลด์ของตัวเองในส่วน TODO ด้านล่าง
 */
public class Job {

    public final String id;
    public final long arrivalMs; //เวลาที่งานเข้าสู่ระบบ นับตั้งแต่โปรแกรมเริ่ม
    public final int priority;   // ลำดับความสำคัญ น้อย = priority สูง
    public final long workMs;    // ระยะเวลาของงานหลักก่อนของ resource
    public final ResourceType resource;  //resource รวมที่ต้องใช้ 
    public final long resourceMs;        // ระยะเวลาครอง resource จะ = 0 เสมอเมื่อ resource เป็น NONE

    /**
     * ลำดับที่งานนี้ปรากฏในไฟล์ workload เริ่มจาก 0
     * เตรียมไว้ให้เผื่อกลุ่มต้องการใช้ประกอบการตัดสินลำดับเมื่อ priority เท่ากัน
     * จะใช้หรือไม่ใช้ก็ได้ กติกาตัดสินลำดับเป็นสิ่งที่กลุ่มต้องออกแบบเอง
     */
    public final int sequence;
    public Job(String id, long arrivalMs, int priority, long workMs,
               ResourceType resource, long resourceMs, int sequence) {
        this.id = id;
        this.arrivalMs = arrivalMs;
        this.priority = priority;
        this.workMs = workMs;
        this.resource = resource;
        this.resourceMs = resourceMs;
        this.sequence = sequence;
    }

    // =====================================================================
    // TODO (นักศึกษา): เพิ่มฟิลด์สำหรับเก็บค่าที่ใช้วัดผลของงานชิ้นนี้เอง

   //volatile คือ keyword ที่ใช้กับ ตัวแปรเพื่อบอก JVM ว่าอาจถูกหลาย thread เข้าถึงและเปลี่ยนแปลงพร้อมกัน
    public volatile long actualArrivalMs = -1;      //  เวลาที่เข้าสู่ระบบจริง
    public volatile long startTimeMs = -1;         // เวลาที่เริ่มถูกทำโดย Worker
    public volatile long resourceWaitStartMs = -1; // เวลาที่เริ่มรอคิวทรัพยากร/Semaphore 
    public volatile long resourceWaitMs = 0;       // ระยะเวลารอคิวทรัพยากรรวม 
    public volatile long completionTimeMs = -1;    // เวลาที่งานชิ้นนี้ทำเสร็จสมบูรณ์ 
    public long waitingTime(){
        if(startTimeMs >=0 && actualArrivalMs >=0) 
            return startTimeMs - actualArrivalMs;
        return 0;
    }
    public long turnaroundTime() {
        if (completionTimeMs >= 0 && actualArrivalMs >= 0) 
            return completionTimeMs - actualArrivalMs;
        return 0;
    }
    public long getResourceWaitTime() {
        return resourceWaitMs;
    }

    // ค่าที่โครงงานต้องการ (ดูหัวข้อ 8 ของเอกสารโจทย์):
    //   - เวลาที่เข้าสู่ระบบจริง
    //   - เวลาที่เริ่มถูกทำโดย Worker
    //   - เวลาที่ทำเสร็จ
    //   - เวลาที่เริ่มรอ resource และเวลารอ resource รวม
    //
    // สามคำถามที่ต้องตอบให้ได้ก่อนเขียน และจะถูกถามใน Demo:
    //   1. ใช้เวลาจากนาฬิกาตัวไหน (ดู ProjectLogger.now() ซึ่งให้เวลาฐานเดียว
    //      กับที่ปรากฏใน log ทำให้ค่าที่วัดกับ log ตรวจสอบกันได้)
    //   2. ฟิลด์ใดถูกเขียนโดย Thread หนึ่งแล้วอ่านโดยอีก Thread หนึ่ง
    //      และต้องป้องกันอย่างไร
    /*    ans  ฟิลด์กลุ่มค่าวัดผล (actualArrivalMs, startTimeMs, completionTimeMs, 
                resourceWaitMs) ถูกเขียนโดย JobGenerator / Worker Thread และถูกอ่านภายหลังโดย Statistics หรือ Monitor Thread    
                การป้องกัน: ใช้ Keyword volatile สำหรับฟิลด์วัดผลเหล่านี้Keyword volatile จะช่วยรับประกัน Memory Visibility ทำให้ Thread อื่นๆ 
                อ่านค่าอัปเดตล่าสุดได้อย่างถูกต้องทันทีโดยไม่ต้องใช้ heavy-weight Synchronization
    */
    //   3. ผลที่ได้ต้องสอดคล้องกับสมการตรวจสอบในหัวข้อ 8:
    //      Turnaround = Waiting + workMs + Resource Wait + resourceMs
    // =====================================================================

    @Override
    public String toString() {
        return String.format("%s(priority=%d, work=%dms, %s)",
                id, priority, workMs,
                resource == ResourceType.NONE ? "no resource"
                        : resource + " " + resourceMs + "ms");
    }
}
