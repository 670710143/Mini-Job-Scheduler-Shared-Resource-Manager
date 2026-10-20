# คู่มือเตรียมพรีเซนต์: MultiThread File Downloader

โปรเจกต์นี้ประกอบด้วย 3 ไฟล์ที่ทำงานร่วมกัน

| ไฟล์ | บทบาท | รันด้วย |
|---|---|---|
| `FileServer.java` | เซิร์ฟเวอร์ส่งไฟล์ (เปิด 2 พอร์ต: I/O และ NIO) | `java FileServer.java <port> <โฟลเดอร์>` |
| `FileClient.java` | ไคลเอนต์แบบ command line + โหมด benchmark | `java FileClient.java <host> <port> bench <ไฟล์>` |
| `FileClientGui.java` | ไคลเอนต์แบบหน้าจอกราฟิก (Swing) | `java FileClientGui.java` |

ต้องใช้ **Java 21 ขึ้นไป** เพราะใช้ Virtual Threads

---

## 0. ภาพรวมในประโยคเดียว

> โหลดไฟล์ใหญ่ด้วยการ **แบ่งเป็นช่วงๆ แล้วให้หลาย worker โหลดพร้อมกัน** และเทียบการส่งข้อมูลสองแบบ คือ **Traditional I/O** กับ **NIO (zero-copy)** บนไฟล์และเครื่องเดียวกัน

### ลำดับการทำงานทั้งระบบ

```
1. Client ถาม INFO  -> ได้ขนาดไฟล์
2. Client ถาม HASH  -> ได้ SHA-256 (ไว้ตรวจตอนจบ)
3. Client แบ่งไฟล์เป็น N ช่วงเท่าๆ กัน (ช่วงสุดท้ายรับเศษ)
4. เปิด N connection พร้อมกัน (1 worker = 1 virtual thread)
5. แต่ละ worker ส่ง  GET <ไฟล์> <offset> <length>
6. Server ตอบ  OK <length>  ตามด้วยข้อมูล length byte
7. Worker เขียนลงไฟล์ปลายทาง "ตามตำแหน่ง offset ของตัวเอง"
8. ครบทุกช่วง -> ตรวจขนาด + SHA-256 -> สรุปผล
```

### ภาพ port ของ server

```
พอร์ต 9000  = Traditional I/O  (ServerSocket + RandomAccessFile)
พอร์ต 9001  = NIO              (ServerSocketChannel + FileChannel.transferTo)
              ^ พอร์ต NIO = พอร์ตหลัก + 1 เสมอ
```

---

# ส่วนที่ 1: FileServer.java

## 1.1 โครงสร้างการทำงาน

```
main ──┬─ accept-io  (platform thread) ── รอรับ connection พอร์ต 9000 ─┐
       ├─ accept-nio (platform thread) ── รอรับ connection พอร์ต 9001 ─┼─▶ virtual thread ต่อ 1 connection
       └─ console    (daemon thread)  ── อ่านคำสั่ง quit จากคีย์บอร์ด    ┘     เรียก serve(conn)
```

- Thread ที่รอ `accept()` มีแค่ 2 ตัว จึงใช้ platform thread ปกติ
- ทุก connection ที่เข้ามาได้ **virtual thread 1 ตัว** จึงเขียนโค้ดแบบ blocking ได้ตรงๆ แต่รองรับ connection จำนวนมาก
- พิมพ์ `quit` (หรือ `exit`, `q`) ในหน้าต่าง server เพื่อปิด

## 1.2 Protocol (ข้อความ 1 บรรทัดต่อ 1 คำสั่ง ลงท้ายด้วย `\n`)

| คำสั่ง | คำตอบ | ใช้ทำอะไร |
|---|---|---|
| `LIST` | `FILE <ชื่อ> <ขนาด>` หลายบรรทัด จบด้วย `END` | ขอรายชื่อไฟล์ |
| `INFO <ชื่อ>` | `SIZE <bytes>` หรือ `ERROR 404 ...` | ขอขนาดไฟล์ |
| `HASH <ชื่อ>` | `HASH <sha256>` | ขอค่า hash ไว้ตรวจความถูกต้อง |
| `GET <ชื่อ> <offset> <len>` | `OK <len>` + ข้อมูล `len` byte | ขอข้อมูลบางช่วง |
| `PORTS` | `PORTS <ioPort> <nioPort>` | ถามว่าพอร์ต I/O กับ NIO คือเลขอะไร (GUI ใช้เลือกพอร์ตให้เอง) |
| `QUIT` | (ปิด connection) | จบการเชื่อมต่อของ client นั้น |

