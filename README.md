# Mini Job Scheduler & Shared Resource Manager

โปรแกรมจำลองระบบรับงาน (Job) ด้วย Java Multithreading
มี Scheduler จัดลำดับงานแบบ FCFS หรือ Priority, Worker Thread หลายตัวรับงานไปทำพร้อมกัน
และทรัพยากรร่วม (PRINTER / DATABASE) ที่ควบคุมด้วย Semaphore (fair = true)

## โครงสร้างระบบ

```
JobGenerator → arrivalQueue → Scheduler → ReadyQueue → Worker × N
                                                         ├─ ResourceManager (Semaphore)
                                                         └─ Statistics + ProjectLogger
Monitor → รายงานสถานะทุก ~1,000 ms
```

| ไฟล์ | หน้าที่ |
|---|---|
| `Main.java` | สร้าง object, เริ่ม Thread, รอจนจบ, shutdown, พิมพ์สรุป |
| `JobGenerator.java` | ปล่อย Job ตามเวลา `arrivalMs` แล้วส่ง POISON เมื่อครบ |
| `Scheduler.java` | รับงานจาก arrivalQueue ใส่ ReadyQueue และปิดคิวเมื่อจบ |
| `ReadyQueue.java` | คิวงานตามนโยบาย (PriorityQueue + ReentrantLock + Condition) |
| `Worker.java` | รับงาน ทำงานหลัก ขอ/คืน resource บันทึกเวลา |
| `ResourceManager.java` | Semaphore แบบ fair ของ PRINTER และ DATABASE |
| `Statistics.java` | ตัวนับ ready/running/completed และสรุปผล (thread-safe) |
| `Monitor.java` | รายงานสถานะระบบเป็นระยะ |
| `Job.java` | ข้อมูลงานและค่าที่ใช้วัดผล |
| `Config.java`, `ProjectLogger.java`, `WorkloadLoader.java`, `ResourceType.java`, `WorkloadFormatException.java` | โค้ดตั้งต้นจากอาจารย์ |

## วิธี compile

ต้องใช้ JDK 11 ขึ้นไป (ตัวอ่าน workload ใช้ `Path.of`)

```powershell
# จากโฟลเดอร์โปรเจกต์ (Windows PowerShell)
javac -encoding UTF-8 src\*.java
```

```bash
# Linux / macOS
javac -encoding UTF-8 src/*.java
```

## วิธีรัน

```
java -cp src Main <workload.csv> <fcfs|priority> <workers> <printerPermits> <databasePermits>
```

| ลำดับ | argument | ความหมาย |
|---|---|---|
| 1 | `workload.csv` | ไฟล์ชุดงานทดสอบ |
| 2 | `fcfs` หรือ `priority` | นโยบายการจัดลำดับงาน |
| 3 | `workers` | จำนวน Worker Thread (≥ 1) |
| 4 | `printerPermits` | จำนวนสิทธิ์ใช้ PRINTER พร้อมกัน (≥ 1) |
| 5 | `databasePermits` | จำนวนสิทธิ์ใช้ DATABASE พร้อมกัน (≥ 1) |

ต้องใส่ครบทั้ง 5 ตัว ไม่มีค่า default ซ่อนอยู่ ถ้าใส่ไม่ครบหรือผิด โปรแกรมจะแสดง Usage แล้วจบก่อนเริ่ม Thread ใดๆ

### ตัวอย่าง

```
java -cp src Main workloads\jobs_standard.csv priority 3 1 2
```

### เก็บ log ลงไฟล์

สร้างโฟลเดอร์ `logs` ก่อน แล้ว redirect ผลลัพธ์

```powershell
# Windows PowerShell
java -cp src Main workloads\jobs_standard.csv priority 3 1 2 | Out-File -Encoding utf8 logs\standard_priority_w3.log
```

```bash
# Linux / macOS
java -cp src Main workloads/jobs_standard.csv priority 3 1 2 > logs/standard_priority_w3.log
```

