# Mini Job Scheduler & Shared Resource Manager — คู่มือเตรียมนำเสนอ

> เอกสารนี้อธิบายการทำงานทั้งระบบ ตัวแปรสำคัญ กลไก Thread-safety และคำถามที่คาดว่าจะถูกถามใน Demo

---

## 1. ระบบนี้ทำอะไร

จำลอง "ระบบจัดคิวงาน" ที่มี
- **งาน (Job)** เข้ามาตามเวลา `arrivalMs` ที่กำหนดในไฟล์ CSV
- **Scheduler** จัดงานเข้าคิวตามนโยบาย `FCFS` หรือ `PRIORITY`
- **Worker หลายตัว** (Thread) หยิบงานไปทำพร้อมกัน
- งานบางชิ้นต้องใช้ **ทรัพยากรร่วม** (PRINTER / DATABASE) ซึ่งจำกัดจำนวนด้วย **Semaphore**
- **Monitor** รายงานสถานะทุก ~1 วินาที
- จบด้วย **Statistics** สรุปผล (Waiting, Turnaround, Resource Wait, Throughput)

คำสั่งรัน
```
java Main <workload.csv> <fcfs|priority> <workers> <printerPermits> <databasePermits>
java Main jobs_standard.csv priority 3 1 2
```

---

## 2. สถาปัตยกรรมและการไหลของงาน

```
CSV ──► WorkloadLoader ──► List<Job> ──► Main
                                           │
 JobGenerator ──arrivalQueue──► Scheduler ──ReadyQueue──► Worker × N
 (รอถึง arrivalMs) (LinkedBlockingQueue)  (จัดคิว)  (PriorityQueue+Lock)  │
                                                          ┌──────────────┤
                                                          ▼              ▼
                                                  ResourceManager     Statistics
                                                    (Semaphore)    (recordCompletion)
 Monitor ── อ่าน ReadyQueue / Statistics / ResourceManager ทุก 1 วินาที
 ProjectLogger ── นาฬิกา now() ตัวเดียวและ log ที่ทุก Thread ใช้ร่วมกัน
```

### Thread ทั้งหมดในระบบ

| Thread | ชื่อ | หน้าที่ | จบเมื่อ |
|---|---|---|---|
| main | main | สร้างทุกอย่าง, start, join, สรุปผล | หลัง join ครบ |
| JobGenerator | generator | ปล่อยงานตามเวลา | ปล่อยครบแล้วส่ง POISON |
| Scheduler | scheduler | รับงานจาก arrivalQueue ใส่ ReadyQueue | เจอ POISON (แล้ว close ReadyQueue) |
| Worker × N | worker-1..N | take งาน → ทำ → ใช้ resource → บันทึกผล | `take()` คืน `null` |
| Monitor | monitor | พิมพ์สถานะทุก 1 วินาที | ถูก `interrupt()` จาก shutdown |

---

## 3. ตัวแปรและวัตถุสำคัญ (และส่งไปที่ไหน)

### 3.1 วัตถุที่ Main สร้างแล้วแจกจ่าย

| วัตถุ | ส่งไปที่ | ใช้ทำอะไร |
|---|---|---|
| `config` | WorkloadLoader, ResourceManager, ReadyQueue, Logger | ค่าจาก command line |
| `jobs` | JobGenerator, Statistics.printSummary, computeMakespan | งานทั้งหมดจาก CSV (แก้ไขไม่ได้) |
| `logger` | ทุก Thread | นาฬิกาเดียว + พิมพ์ log |
| `arrivalQueue` | JobGenerator (put) → Scheduler (take) | ท่อส่งงานเข้าระบบ |
| `readyQueue` | Scheduler (add/close), Worker (take), Monitor (size), Main (close) | คิวงานที่พร้อมทำ |
| `resources` | Worker (acquire/release), Monitor (status) | Semaphore ของทรัพยากร |
| `statistics` | Worker, Monitor, Main | ตัวนับ running/completed + ผลรวมสถิติ |
| `started` | startThread, shutdown | รายชื่อ Thread ที่ start สำเร็จจริง |
| `crashHandler` | Thread ทุกตัว | บันทึก THREAD_CRASH ลง log |

### 3.2 ฟิลด์ใน Job