connection หนึ่งส่งได้หลายคำสั่งต่อเนื่อง

## 1.3 interface `Conn`: ซ่อนความต่างของสองโหมด

```java
interface Conn extends Closeable {
    String readLine();                              // อ่านบรรทัดคำสั่ง
    void send(String text);                         // ส่งข้อความ
    void sendRange(Path file, long off, long len);  // ส่งข้อมูลไฟล์ช่วงหนึ่ง  <-- จุดที่ I/O กับ NIO ต่างกัน
    void flush();
}
```

ตัวประมวลผลคำสั่ง (`doList`, `doGet` ฯลฯ) ไม่รู้ว่าข้างใต้เป็นแบบไหน ต่างกันที่ `sendRange` เท่านั้น

## 1.4 `IoConn`: Traditional I/O

```java
raf.seek(offset);                                   // กระโดดไปกลางไฟล์
byte[] buf = new byte[BUF_SIZE];                    // 64 KB อยู่ใน user space
while (remaining > 0) {
    int n = raf.read(buf, 0, (int) Math.min(buf.length, remaining)); // kernel -> buf
    out.write(buf, 0, n);                                            // buf -> socket
    remaining -= n;
}
```

ข้อมูลเดินทาง: `ดิสก์ → page cache → buf (โปรแกรม) → socket buffer → การ์ดเครือข่าย` (คัดลอกหลายรอบ และสลับ user/kernel mode หลายครั้ง)

จุดที่ควรบอก:
- `BufferedOutputStream` 64 KB รวมข้อมูลก่อนส่ง ลดจำนวน system call
- ต้อง `flush()` หลังทุกคำสั่ง ไม่งั้นคำตอบค้างใน buffer แล้ว client รอตลอดไป
- `TCP_NODELAY` ปิด Nagle's algorithm ให้ข้อความสั้นๆ ออกทันที

## 1.5 `NioConn`: NIO + zero-copy

```java
long n = fc.transferTo(pos, remaining, sc);   // ไฟล์ -> socket โดยตรง
pos += n;  remaining -= n;                    // ส่งได้น้อยกว่าที่ขอได้ ต้องวนลูป
```

ข้อมูลเดินทาง: `ดิสก์ → page cache → socket buffer → การ์ดเครือข่าย` (ไม่ผ่านหน่วยความจำของโปรแกรมเลย)

บน Linux `transferTo` เรียก system call **`sendfile()`**

ทำไมต้องวนลูป และทำไมมี fallback:
- `transferTo` การันตีแค่ "ส่งอย่างมากเท่าที่ขอ" ถ้า socket send buffer เต็ม จะส่งได้น้อยลงหรือคืน 0
- บน virtual thread ตัว JDK ตั้ง socket เป็น non-blocking ภายใน ทำให้ `sendfile` คืน 0 ได้เมื่อ buffer เต็ม จึงสลับไปใช้ `read` + `write` ก้อนเล็ก (ที่จอด thread รอได้) แล้ววนต่อ
- ถ้า `transferTo` โยน error บนระบบนั้น จะสลับไปคัดลอกเองตลอดช่วงที่เหลือ

## 1.6 ฟังก์ชันสำคัญอื่นๆ

