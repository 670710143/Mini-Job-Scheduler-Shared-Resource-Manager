
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * รวบรวมและคำนวณค่าที่ใช้วัดผลของการรันหนึ่งครั้ง (หัวข้อ 8 ของโจทย์)
 *
 * นิยามที่ใช้ (ตรงกับโจทย์): Waiting Time = startTime - actualArrivalTime
 * (Job.waitingTime()) Turnaround Time = completionTime - actualArrivalTime
 * (Job.turnaroundTime()) Resource Wait = เวลาเริ่มรอ Semaphore จน acquire
 * สำเร็จ (resource=NONE ให้เป็น 0) Throughput = จำนวน Job ที่เสร็จ / เวลารวมของ
 * simulation (jobs/second)
 *
 * การเฉลี่ย: avg WT, avg TAT : เฉลี่ยจาก Job ที่เสร็จทุกตัว avg RW :
 * เฉลี่ยเฉพาะ Job ที่ขอ PRINTER หรือ DATABASE (resource != NONE)
 *
 * ความปลอดภัยของ Thread: recordCompletion() ถูกเรียกจาก Worker หลายตัวพร้อมกัน
 * ทุกเมธอดที่แตะข้อมูลสะสม จึงเป็น synchronized (ล็อกเดียวคือ this) เพราะการ +=
 * ไม่ atomic ถ้าไม่ล็อกจะเกิด lost update ซึ่ง volatile ช่วยไม่ได้
 *
 * สมการตรวจสอบรายงาน: TAT = WT + workMs + RW + resourceMs
 * เวลาจริงจะเกินสมการเล็กน้อยเสมอ เพราะ sleep() หลับเกินที่ขอ จึงแสดงส่วนต่าง
 * (diff) ต่อ Job และทำเครื่องหมาย CHECK เมื่อเกิน TOLERANCE_MS
 */


      public class Statistics {

        /**
         * ส่วนต่างสูงสุดที่ยอมรับได้ต่อสมการหนึ่ง Job (ms) ปรับได้ตามเครื่อง
         */
        private static final long TOLERANCE_MS = 50;

        // ----- ข้อมูลสะสม: อ่าน/เขียนภายใต้ synchronized (this) เท่านั้น -----
        private final List<Job> completedJobs = new ArrayList<>();
        private final Set<String> completedIds = new HashSet<>();
        private long sumWaitingMs = 0;
        private long sumTurnaroundMs = 0;
        private long sumResourceWaitMs = 0;   // รวมเฉพาะ Job ที่ใช้ resource
        private int resourceJobCount = 0;     // ตัวหารของ avg Resource Wait
        private final Set<String> runningIds = new HashSet<>();   // id ของงานที่ Worker กำลังทำ (ใต้ล็อกเดียวกับ completed)
        private final Set<String> readyIds = new HashSet<>();   // งานที่รอ Worker

        /**
         * ภาพรวม ณ ขณะเดียวกันของ running กับ completed (อ่านใต้ล็อกเดียว
         * จึงสอดคล้องกัน)
         */
        public static final class Snapshot {

            public final int ready;
            public final int running;
            public final int completed;

            private Snapshot(int ready, int running, int completed) {
                this.ready = ready;
                this.running = running;
                this.completed = completed;
            }
        }

    /**
     * Scheduler เรียก "ก่อน" readyQueue.add() เพื่อให้งานถูกนับเป็น ready
     * ก่อนที่ Worker จะหยิบได้
     */
    public synchronized void jobReady(Job job) {
        readyIds.add(job.id);
    }

    /**
     * ใช้ถอยกลับเมื่อ readyQueue.add() ล้มเหลว (เช่นคิวถูกปิด)
     */
    public synchronized void jobReadyCancelled(Job job) {
        readyIds.remove(job.id);
    }

    /**
     * Worker เรียกหลังหยิบงาน: ย้ายจาก ready ไป running ในบล็อกเดียว
     */
    public synchronized void jobStarted(Job job) {
        readyIds.remove(job.id);
        runningIds.add(job.id);
    }

    /**
     * Worker เรียกใน finally ทุกครั้งที่จบการทำงานหนึ่งงาน (สำเร็จ, exception
     * หรือถูก interrupt) idempotent: ถ้า recordCompletion()
     * ถอนงานนี้ออกจากทะเบียนไปแล้ว การเรียกซ้ำไม่มีผล
     * จึงไม่มีทางลดตัวนับซ้ำจนติดลบ และงานที่ไม่เสร็จก็ไม่ทำให้ running ค้าง
     */
    public synchronized void jobFinished(Job job) {
        runningIds.remove(job.id);
    }

    /**
     * จำนวนงานที่กำลังทำ ใช้โดย Monitor
     */
    public synchronized int runningCount() {
        return runningIds.size();
    }

    /**
     * running และ completed ในภาพเดียวกัน ใช้โดย Monitor
     */
    public synchronized Snapshot snapshot() {
        return new Snapshot(readyIds.size(), runningIds.size(), completedJobs.size());
    }

    /**
     * บันทึกว่างานชิ้นหนึ่งเสร็จแล้ว เรียกโดย Worker หลายตัวพร้อมกันได้
     * เงื่อนไข: Worker ต้องเซ็ต actualArrivalMs, startTimeMs, completionTimeMs
     * ครบก่อนเรียก
     */
    public synchronized void recordCompletion(Job job) {
        if (job.actualArrivalMs < 0 || job.startTimeMs < 0 || job.completionTimeMs < 0) {
            throw new IllegalStateException(
                    "บันทึกงานที่ยังวัดเวลาไม่ครบ: " + job.id
                    + " (arrival=" + job.actualArrivalMs
                    + ", start=" + job.startTimeMs
                    + ", completion=" + job.completionTimeMs + ")");
        }
        if (!completedIds.add(job.id)) {
            throw new IllegalStateException("บันทึกงานซ้ำ: " + job.id);
        }

        // ถอนออกจาก running และเพิ่ม completed ในบล็อก synchronized เดียวกัน
        // Monitor จึงไม่มีทางเห็นงานที่ "หายไป" หรือ "ถูกนับซ้ำ" ระหว่างสองค่านี้
        runningIds.remove(job.id);
        completedJobs.add(job);
        sumWaitingMs += job.waitingTime();
        sumTurnaroundMs += job.turnaroundTime();

        if (job.resource != ResourceType.NONE) {
            sumResourceWaitMs += job.getResourceWaitTime();
            resourceJobCount++;
        }
    }

    /**
     * จำนวนงานที่เสร็จแล้ว ใช้โดย Monitor และใช้ตรวจว่างานครบหรือยัง
     */
    public synchronized int completedCount() {
        return completedJobs.size();
    }

    /**
     * พิมพ์ตารางสรุปผลตอนจบโปรแกรม (เรียกหลัง join() Worker ครบ และหยุด Monitor
     * แล้ว)
     *
     * @param allJobs งานทั้งหมดจากไฟล์ workload
     * @param makespanMs เวลารวมของ simulation นับจาก logger เริ่ม
     * จนงานสุดท้ายเสร็จ
     */
    public synchronized void printSummary(List<Job> allJobs, long makespanMs) {
        int completed = completedJobs.size();
        int total = allJobs.size();

        long avgWaiting = average(sumWaitingMs, completed);
        long avgTurnaround = average(sumTurnaroundMs, completed);
        long avgResourceWait = average(sumResourceWaitMs, resourceJobCount);
        double throughput = makespanMs > 0 ? completed / (makespanMs / 1000.0) : 0.0;

        List<Job> sorted = new ArrayList<>(completedJobs);
        sorted.sort(Comparator.comparingInt((Job j) -> j.sequence));

        System.out.println();
        System.out.println("=============== PER-JOB CHECK (TAT = WT + work + RW + resource) ===============");
        System.out.printf(Locale.ROOT, "%-8s %7s %7s %7s %7s | %7s %9s %6s  %s%n",
                "job", "WT", "work", "RW", "res", "TAT", "expected", "diff", "status");

        int outOfTolerance = 0;
        long maxAbsDiff = 0;
        for (Job job : sorted) {
            long wt = job.waitingTime();
            long rw = job.getResourceWaitTime();
            long tat = job.turnaroundTime();
            long expected = wt + job.workMs + rw + job.resourceMs;
            long diff = tat - expected;
            boolean ok = Math.abs(diff) <= TOLERANCE_MS;
            if (!ok) {
                outOfTolerance++;
            }
            maxAbsDiff = Math.max(maxAbsDiff, Math.abs(diff));
            System.out.printf(Locale.ROOT, "%-8s %7d %7d %7d %7d | %7d %9d %6d  %s%n",
                    job.id, wt, job.workMs, rw, job.resourceMs, tat, expected, diff,
                    ok ? "OK" : "CHECK");
        }
        System.out.println("=============== START ORDER ===============");
        List<Job> byStart = new ArrayList<>(completedJobs);
        byStart.sort(Comparator
                .comparingLong((Job j) -> j.startTimeMs)
                .thenComparingInt(j -> j.sequence));
        System.out.println();
        StringBuilder startOrder = new StringBuilder();
        for (Job job : byStart) {
            if (startOrder.length() > 0) {
                startOrder.append(" -> ");
            }
            startOrder.append(job.id);
        }
        System.out.println("START ORDER         : " + startOrder);

        String order = completedJobs.stream()
                .map(j -> j.id)
                .collect(Collectors.joining(" -> "));
        System.out.println("Completion order    : " + order);

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

    /**
     * ค่าเฉลี่ยเป็นจำนวนเต็ม ms (ปัดเศษ) ถ้าไม่มีข้อมูลคืน 0 เพื่อกันหารศูนย์
     */
    private static long average(long sum, int count) {
        return count == 0 ? 0 : Math.round((double) sum / count);
    }
}