| ฟิลด์ | ชนิด | ใครเขียน | ใครอ่าน | ความหมาย |
|---|---|---|---|---|
| `id` | final | Loader | ทุกคน | รหัสงาน |
| `arrivalMs` | final | Loader | JobGenerator | เวลาที่ *ควร* เข้าระบบ |
| `priority` | final | Loader | ReadyQueue | น้อย = สำคัญ (1 สูงสุด) |
| `workMs` | final | Loader | Worker | ระยะเวลางานหลัก |
| `resource` | final | Loader | Worker | NONE / PRINTER / DATABASE |
| `resourceMs` | final | Loader | Worker | เวลาถือครอง resource |
| `sequence` | final | Loader | JobGenerator, Statistics | ลำดับในไฟล์ (เริ่ม 0) |
| `actualArrivalMs` | **volatile** | JobGenerator | ReadyQueue (Comparator), Statistics | เวลาเข้าระบบจริง |
| `startTimeMs` | **volatile** | Worker | Statistics | เวลา Worker เริ่มทำ |
| `resourceWaitStartMs` | **volatile** | Worker | — | เวลาเริ่มรอ Semaphore |
| `resourceWaitMs` | **volatile** | Worker | Statistics | เวลารอ Semaphore รวม |
| `completionTimeMs` | **volatile** | Worker | Statistics, Main | เวลาเสร็จ |

> ฟิลด์ `final` ปลอดภัยโดยธรรมชาติ (เขียนครั้งเดียวใน constructor) ส่วนฟิลด์วัดผลเขียนโดย Thread หนึ่ง อ่านโดยอีก Thread หนึ่ง จึงใช้ `volatile` เพื่อรับประกัน visibility

### 3.3 ตัวแปรภายในคลาสสำคัญ

| คลาส | ตัวแปร | ความหมาย / การป้องกัน |
|---|---|---|
| ReadyQueue | `queue` (PriorityQueue) | เก็บงาน เรียงตาม Comparator แตะใต้ `lock` เท่านั้น |
| ReadyQueue | `lock` (ReentrantLock) | กุญแจให้ทีละ Thread |
| ReadyQueue | `notEmpty` (Condition) | จุดพักของ Worker เมื่อคิวว่าง |
| ReadyQueue | `closed` | flag "ไม่มีงานเข้าอีกแล้ว" แตะใต้ lock |
| ResourceManager | `semaphores` (EnumMap) | Semaphore แบบ `fair=true` ต่อชนิด resource |
| ResourceManager | `totals` | จำนวน permit ทั้งหมด ไว้แสดงสถานะ |
| Statistics | `runningIds` | id ของงานที่กำลังทำ |
| Statistics | `completedJobs`, `completedIds` | งานที่เสร็จ / กันบันทึกซ้ำ |
| Statistics | `sumWaitingMs`, `sumTurnaroundMs` | ผลรวมสำหรับหาค่าเฉลี่ย |
| Statistics | `sumResourceWaitMs`, `resourceJobCount` | ผลรวมและตัวหารของ avg RW (นับเฉพาะงานที่ใช้ resource) |
| Statistics | `TOLERANCE_MS = 50` | ส่วนต่างที่ยอมรับได้ของสมการตรวจสอบ |
| ProjectLogger | `startNanos` | เวลาฐานของ `now()` |
| ProjectLogger | `writeLock` | กัน log ซ้อนกัน |
| JobGenerator | `POISON` | สัญญาณ "ปล่อยงานครบแล้ว" (เทียบด้วย `==`) |

---

## 4. อธิบายทีละคลาส

### 4.1 Config (อาจารย์แจก)
- `parse(args)` ตรวจ argument 5 ตัว: ไฟล์, นโยบาย, workers, printer, database (ตัวเลขต้อง ≥ 1) ผิดแล้วโยน `IllegalArgumentException`
- `enum Policy {FCFS, PRIORITY}` ส่งให้ ReadyQueue เลือก Comparator
- `describe()` สร้างข้อความบรรทัดเดียวสำหรับ log ตอนเริ่ม

### 4.2 ResourceType / WorkloadFormatException / WorkloadLoader (อาจารย์แจก)
- `ResourceType.parse` แปลงข้อความเป็น enum (ไม่สนตัวพิมพ์)
- `WorkloadLoader.load` อ่าน CSV: ตัด BOM, ข้ามบรรทัดว่างและหัวตาราง, แยก 6 คอลัมน์, ตรวจค่า, ตรวจ id ซ้ำ, ตรวจกติกา NONE⇒resourceMs=0 / อื่นๆ⇒>0
- ใช้ `jobs.size()` เป็น `sequence`
- คืนลิสต์ **unmodifiable** จึงต้องทำสำเนาก่อน sort
- ถ้าผิดจะโยน `WorkloadFormatException` พร้อมเลขบรรทัด