| ฟังก์ชัน | ทำอะไร | จุดที่ควรรู้ |
|---|---|---|
| `readLine` | อ่านทีละ byte จนเจอ `\n` | TCP เป็น byte stream ไม่มีขอบเขตข้อความ ต้องหาเส้นแบ่งเอง จำกัด 4096 byte กันบรรทัดยาวไม่จบ |
| `serve` | วนรับคำสั่งจนกว่าจะ `QUIT` | `try (c)` ปิด socket แน่นอน, `flush()` หลังทุกคำสั่ง, ตัด error จาก client ที่หลุดทิ้งเงียบๆ |
| `resolve` | แปลงชื่อไฟล์เป็น path | กัน **path traversal** (`../../etc/passwd`) 3 ชั้น: ห้ามมี `/` `\` `..`, normalize แล้วต้องอยู่ใต้โฟลเดอร์ที่แชร์, ต้องเป็นไฟล์ปกติ |
| `doHash` | คำนวณ SHA-256 | cache key = path + ขนาด + เวลาแก้ไข อ่านทีละ 1 MB ไม่โหลดทั้งไฟล์เข้า RAM |
| `doGet` | ตรวจช่วงแล้วส่ง | ตรวจ `len > size - off` (กัน integer overflow) ส่ง `OK len` + flush ก่อน แล้วค่อยส่งข้อมูล |
| `acceptIo/acceptNio` | รอรับ connection | backlog 128, `SO_REUSEADDR` ให้รีสตาร์ทแล้วจับพอร์ตเดิมได้ทันที |

---

# ส่วนที่ 2: FileClient.java (command line)

## 2.1 คำสั่งที่รองรับ

```
java FileClient.java <host> <port> list
java FileClient.java <host> <port> info  <ไฟล์>
java FileClient.java <host> <port> get   <ไฟล์> <io|nio> [workers=10] [runs=3]
java FileClient.java <host> <port> bench <ไฟล์> [runs=3]
```

`bench` รันครบ 4 กรณี คือ **IO/NIO × 1/10 workers** แล้วพิมพ์ตารางสรุป (เวลาแต่ละรอบ, เฉลี่ย, MB/s, size/hash)

## 2.2 แกนหลัก `downloadOnce`

```java
raf.setLength(size);                       // จองขนาดไฟล์ไว้ก่อน
int parts = (int) Math.min(workers, size);
long chunk = size / parts;
for (int i = 0; i < parts; i++) {
    long off = i * chunk;
    long len = (i == parts - 1) ? size - off : chunk;   // ตัวสุดท้ายรับเศษ
    pool.submit(() -> worker(... off, len ...));        // 1 virtual thread ต่อ 1 ช่วง
}
```

**ทำไมไม่ต้องใช้ lock:** แต่ละ worker เขียนคนละช่วงของไฟล์ ไม่ซ้อนกัน และมี file handle ของตัวเอง จึงไม่มี race condition

## 2.3 สอง worker เทียบกัน

```java
// workerIo  : socket -> byte[] -> ไฟล์
int n = in.read(buf, 0, ...);
raf.write(buf, 0, n);

