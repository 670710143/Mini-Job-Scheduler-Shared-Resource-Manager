

/**
 * รวบรวมและคำนวณค่าที่ใช้วัดผลของการรันหนึ่งครั้ง
 *
 * ===== ไฟล์นี้เป็นโครงเปล่า นักศึกษาต้องเขียนเอง =====
 *
 * ข้อกำหนดจากโจทย์ที่เกี่ยวกับคลาสนี้ (หัวข้อ 8):
 *   - Waiting Time, Turnaround Time, Throughput, Resource Wait Time
 *   - ต้องถูกอัปเดตจากหลาย Worker พร้อมกันได้อย่างปลอดภัย
 *   - ผลต้องสอดคล้องกับสมการตรวจสอบ:
 *       Turnaround = Waiting + workMs + Resource Wait + resourceMs
 *     ใช้สมการนี้ตรวจงานทีละชิ้นได้ว่าค่าไหนคำนวณผิด
 *
 * ข้อควรระวัง: ค่าเฉลี่ยของ Resource Wait ให้คิดเฉพาะงานที่ใช้ resource
 * ส่วนงานที่ resource = NONE ให้ถือว่า Resource Wait เป็น 0
 */
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
public class Statistics {
// TODO: เก็บข้อมูลของงานที่เสร็จแล้ว หรือเก็บผลรวมไว้คำนวณทีหลัง
    private static final long TOLERANCE_MS = 50; //ส่วนต่างสูงสุดที่ยอมรับได้ต่อสมการหนึ่ง Job (ms) 
    private final List<Job> completedJobs = new ArrayList<>(); 
    private final Set<String> completedIds = new HashSet<>();
    private long sumWaitingMs, sumTurnaroundMs, sumResourceWaitMs = 0 ;
    private int resourceJobCount = 0 ;
    

    /** บันทึกว่างานชิ้นหนึ่งเสร็จแล้ว เรียกโดย Worker หลายตัวพร้อมกันได้ */
 public synchronized void recordCompletion(Job job) {
    //เช็คว่างานเสร็จหรือยัง    
    if (job.actualArrivalMs < 0 || job.startTimeMs < 0 || job.completionTimeMs < 0) {
            throw new IllegalStateException(
                    "Log incomplete time-tracking entries: " + job.id
                    + " (arrival=" + job.actualArrivalMs
                    + ", start=" + job.startTimeMs
                    + ", completion=" + job.completionTimeMs + ")");
        }
        //เช็คงานซ้ำ
        if (!completedIds.add(job.id)) {
            throw new IllegalStateException("Duplicate Job: " + job.id);
        }
 
        completedJobs.add(job);
        sumWaitingMs += job.waitingTime();
        sumTurnaroundMs += job.turnaroundTime();
 
        if (job.resource != ResourceType.NONE) {
            sumResourceWaitMs += job.getResourceWaitTime();
            resourceJobCount++;
        }
    }
 
    /** จำนวนงานที่เสร็จแล้ว ใช้โดย Monitor และใช้ตรวจว่างานครบหรือยัง */
    public synchronized int completedCount() {
        return completedJobs.size();
    }
 