### 4.3 Job
เก็บข้อมูลงาน + ฟิลด์วัดผล (volatile) พร้อมเมธอดคำนวณ
- `waitingTime()` = `startTimeMs − actualArrivalMs`
- `turnaroundTime()` = `completionTimeMs − actualArrivalMs`
- `getResourceWaitTime()` = `resourceWaitMs`

### 4.4 ProjectLogger (อาจารย์แจก)
- `now()` = `(System.nanoTime() − startNanos)/1,000,000` เป็นนาฬิกาเดียวของระบบ (ห้ามปนกับ `currentTimeMillis`)
- `write()` จัดรูปแบบและพิมพ์ใต้ `synchronized(writeLock)` บรรทัดจึงไม่ซ้อนกัน
- มีเมธอดเหตุการณ์ต่างๆ เช่น `jobArrived`, `jobStarted`, `monitor`, `systemEvent`

### 4.5 JobGenerator
1. constructor: ทำสำเนาลิสต์ เรียงตาม `arrivalMs` แล้ว `sequence`
2. `run()` วนทีละ job
   - `remaining = job.arrivalMs − logger.now()` ถ้า > 0 → `Thread.sleep(remaining)` และวนเช็กซ้ำ (กัน sleep ตื่นเร็ว และไม่สะสมความคลาดเคลื่อนเพราะคำนวณจากนาฬิกากลางทุกรอบ)
   - เซ็ต `actualArrivalMs = logger.now()` **ก่อน** `out.put(job)`
   - `logger.jobArrived(job)`
   - `out.put(job)` ส่งให้ Scheduler
3. ครบแล้ว `out.put(POISON)`

### 4.6 Scheduler
- `inbox.take()` รอแบบถูกพัก ไม่กิน CPU
- ถ้า `job == JobGenerator.POISON` → `break`
- ไม่ใช่ → `readyQueue.add(job)` + log ขนาดคิว
- `finally { readyQueue.close(); }` เสมอ ไม่ว่าจบแบบไหน เพื่อปลุก Worker ที่หลับ
- เหตุผลที่ต้องมี Scheduler: โจทย์บังคับห้าม JobGenerator ใส่ ReadyQueue โดยตรง

### 4.7 ReadyQueue
- **นโยบายต่างกันแค่ Comparator**
  - FCFS: `actualArrivalMs` → `id`
  - PRIORITY: `priority` → `actualArrivalMs` → `id`
  - tie-break ใช้ข้อมูลของ Job เท่านั้น ผลจึงเหมือนเดิมทุกครั้งไม่ขึ้นกับว่า Thread ใดมาก่อน
- `add(job)`: lock → ถ้า closed โยน exception → `offer` → `notEmpty.signal()` → unlock (finally)
- `take()`: `lockInterruptibly()` → `while (queue.isEmpty())` { ถ้า closed คืน `null`; ไม่งั้น `await()` } → `poll()`
  - ใช้ `while` ไม่ใช่ `if` เพราะ Thread อาจตื่นเอง (spurious wakeup) หรือมี Worker อื่นแย่งงานไปก่อน
  - `await()` ปล่อยล็อกและพัก จึงไม่ busy waiting
- `close()`: `closed = true` + `signalAll()` ปลุก Worker ทุกตัว งานที่ค้างยังถูกหยิบจนหมดก่อน
- `size()` / `snapshot()` อ่านใต้ lock
- **ทำไมไม่ใช้ PriorityBlockingQueue + poison pill:** poison ต้องถูกจัดลำดับตาม Comparator ด้วย อาจถูกหยิบก่อนงานจริง flag `closed` ตรงไปตรงมากว่า

### 4.8 ResourceManager
- Semaphore `fair = true` ต่อ PRINTER และ DATABASE จำนวน permit จาก Config
- `acquire(type)` รอจนได้ (ถูก interrupt ได้), `release(type)` คืน permit
- `status()` เช่น `printer=1/1 database=0/2`
- **Worker ทุกตัวใช้ object เดียวกัน**