// workerNio : socket -> ไฟล์ (JDK ช่วยย้าย)
long n = fc.transferFrom(sc, pos, remaining);
```

อ่านบรรทัด header `OK <len>` **ทีละ byte แบบไม่ buffer** เพราะถ้า buffer จะไปกลืนข้อมูล payload ที่ส่วนถัดไปต้องอ่านต่อ

## 2.4 ความถูกต้องและวิธีวัดผล

- ตรวจ **ขนาดไฟล์** และ **SHA-256** หลังโหลดทุกรอบ
- เวลาที่วัดไม่รวมเวลาคำนวณ hash
- `bench` มีรอบ **warm-up** (ไม่นับผล) เพื่อให้ไฟล์อยู่ใน page cache เท่ากันทุกกรณี

---

# ส่วนที่ 3: FileClientGui.java

## 3.1 หน้าตาและหน้าที่

```
┌──────────────── Header (โลโก้ + ชื่อโปรแกรม) ────────────────┐
│ Connection & Configuration │ Live Telemetry                  │
│  Host/Port/Connect         │  % ใหญ่ + แถบความคืบหน้า         │
│  Engine: [I/O] [NIO]       │  Speed / Elapsed / ETA           │
│  Workers - 10 +  Rounds    │  Worker Lanes (แถบต่อ worker)    │
│  Save Dir / Browse         │                                  │
│  ตารางไฟล์บน server         │                                  │
│  [Start][Info][Cancel]     │                                  │
├────────────── ••• (แถบลากปรับความสูง) ───────────────────────┤
│ Download History  [Copy Summary] [Export CSV] [Clear]        │
└──────────────────────────────────────────────────────────────┘
│ แถบสถานะล่างสุด                                               │
```

## 3.2 โครงสร้างโค้ดสำคัญ

| ส่วน | หน้าที่ |
|---|---|
| `enum State` | IDLE, RUNNING, VERIFYING, DONE, FAILED, CANCELLED ควบคุมการเปิด/ปิดปุ่ม |
| `connectServer()` | ส่ง `LIST` ใน virtual thread แล้วเติมตารางไฟล์ |
| `startDownload()` | อ่านค่าจากฟอร์ม สร้าง Run ใหม่ (`++jobSeq`) แล้วเริ่มโหลด |
| `runDownload()` | ถามพอร์ตจริงด้วย `PORTS` → INFO/HASH → แบ่งช่วง → โหลด → ตรวจ → บันทึก History |
| `workerIo / workerNio` | ตัวโหลดจริง (เหมือน client ใน CLI) พร้อมอัปเดตความคืบหน้า |
| `progressUpdater` | virtual thread ที่คำนวณความเร็ว (ค่าเฉลี่ยเคลื่อนที่) ทุก 100 ms |
| `recordHistory()` | เก็บผลแต่ละรอบเข้า `Case` ของ Run นั้น |

## 3.3 กฎของ History

- กด Start 1 ครั้ง = **1 Run = 1 แถว** (มีเลข Run และเวลา Started)
- ถ้าตั้ง Rounds = 3 แถวนั้นจะมีเวลา 3 ค่า และคำนวณค่าเฉลี่ย / MB/s ให้
- กด Start อีกครั้ง = Run ใหม่ ไม่ปนกับครั้งก่อน
- Copy Summary / Export CSV ส่งออกข้อมูลชุดเดียวกัน

## 3.4 ข้อควรรู้เรื่องพอร์ต

- ช่อง **Port ให้ใส่พอร์ตหลักของ server** (เช่น 9000)
- GUI ถามพอร์ตจริงจาก server ด้วยคำสั่ง `PORTS` จึงใส่พอร์ต NIO (9001) ก็ยังเลือกถูก
- ถ้าเจอ `Connection refused` ตอนเลือก NIO ให้ตรวจว่า server เปิดอยู่ และ firewall เปิดทั้ง 2 พอร์ต

---

# ส่วนที่ 4: เนื้อหา OS ที่ต้องเตรียม

## 4.1 Thread และ Virtual Thread

- **Platform thread** = thread ของ OS สแต็กใหญ่ สลับบริบท (context switch) แพง เปิดได้ไม่กี่พัน
- **Virtual thread** = thread ของ JVM บางเบา วางบน carrier thread จำนวนน้อย
- พอ virtual thread รอ I/O จะถูก "จอด" แล้วปล่อย carrier ไปทำงานอื่น
- ผลคือเขียนโค้ด blocking ง่ายๆ แต่รองรับ connection จำนวนมากได้

## 4.2 Socket และ TCP

- Socket คือ **file descriptor** ชนิดหนึ่ง OS มี send/receive buffer ให้
- TCP รับประกันลำดับและความครบถ้วน แต่เป็น **byte stream** ไม่มีขอบเขตข้อความ
- จึงต้องมี framing: อ่านถึง `\n` สำหรับคำสั่ง และ header `OK <len>` บอกความยาวข้อมูล
- `TCP_NODELAY` ปิด Nagle's algorithm ลดความหน่วงของข้อความสั้น
- **backlog** = คิวใน kernel ที่เก็บ connection ซึ่ง handshake เสร็จแล้วแต่โปรแกรมยังไม่ `accept`
- **`SO_REUSEADDR`** = ให้ bind พอร์ตเดิมได้เลยแม้ connection เก่าค้างสถานะ `TIME_WAIT`

## 4.3 System call และ User/Kernel mode

- โปรแกรมทำงานใน user mode เข้าถึงฮาร์ดแวร์ตรงๆ ไม่ได้ ต้องขอ kernel ผ่าน **system call** (`read`, `write`, `sendfile`)
- การสลับ user ↔ kernel mode มีต้นทุน ยิ่งสลับน้อย ยิ่งประหยัด CPU

## 4.4 Page cache

- OS เก็บข้อมูลไฟล์ที่เพิ่งอ่านไว้ใน RAM
- รอบแรกช้า (ต้องอ่านดิสก์จริง) รอบหลังเร็วมาก
- เป็นเหตุผลที่ `bench` มี warm-up และควรเทียบหลายรอบ

## 4.5 Zero-copy: จุดต่างสำคัญที่สุดของ I/O กับ NIO

```
Traditional I/O (read + write)
  ดิสก์ ─▶ page cache ─▶ [buffer ในโปรแกรม] ─▶ socket buffer ─▶ NIC
           คัดลอก 1        คัดลอก 2              คัดลอก 3
           + สลับ mode 4 ครั้ง ต่อก้อน

NIO (transferTo -> sendfile)
  ดิสก์ ─▶ page cache ──────────────────────▶ socket buffer ─▶ NIC
           ไม่ผ่านหน่วยความจำของโปรแกรมเลย (zero-copy)