### รูปแบบ workload CSV

```
id,arrivalMs,priority,workMs,resource,resourceMs
J01,0,5,2500,NONE,0
```

- `priority` 1 = สำคัญที่สุด
- `resource` เป็น `NONE`, `PRINTER` หรือ `DATABASE`
- `resource = NONE` ต้องมี `resourceMs = 0`

## ผลลัพธ์ที่โปรแกรมพิมพ์

1. Log เหตุการณ์ตามเวลา (`[เวลา ms] [ชื่อ Thread] EVENT รายละเอียด`) และสถานะจาก Monitor
2. ตาราง PER-JOB CHECK ตรวจสมการ `TAT = WT + workMs + RW + resourceMs`
3. START ORDER และ Completion order
4. SUMMARY: avg Waiting, avg Turnaround, avg Resource Wait, Throughput
5. บรรทัด `RESULT avgWT=... avgTAT=... throughput=... avgRW=...` สำหรับ grep ไปกรอกตารางผลลัพธ์

## การทำงานที่ควรรู้

- **Tie-break (Priority):** `priority` → `actualArrivalMs` → `id` ตัดสินจากข้อมูลของ Job ไม่ขึ้นกับว่า Thread ใดเข้าคิวก่อน (FCFS ใช้ `actualArrivalMs` → `id`)
- **การปิดระบบ:** JobGenerator ส่ง POISON → Scheduler หยุดรับงานแล้ว `close()` ReadyQueue → Worker ทำงานที่ค้างจนหมดและออกเมื่อ `take()` คืน `null` → Main `join()` ทุก Thread แล้ว interrupt Monitor ไม่ใช้ `System.exit()`
- **Resource:** acquire ก่อน try และ release ใน `finally` จึงคืน permit เสมอแม้ถูก interrupt
- **นาฬิกา:** ทุกค่าที่วัดใช้ `ProjectLogger.now()` (นับจากตอนสร้าง logger) ตรวจย้อนกลับกับ log ได้

## ข้อจำกัด

- **Job ใช้ resource ได้ไม่เกิน 1 ชนิด** (ส่วนบังคับ) ยังไม่รองรับการถือหลาย resource พร้อมกัน
- **Non-preemptive:** เมื่อ Worker รับ Job ไปแล้วจะทำจนจบ ไม่ถูกดึงกลับ
- **ไม่มี Aging / MLFQ:** Priority อาจทำให้ Job priority ต่ำรอนาน (starvation) ได้ถ้ามีงานสำคัญเข้ามาต่อเนื่อง
- **เวลาเป็นการจำลองด้วย `Thread.sleep`** ไม่ใช่ CPU burst หรือ I/O จริง และ `sleep` หลับเกินที่ขอเล็กน้อย สมการตรวจสอบจึงมีส่วนต่าง (diff) เล็กน้อย โปรแกรมยอมรับได้ไม่เกิน 50 ms ต่อ Job (`TOLERANCE_MS`) ถ้าเกินจะแสดง `CHECK`
- **Nondeterminism:** รันซ้ำด้วยค่าเดิม เวลาและลำดับบางบรรทัดใน log อาจต่างกันเล็กน้อยจากการสลับ Thread ของ OS แต่กติกาจัดลำดับและแนวโน้มของผลต้องเหมือนเดิม
- **Worker ถูก interrupt กลางงาน:** งานนั้นจะไม่ถูกส่งกลับเข้าคิวและไม่นับเป็นเสร็จ Summary จะรายงานจำนวนงานที่เสร็จไม่ครบ (permit ไม่หาย และโปรแกรมไม่ค้าง)
- **Monitor** รายงานโดยประมาณทุก ~1,000 ms ค่า ready/running/completed อ่านใต้ล็อกเดียวของ `Statistics`
- **Workload ผิดรูปแบบ:** `WorkloadLoader` ตรวจและแจ้งเลขบรรทัดที่ผิด แล้วโปรแกรมจบก่อนเริ่ม Thread