### 4.9 Worker (ลำดับ 5 ขั้นตามโจทย์)
```
run():  while ((job = readyQueue.take()) != null) {
            statistics.jobStarted(job);          // ขึ้นทะเบียน running
            try { processJob(job); }
            finally { statistics.jobFinished(job); }   // ถอนทะเบียนเสมอ
        }
processJob(job):
  1. startTimeMs = now();  logger.jobStarted(job)
  2. Thread.sleep(workMs)                         // งานหลัก
  3. ถ้า resource != NONE:
        resourceWaitStartMs = now()
        resources.acquire(resource)
        resourceWaitMs = now() − resourceWaitStartMs
        try { Thread.sleep(resourceMs) }          // ถือครอง
        finally { resources.release(resource) }   // คืนเสมอ
  4. completionTimeMs = now();  statistics.recordCompletion(job)
```
- **ห้ามสลับขั้น 2 กับ 3** (ผลของทุกกลุ่มต้องเทียบกันได้)
- `acquire` อยู่ **นอก** try: ถ้า acquire ล้มเหลวหรือถูก interrupt ขณะรอ ยังไม่ได้ permit จึงไม่ต้องคืน (ถ้าอยู่ใน try จะ release เกิน)
- Worker หยุดเมื่อ `take()` คืน `null` (คิวว่างและปิดแล้ว)

### 4.10 Statistics
- ทุกเมธอดที่แตะข้อมูลสะสมเป็น `synchronized` (ล็อกเดียวคือ `this`) เพราะ `+=` ไม่ atomic; `volatile` ช่วยเรื่อง lost update ไม่ได้
- `recordCompletion(job)`: ตรวจว่าเวลาครบและไม่ซ้ำ → ถอนจาก `runningIds` + เพิ่ม `completedJobs` + บวกผลรวม **ในบล็อกเดียว** Monitor จึงไม่เห็นงานที่ "หายไป" หรือ "ถูกนับซ้ำ"
- `jobFinished` เป็น idempotent: เรียกซ้ำหลัง `recordCompletion` ไม่มีผล running ไม่ติดลบ และงานที่ล้มเหลวไม่ทำให้ running ค้าง
- `snapshot()` คืน running+completed ใต้ล็อกเดียว
- `printSummary`: ตาราง PER-JOB CHECK, START ORDER, Completion order, SUMMARY, บรรทัด `RESULT ...`

### 4.11 Monitor
```java
while (!isInterrupted()) {
    logger.monitor(readyQueue.size(), statistics.runningCount(),
                   statistics.completedCount(), resources.status());
    Thread.sleep(1000);
}
```
ถูก interrupt ตอน shutdown → ออกจากลูป

### 4.12 Main (Controller)
1. `Config.parse` → ผิดพิมพ์ error + USAGE แล้ว `return`
2. `WorkloadLoader.load` → ผิดหรือว่างแล้ว `return`
3. สร้าง `logger` (เริ่มจับเวลา) + `systemStart`
4. สร้างส่วนประกอบทั้งหมด (ยังไม่มี Thread เริ่ม)
5. start: Worker → Scheduler → Monitor → JobGenerator (ฝั่งรับก่อน ฝั่งผลิตทีหลัง)
6. รอตามทิศทางงานไหล
   - `generator.join()` → `arrivalQueue.offer(POISON)` (กัน generator ตายก่อนส่ง; ส่งซ้ำไม่เสียหาย)
   - `scheduler.join()` → `readyQueue.close()` (กัน Scheduler ตายก่อน close)
   - `join()` Worker ทุกตัว
7. `finally { shutdown(started); }` เสมอ: interrupt Thread ที่ยังอยู่ (ปกติคือ Monitor) แล้ว join แบบทนการถูก interrupt
8. สรุปผล: `computeMakespan` (completionTimeMs สูงสุด), ตรวจ 3 เงื่อนไข (งานไม่ครบ, ReadyQueue เหลือ, running ≠ 0), `printSummary`, `systemStop`
9. **ไม่เรียก `System.exit()`** และไม่เดาเวลาด้วย `sleep`

---

## 5. หลักการปิดระบบ (Shutdown)

| ขั้น | สัญญาณ | ผู้ส่ง → ผู้รับ |
|---|---|---|
| 1 | `JobGenerator.POISON` ใน arrivalQueue | JobGenerator → Scheduler |
| 2 | `readyQueue.close()` | Scheduler → Worker (ผ่าน `take()` คืน null) |
| 3 | `join()` Worker ครบ | Main |
| 4 | `interrupt()` Monitor แล้ว `join()` | Main |
| 5 | เทียบ `completedCount` กับ `jobs.size()` | Main |