```

> หมายเหตุสำคัญ: zero-copy จริงอยู่ที่ **ฝั่ง server (`transferTo`)** ส่วน `transferFrom` ฝั่ง client จาก socket ใน JDK ยังอ่านผ่าน buffer ชั่วคราว ไม่ใช่ zero-copy เต็มรูปแบบ

## 4.6 Blocking กับ Non-blocking

- **Blocking**: `read()` ทำให้ thread หยุดรอจนมีข้อมูล
- **Non-blocking**: คืนค่าทันที (อาจได้ 0) แล้วใช้ `epoll`/`select` รอเหตุการณ์
- โปรแกรมนี้ใช้ NIO แบบ blocking ไม่ใช้ Selector ให้ virtual thread รับภาระการรอแทน
- ใต้ฝา JDK ตั้ง socket เป็น non-blocking ภายในเมื่อใช้บน virtual thread (จึงต้องมี fallback ใน `transferTo`)

## 4.7 File system และการเขียนแบบ random access

- `setLength` จองขนาดไฟล์ไว้ก่อน
- แต่ละ worker `seek` ไปเขียนตาม offset ของตัวเอง
- เปิด file handle แยกกัน แต่ละตัวมีตำแหน่งอ่าน/เขียน (file pointer) ของตัวเอง ไม่แย่งกัน

## 4.8 Concurrency

- ทำงานขนานได้ **โดยไม่ต้องล็อก** เพราะแบ่งงานให้ข้อมูลไม่ซ้อนกัน (data partitioning)
- เพิ่ม worker ช่วยถึงจุดหนึ่ง (ใช้แบนด์วิดท์ได้เต็มขึ้น) แต่มากเกินไปเสียค่าสร้าง connection และแย่งดิสก์

## 4.9 ความปลอดภัยและความถูกต้อง

- **SHA-256** ยืนยันว่าไฟล์ที่ประกอบจากหลายช่วงถูกต้องครบ
- **Path traversal** ถูกกันที่ `resolve()`
- ข้อจำกัด: ไม่มี authentication และไม่จำกัดจำนวน connection (เหมาะกับงานทดลอง)

## 4.10 ตารางสรุปเทียบ I/O กับ NIO

| หัวข้อ | Traditional I/O | NIO |
|---|---|---|
| ตัวแทนข้อมูล | Stream | Channel + Buffer |
| ฝั่งส่งไฟล์ | `read` เข้า `byte[]` แล้ว `write` ออก | `transferTo` (zero-copy) |
| จำนวนการคัดลอก | หลายรอบ | น้อยกว่า |
| System call หลัก | `read` + `write` | `sendfile` (Linux) |
| การสลับ user/kernel | บ่อย | น้อยกว่า |
| ความยากของโค้ด | ง่าย | ต้องวนลูป + จัดการ fallback |
| พอร์ตในโปรเจกต์นี้ | `port` | `port + 1` |

---

# ส่วนที่ 5: คำถามที่น่าจะโดน และแนวตอบ

**1. ทำไม NIO เร็วกว่า I/O?**
เพราะ zero-copy ลดการคัดลอกข้อมูลและลดการสลับ user/kernel mode จึงใช้ CPU น้อยลง แต่ถ้าคอขวดคือเครือข่ายหรือดิสก์ ความต่างจะไม่มาก (โดยเฉพาะทดสอบบนเครื่องเดียว)

**2. ทำไมรอบแรกช้ากว่ารอบหลังเสมอ?**
เพราะ page cache รอบแรกต้องอ่านจากดิสก์จริง รอบหลังข้อมูลอยู่ใน RAM แล้ว จึงมีรอบ warm-up

**3. ทำไม 10 workers เร็วกว่า 1 worker?**
หลาย connection ใช้แบนด์วิดท์และ CPU ได้พร้อมกัน แต่เพิ่มมากเกินไป (เช่น 64) อาจช้าลง เพราะเสียเวลาสร้าง connection และแย่งดิสก์

**4. ทำไมหลาย worker เขียนไฟล์เดียวกันได้โดยไม่ชนกัน?**
แต่ละตัวเขียนคนละช่วง (offset ไม่ซ้อนกัน) และมี file handle ของตัวเอง

**5. ทำไมต้องวนลูปตอน `transferTo`?**
เพราะมันส่งได้น้อยกว่าที่ขอได้ เมื่อ socket send buffer เต็ม ต้องขอต่อจากตำแหน่งที่ส่งไปแล้ว

**6. ทำไมต้องอ่าน header ทีละ byte ฝั่ง client?**
กันไม่ให้ buffer ไปกลืนข้อมูล payload ที่ส่วนถัดไปต้องอ่านต่อ

**7. Virtual thread ต่างจาก thread ปกติอย่างไร?**
เป็น thread ของ JVM ไม่ผูก 1:1 กับ thread ของ OS รอ I/O แล้วถูกจอด ไม่กิน carrier thread

**8. ถ้าไฟล์โหลดมาไม่ครบหรือเสียหาย รู้ได้อย่างไร?**
ตรวจขนาดไฟล์ และเทียบ SHA-256 กับค่าที่ server ส่งมา

**9. ป้องกันการขอไฟล์นอกโฟลเดอร์อย่างไร?**
`resolve()` ห้ามชื่อที่มี `/`, `\`, `..` แล้ว normalize และตรวจว่ายังอยู่ใต้โฟลเดอร์ที่แชร์

**10. NIO ที่ใช้ non-blocking หรือไม่?**
ไม่เต็มรูปแบบ ใช้ NIO แบบ blocking (ไม่มี Selector) ให้ virtual thread รอแทน

---

# ส่วนที่ 6: โค้ดตัวอย่างสำหรับปรับ UI

> ทุกตัวอย่างอยู่ใน `FileClientGui.java` ค้นหาชื่อที่ระบุได้ด้วย Ctrl+F

## 6.1 เปลี่ยนสีทั้งโปรแกรม (จุดเดียว)

สีทั้งหมดรวมอยู่ที่ "Design tokens" ต้นไฟล์ แก้ตรงนี้ที่เดียวเปลี่ยนทั้งหน้าจอ

```java
static final Color BG_MAIN        = new Color(0xF1F5F9);  // พื้นหลังหลัก
static final Color CARD_BG        = Color.WHITE;          // พื้นการ์ด
static final Color TEXT_MAIN      = new Color(0x0F172A);  // ตัวอักษรหลัก
static final Color PRIMARY_BLUE   = new Color(0x1D4ED8);  // ปุ่มหลัก (Connect, Start)
static final Color SUCCESS_GREEN  = new Color(0x047857);  // สำเร็จ (Open Folder, แถบ 100%)
static final Color DANGER_RED     = new Color(0xDC2626);  // อันตราย (Cancel, Clear, Error)
static final Color PROGRESS_YELLOW= new Color(0xF59E0B);  // แถบกำลังโหลด
static final Color NIO_PURPLE     = new Color(0x7C3AED);  // ตัวอักษร "NIO" ใน History
```

ตัวอย่าง เปลี่ยนปุ่มหลักจากน้ำเงินเป็นส้ม:

```java
static final Color PRIMARY_BLUE = new Color(0xEA580C);
```

> รูปแบบ `0xRRGGBB` (เลขฐาน 16 ของ แดง เขียว น้ำเงิน) ใช้ color picker ออนไลน์เลือกแล้วก๊อปค่า hex ได้

## 6.2 เปลี่ยนสีเฉพาะปุ่มเดียว

ปุ่มทั้งหมดประกาศไว้ต้นคลาส รูปแบบ `new MinimalButton(ข้อความ, สีพื้น, สีตัวอักษร)`

```java
// เดิม
final MinimalButton downloadBtn = new MinimalButton("Start Download", PRIMARY_BLUE, Color.WHITE);