    /**
     * พิมพ์ตารางสรุปผลตอนจบโปรแกรม (เรียกหลัง join() Worker ครบ และหยุด Monitor แล้ว)
     *
     * @param allJobs    งานทั้งหมดจากไฟล์ workload
     * @param makespanMs เวลารวมของ simulation นับจาก logger เริ่ม จนงานสุดท้ายเสร็จ
     */
    public synchronized void printSummary(List<Job> allJobs, long makespanMs) {
        int completed = completedJobs.size();
        int total = allJobs.size();
 
        long avgWaiting = average(sumWaitingMs, completed);
        long avgTurnaround = average(sumTurnaroundMs, completed);
        long avgResourceWait = average(sumResourceWaitMs, resourceJobCount);
        //เช็คเพื่อป้องกันการหารด้วย 0 
        double throughput = makespanMs > 0 ? completed / (makespanMs / 1000.0) : 0.0;
        // เนื่องจาก completedJobs คืองานที่ทำเสร็จและจัดเก็บตามจังหวะ Thread 
        List<Job> sorted = new ArrayList<>(completedJobs);
        sorted.sort(Comparator.comparingInt((Job j) -> j.sequence)); //--> นำมาเรียงตามลำดับ csv ให้อ่านง่าย
 
        // ตารางตรวจสอบสมการ   Turnaround = Waiting + workMs + Resource Wait + resourceMs
        System.out.println("\n=============== PER-JOB CHECK  ===============");
        System.out.printf(Locale.ROOT, "%-8s %7s %7s %7s %7s | %7s %9s %6s  %s%n",
                "job", "WT", "work", "RW", "res", "TAT", "expected", "diff", "status");
 
        int outOfTolerance = 0;
        long maxAbsDiff = 0;
        for (Job job : sorted) {
            long wt = job.waitingTime();
            long rw = job.getResourceWaitTime();
            long tat = job.turnaroundTime(); //ค่าที่วัดได้จริง  (completion − arrival)
            long expected = wt + job.workMs + rw + job.resourceMs;
            long diff = tat - expected;
            boolean ok = Math.abs(diff) <= TOLERANCE_MS;
            if (!ok) {
                ++outOfTolerance;
            }
            maxAbsDiff = Math.max(maxAbsDiff, Math.abs(diff));
            System.out.printf(Locale.ROOT, "%-8s %7d %7d %7d %7d | %7d %9d %6d  %s%n",
                    job.id, wt, job.workMs, rw, job.resourceMs, tat, expected, diff,
                    ok ? "OK" : "CHECK");
        }
 
        System.out.println();
        System.out.println("=============== SUMMARY ===============");
        System.out.printf(Locale.ROOT, "Completed jobs      : %d / %d%n", completed, total);
        System.out.printf(Locale.ROOT, "Simulation time     : %d ms%n", makespanMs);
        System.out.printf(Locale.ROOT, "Avg Waiting Time    : %d ms%n", avgWaiting);
        System.out.printf(Locale.ROOT, "Avg Turnaround Time : %d ms%n", avgTurnaround);
        System.out.printf(Locale.ROOT, "Avg Resource Wait   : %d ms (เฉลี่ยจาก %d งานที่ใช้ resource)%n",
                avgResourceWait, resourceJobCount);
        System.out.printf(Locale.ROOT, "Throughput          : %.2f jobs/second%n", throughput);
        System.out.printf(Locale.ROOT, "Equation check      : max |diff| = %d ms, เกิน %d ms: %d งาน%n",
                maxAbsDiff, TOLERANCE_MS, outOfTolerance);
        // เตื้อนเมื่อมีงานค้าง --> เอา job id ที่ไม่อยู่ใน completedIds มารายงาน
        if (completed != total) {
            List<String> missing = new ArrayList<>();
            for (Job job : allJobs) {
                if (!completedIds.contains(job.id)) {
                    missing.add(job.id);
                }
            }
            System.out.printf(Locale.ROOT, "WARNING: ยังไม่เสร็จ %d งาน: %s%n", missing.size(), missing);
        }
 
        // บรรทัดเดียวสำหรับ grep จาก raw log ไปกรอกตารางผลลัพธ์ในหัวข้อ 14
        System.out.printf(Locale.ROOT,
                "RESULT avgWT=%d avgTAT=%d throughput=%.2f avgRW=%d%n",
                avgWaiting, avgTurnaround, throughput, avgResourceWait);
        System.out.println("=======================================");
    }
 
    /** ค่าเฉลี่ยเป็นจำนวนเต็ม ms (ปัดเศษ) ถ้าไม่มีข้อมูลคืน 0 เพื่อกันหารศูนย์ */
    private static long average(long sum, int count) {
        return count == 0 ? 0 : Math.round((double) sum / count);
    }
}
/*ตัวอย่าง Job ที่ใช้ PRINTER
         arrival=100, start=110, completion=4015, workMs=2400, RW=300, resourceMs=1200
         WT = 110 − 100 = 10, TAT = 4015 − 100 = 3915
        expected = 10 + 2400 + 300 + 1200 = 3910
         diff = 3915 − 3910 = 5 ms → OK (ส่วนต่างเล็กน้อยมาจาก sleep() ที่หลับเกินเวลาที่ขอนิดหน่อย) */
    
                /**
     * พิมพ์ตารางสรุปผลตอนจบโปรแกรม
     * อย่างน้อยต้องมี avg Waiting Time, avg Turnaround Time,
     * Throughput และ avg Resource Wait Time
     *
     * ตามหัวข้อ 14 ให้รายงานเวลาเป็นจำนวนเต็มหน่วย ms
     * และ Throughput อย่างน้อย 2 ตำแหน่งทศนิยม
     */