arrivalQueue เป็น FIFO จึงรับประกันว่า POISON มาถึง **หลัง** งานจริงทุกชิ้น

---

## 6. กลไก Concurrency ที่ใช้ และเหตุผล

| ปัญหา | กลไก | ที่ไหน |
|---|---|---|
| ส่งงานข้าม Thread โดยไม่ busy wait | `LinkedBlockingQueue` | arrivalQueue |
| คิวถูกแก้ไขพร้อมกัน + รอแบบพัก | `ReentrantLock` + `Condition` | ReadyQueue |
| จำกัดจำนวนผู้ใช้ resource | `Semaphore(fair=true)` | ResourceManager |
| ค่าที่ Thread หนึ่งเขียน อีก Thread อ่าน | `volatile` | ฟิลด์วัดผลใน Job |
| ผลรวม/ตัวนับที่หลาย Thread แก้ | `synchronized` | Statistics |
| log ไม่ซ้อนกัน | `synchronized(writeLock)` | ProjectLogger |
| permit ไม่ค้างเมื่อ exception/interrupt | `try/finally` | Worker |
| Thread ตายเงียบๆ | `UncaughtExceptionHandler` | Main |

### ความสัมพันธ์ happens-before ที่ควรอธิบายได้
`actualArrivalMs` ถูกเขียนก่อน `out.put(job)` และ Scheduler อ่านหลัง `take()` การ put/take ของ BlockingQueue สร้าง happens-before จึงแน่ใจว่า ReadyQueue เห็นค่าถูกต้องตอนเปรียบเทียบใน Comparator นอกจากนี้ยังเป็น `volatile` ซ้ำอีกชั้น

---

## 7. การวัดผล

นาฬิกาเดียว: `logger.now()` ทุกค่าที่วัดจึงตรวจย้อนกลับกับ log ได้

| ค่า | สูตร |
|---|---|
| Waiting Time (WT) | `startTime − actualArrivalTime` |
| Turnaround (TAT) | `completionTime − actualArrivalTime` |
| Resource Wait (RW) | เวลาเริ่มรอ Semaphore จน acquire สำเร็จ (NONE = 0) |
| Throughput | งานที่เสร็จ ÷ (makespan/1000) jobs/sec |
| Makespan | `completionTimeMs` สูงสุด |

**สมการตรวจสอบ:** `TAT = WT + workMs + RW + resourceMs`
เวลาจริงจะเกินเล็กน้อยเพราะ `sleep()` หลับเกินที่ขอ จึงแสดง diff และทำเครื่องหมาย CHECK เมื่อเกิน 50 ms

- avg WT, avg TAT: เฉลี่ยจากงานที่เสร็จทุกตัว
- avg RW: เฉลี่ยเฉพาะงานที่ใช้ resource

---

## 8. ตัวอย่างไทม์ไลน์ (แนวคิด)

สมมติ 1 Worker, printer=1, งาน J1 (arrive 0, work 1000, PRINTER 500)

```
0 ms     JOB_ARRIVED J1  (actualArrivalMs ≈ 0)
~0 ms    Scheduler add → ReadyQueue, Worker take → startTimeMs ≈ 0
1000 ms  งานหลักเสร็จ → resourceWaitStartMs → acquire ได้ทันที (RW ≈ 0)
1500 ms  release → completionTimeMs ≈ 1500
WT≈0, TAT≈1500 = 0 + 1000 + 0 + 500 ✔
```

---

## 9. คำถามที่น่าจะโดนถามใน Demo (พร้อมแนวตอบ)