// เปลี่ยนเป็นสีเขียว
final MinimalButton downloadBtn = new MinimalButton("Start Download", SUCCESS_GREEN, Color.WHITE);

// เปลี่ยนข้อความปุ่ม
final MinimalButton downloadBtn = new MinimalButton("Download", PRIMARY_BLUE, Color.WHITE);
```

ปุ่มแบบกรอบ (outline) ใส่ `true` เป็นพารามิเตอร์ตัวที่ 4:

```java
final MinimalButton infoBtn = new MinimalButton("File Info", CARD_BG, TEXT_MAIN, true);
```

## 6.3 เปลี่ยนขนาดและมุมโค้งของปุ่ม

อยู่ในคลาส `MinimalButton`

```java
@Override public Dimension getPreferredSize() {
    int w = getFontMetrics(getFont()).stringWidth(getText()) + 28;   // 28 = ระยะซ้ายขวารวม (เพิ่ม = ปุ่มกว้างขึ้น)
    return new Dimension(Math.max(w, 38), 36);                       // 38 = กว้างต่ำสุด, 36 = สูง
}
```

มุมโค้ง (เลข 10 สองตัวท้าย) อยู่ใน `paintComponent` ของ `MinimalButton`

```java
g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 10, 10));
//                                                              ^^  ^^ รัศมีมุม (ใหญ่ขึ้น = โค้งขึ้น)
```

> ถ้าแก้รัศมีมุม ให้แก้ทั้ง 2 จุดในเมธอดนั้น (ตัวพื้น และตัวเส้นขอบ) ให้เท่ากัน ตัวเส้นขอบใช้เลขน้อยกว่าเล็กน้อย (8)

## 6.4 เปลี่ยนฟอนต์

```java
static final Font UI_FONT     = new Font("SansSerif", Font.PLAIN, 13);  // ตัวอักษรทั่วไป
static final Font BOLD_FONT   = new Font("SansSerif", Font.BOLD,  13);  // ปุ่ม/หัวคอลัมน์
static final Font HEADER_FONT = new Font("SansSerif", Font.BOLD,  15);  // หัวข้อ section
static final Font SMALL_FONT  = new Font("SansSerif", Font.PLAIN, 11);  // ตัวเล็ก (Worker Lanes)
```

ตัวเลข % ขนาดใหญ่ใน Live Telemetry:

```java
percentLabel.setFont(new Font("SansSerif", Font.BOLD, 44));   // เปลี่ยน 44 เป็นขนาดที่ต้องการ
```

## 6.5 เปลี่ยนขนาดหน้าต่าง

ในคอนสตรัคเตอร์ `FileClientGui()`

```java
setPreferredSize(new Dimension(1260, 900));     // ขนาดตอนเปิด (กว้าง, สูง)