1. **ทำไมต้องมี Scheduler แยก?** โจทย์บังคับ และแยกหน้าที่ "ปล่อยงาน" ออกจาก "จัดลำดับ"
2. **Worker ที่ไม่มีงานทำอะไร?** `await()` บน Condition ถูกพักและปล่อยล็อก ไม่ busy waiting
3. **ทำไม `while` แทน `if` ใน `take()`?** spurious wakeup และกรณี Worker อื่นแย่งงานก่อน
4. **Worker รู้ได้อย่างไรว่าควรหยุด?** `take()` คืน `null` เมื่อคิวว่างและ `closed` แล้ว งานค้างจะถูกทำจนหมดก่อน
5. **ทำไมไม่ใช้ `sleep` รอให้งานเสร็จ?** เดาเวลาไม่ได้ ใช้ `join()` และสัญญาณแทน
6. **Tie-break ของ Priority คืออะไร?** `priority → actualArrivalMs → id` ใช้ข้อมูลของ Job ไม่ขึ้นกับ Thread
7. **ทำไม Semaphore ต้อง `fair = true`?** ให้ Thread ที่รอก่อนได้ก่อน (FIFO) ถ้าเป็น `false` Thread ที่มาทีหลังอาจแซง ทำให้บางงานรอนานผิดปกติ (starvation) และ RW ไม่เป็นธรรม
8. **ถูก interrupt หลัง acquire แต่ก่อน release?** `try/finally` ทำให้ `release` ถูกเรียกเสมอ permit ไม่ค้าง
9. **ทำไม `volatile` ใน Job ไม่พอสำหรับ Statistics?** `+=` ไม่ atomic (read-modify-write) volatile แก้ได้แค่ visibility จึงใช้ `synchronized`
10. **running counter อยู่ที่ไหน ใครเพิ่ม/ลด?** อยู่ใน Statistics (`runningIds`) Worker เพิ่มด้วย `jobStarted` และลดด้วย `jobFinished`/`recordCompletion` ภายใต้ล็อกเดียวกับ completed
11. **ทำไมใช้ `nanoTime` ไม่ใช่ `currentTimeMillis`?** monotonic ไม่กระโดดเมื่อปรับนาฬิการะบบ และต้องฐานเดียวกับ log
12. **ถ้า JobGenerator หรือ Scheduler ตายกลางทาง?** Main ส่ง POISON ซ้ำ / `close()` ซ้ำเป็นตัวสำรอง และ shutdown ใน finally ทำให้ไม่มี Thread ค้าง

---

## 10. จุดอ่อน/ข้อสังเกตที่ควรเตรียมไว้ (ตรวจจากโค้ดปัจจุบัน)

1. **Monitor ยังไม่ใช่ snapshot เดียวกัน**: เรียก `runningCount()` กับ `completedCount()` แยกกัน อาจเห็นจากคนละจังหวะ ควรเปลี่ยนเป็น `Statistics.snapshot()` (ส่วน `ready` ยังอยู่คนละล็อกกับ Statistics ซึ่งเป็นข้อจำกัดที่อธิบายได้)
2. **ค่าคงที่ซ้ำซ้อน**: `Job.POISON` และ `Scheduler.END` ประกาศไว้แต่ไม่ถูกใช้ ตัวที่ใช้จริงคือ `JobGenerator.POISON`
3. **คอมเมนต์ไม่ตรงโค้ด**: คอมเมนต์ใน `ReadyQueue.close()` เขียนว่าเรียกโดย JobGenerator แต่จริงๆ เรียกโดย Scheduler (Main เป็นตัวสำรอง) และคอมเมนต์ใน Scheduler พูดถึง `Scheduler.END`
4. **เมธอด log ที่ยังไม่ถูกเรียก**: `workFinished`, `resourceWaitStarted`, `resourceAcquired`, `resourceReleased`, `jobCompleted` มีใน ProjectLogger แต่ Worker เรียกแค่ `jobStarted` ถ้าต้องการให้ log ครบเหตุการณ์ควรเพิ่มการเรียกใน `processJob` (ไม่กระทบการวัดผล)
5. **การเรียงลิสต์ซ้ำ**: Main และ JobGenerator เรียงลิสต์เหมือนกัน ไม่ผิดแต่ซ้ำ
6. **`snapshot()` ใน ReadyQueue** ยังไม่มีใครเรียก

---

## 11. เช็กลิสต์ก่อนนำเสนอ

- [ ] วาดแผนภาพการไหลของงานได้ (หัวข้อ 2)
- [ ] อธิบายสัญญาณปิดระบบ 3 ขั้นได้ (POISON → close → join)
- [ ] อธิบายได้ว่าทำไมแต่ละกลไก (`volatile` / `synchronized` / Lock+Condition / Semaphore) อยู่ตรงจุดนั้น
- [ ] อธิบายสมการ TAT และทำไม diff ไม่เป็นศูนย์
- [ ] รันเทียบ fcfs กับ priority และอธิบายลำดับ START ORDER ที่ต่างกัน
- [ ] รันด้วย permit น้อยและมาก เพื่อให้เห็นผลต่อ Resource Wait
- [ ] ตอบคำถามหัวข้อ 9 ได้ครบ และรู้จุดอ่อนในหัวข้อ 10