// ขนาดต่ำสุด: คำนวณจากเลย์เอาต์ แล้วไม่ให้ต่ำกว่า 1100 x 800
setMinimumSize(new Dimension(Math.max(1100, cm.width + fi.left + fi.right),
                             Math.max(800,  cm.height + fi.top + fi.bottom)));
```

## 6.6 เปลี่ยนความสูงของแถวในตาราง

```java
styleTable(fileTable, 34);      // ตารางไฟล์: สูงแถวละ 34 px
styleTable(historyTable, 30);   // ตาราง History: สูงแถวละ 30 px
```

จำนวนแถวขั้นต่ำของตารางไฟล์ (ตอนนี้ 3 แถว) อยู่ใน `buildLeftControlPanel`

```java
sp.setPreferredSize(new Dimension(100, hdrH + 34 * 5 + 4));   // ปกติเห็น 5 แถว
sp.setMinimumSize  (new Dimension(100, hdrH + 34 * 3 + 4));   // อย่างน้อย 3 แถว (เปลี่ยน 3 เป็นเลขอื่น)
```

## 6.7 ปรับตาราง History และแถบลากแบ่งหน้าจอ

ความกว้างแต่ละคอลัมน์ (เรียง: Run, Started, File, Mode, Workers, Time per round, Average, MB/s, Size/Hash)

```java
int[] w = {45, 140, 170, 60, 70, 230, 90, 80, 100};
```

แถบลากระหว่างส่วนบนกับ History (ใน `buildBody`)

```java
split.setDividerSize(16);      // ความหนาแถบลาก
split.setResizeWeight(0.6);    // ตอนขยายหน้าต่าง ส่วนบนได้ 60% History ได้ 40%
topScroll.setMinimumSize(new Dimension(100, 150));   // ส่วนบนย่อได้เหลือ 150 px
```

ความสูงเริ่มต้นของ History:

```java
sp.setPreferredSize(new Dimension(100, 190));   // ในเมธอด buildHistoryPanel (เพิ่ม = สูงขึ้น)
```

## 6.8 เปลี่ยนแถบความคืบหน้าและสี

```java
final MinimalProgressBar overallBar = new MinimalProgressBar(14);   // 14 = ความหนา px
```

สี: ตอนโหลด = `PROGRESS_YELLOW`, ครบ 100% = `SUCCESS_GREEN` (แก้ที่ design tokens ในข้อ 6.1)

## 6.9 เปลี่ยนสีป้ายสถานะ (Idle / Downloading / ...)

ในเมธอด `setState`

```java
switch (s) {
    case IDLE      -> pill.set("Idle",        TEXT_MUTED);
    case RUNNING   -> pill.set("Downloading", PROGRESS_YELLOW.darker());
    case VERIFYING -> pill.set("Verifying",   PRIMARY_BLUE);
    case DONE      -> pill.set("Completed",   SUCCESS_GREEN);
    case FAILED    -> pill.set("Failed",      DANGER_RED);
    case CANCELLED -> pill.set("Cancelled",   TEXT_MUTED);
}
```

## 6.10 เปลี่ยนแถบหัวโปรแกรม (Header)

ในคลาส `HeaderPanel`

```java
getPreferredSize() { return new Dimension(100, 64); }                 // ความสูงหัว 64 px
g.setPaint(new GradientPaint(0, 0, TEXT_MAIN, getWidth(), 0, new Color(0x1E293B)));  // สีพื้นไล่เฉด
g.setPaint(new GradientPaint(0, 0, PRIMARY_BLUE, getWidth(), 0, NIO_PURPLE));        // เส้นไล่สีล่าง
g.drawString("MultiThread File Downloader", 66, 29);                  // ชื่อโปรแกรม (แก้ข้อความได้)
```

## 6.11 เพิ่มปุ่มใหม่ (ตัวอย่างครบวงจร)

สมมติจะเพิ่มปุ่ม "Refresh" สีเขียวข้างปุ่ม Connect

```java
// 1) ประกาศปุ่ม (รวมกับกลุ่มปุ่มต้นคลาส)
final MinimalButton refreshBtn = new MinimalButton("Refresh", SUCCESS_GREEN, Color.WHITE);

// 2) ใส่ลงแถวในเมธอด buildLeftControlPanel
form.add(row(rowLabel("Host"), hostField, label("Port"), portField, connectBtn, refreshBtn));

// 3) ผูกการทำงานในเมธอด wireEvents
refreshBtn.addActionListener(e -> connectServer());

// 4) (ถ้าต้องการ) ใส่ข้อความเมื่อเอาเมาส์ชี้ใน addTooltips
refreshBtn.setToolTipText("Reload the file list");

// 5) (ถ้าต้องการ) ให้ปิดปุ่มตอนกำลังโหลด ในเมธอด setState
refreshBtn.setEnabled(!busy);
```

## 6.12 ช่องกรอกข้อมูลและแถว

```java
final JTextField hostField = field("localhost", 12);   // (ค่าเริ่มต้น, ความกว้างเป็นจำนวนตัวอักษร)
final JTextField portField = field("9000", 6);
```

- ช่องที่กว้าง 10 ตัวอักษรขึ้นไป (Host, Save Dir) จะยืดตามหน้าต่างอัตโนมัติ
- ช่องแคบ (Port, Workers, Rounds) ขนาดคงที่
- ระยะห่างในแถวอยู่ในเมธอด `row(...)`: `Box.createHorizontalStrut(8)` (8 = ระยะห่าง px)

---

# ส่วนที่ 7: สคริปต์สาธิตและเช็กลิสต์ก่อนพรีเซนต์

## 7.1 ลำดับสาธิตที่แนะนำ

1. เปิด server: `java FileServer.java 9000 <โฟลเดอร์ไฟล์>` (เห็นสองพอร์ต 9000 และ 9001)
2. เปิด GUI: `java FileClientGui.java`
3. กด **Connect** แล้วดูรายชื่อไฟล์
4. เลือก **I/O**, Workers = 1, Rounds = 3 แล้วกด Start (เป็นฐานเปรียบเทียบ)
5. เลือก **I/O**, Workers = 10, Rounds = 3 (ดูผลของการขนาน)
6. เลือก **NIO**, Workers = 10, Rounds = 3 (เทียบ I/O กับ NIO)
7. ชี้ตาราง **History**: แต่ละ Run แยกกัน มีเวลาแต่ละรอบ ค่าเฉลี่ย MB/s และ size/hash
8. กด **Copy Summary** หรือ **Export CSV** เพื่อเก็บผล
9. ลากแถบ `•••` ขยาย History ให้เห็นตารางชัดๆ
10. พิมพ์ `quit` ในหน้าต่าง server เพื่อปิด

## 7.2 เช็กลิสต์ก่อนวันจริง

- [ ] ใช้ Java 21 ขึ้นไป (`java -version`)
- [ ] เตรียมไฟล์ทดสอบขนาดใหญ่ (หลายร้อย MB ขึ้นไป) ให้เห็นความต่าง
- [ ] ถ้า server กับ client คนละเครื่อง เปิด firewall **ทั้งสองพอร์ต** (หลัก และหลัก + 1)
- [ ] ทดสอบรัน 1 รอบก่อน (รอบแรกช้าเพราะ page cache)
- [ ] ใช้ Rounds ตั้งแต่ 3 ขึ้นไปเพื่อให้ผลเทียบได้
- [ ] เตรียมคำอธิบายว่าทำไมผลของคุณเป็นแบบนั้น (คอขวดอยู่ที่ไหน)

## 7.3 ประโยคสรุปปิดท้าย

> "งานนี้แสดงให้เห็นว่าการเลือกวิธี I/O ส่งผลต่อประสิทธิภาพ I/O แบบดั้งเดิมคัดลอกข้อมูลผ่าน buffer ใน user space หลายครั้ง ส่วน NIO ใช้ `transferTo` ให้ kernel ส่งข้อมูลจาก page cache เข้า socket โดยตรง (zero-copy) ลดการคัดลอกและการสลับ mode ระหว่าง user กับ kernel เมื่อรวมกับการแบ่งไฟล์ให้หลาย worker โหลดขนานกันด้วย virtual thread ทำให้ใช้ทรัพยากรได้เต็มประสิทธิภาพ โดยความเร็วจริงยังขึ้นกับว่าคอขวดอยู่ที่เครือข่าย ดิสก์ หรือ CPU"
