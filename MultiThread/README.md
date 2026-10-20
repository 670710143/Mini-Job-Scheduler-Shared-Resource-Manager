# Multi-threaded File Download Client/Server (Traditional I/O vs NIO)

ต้องใช้ JDK 21 ขึ้นไป (ใช้ Virtual Thread)

## Compile

```
javac FileServer.java FileClient.java
```

## Run

เตรียมโฟลเดอร์ `share` ที่มีไฟล์ใหญ่ (ชื่อไฟล์ห้ามมีช่องว่าง) แล้วเปิด Server:

```
java FileServer 9000 share
```

Server เปิด 2 พอร์ตพร้อมกัน เพื่อเทียบกันโดยใช้ไฟล์และเครื่องเดียวกัน
- `9000` = Traditional I/O (`ServerSocket`, `RandomAccessFile.read`, `OutputStream.write`)
- `9001` = NIO (`ServerSocketChannel`, `FileChannel.transferTo`)

Client (คนละ terminal):

```
java FileClient localhost 9000 list
java FileClient localhost 9000 get big.bin io 10 3      # โหมด io, 10 workers, 3 รอบ
java FileClient localhost 9000 get big.bin nio 1 3      # โหมด nio, 1 worker, 3 รอบ
java FileClient localhost 9000 bench big.bin 3          # รันครบ io/nio x 1/10 workers x 3 รอบ + ตารางสรุป
```

พอร์ตที่ให้ใน Client คือพอร์ตฐาน (io ใช้พอร์ตนั้น, nio ใช้พอร์ต+1) ไฟล์ที่โหลดอยู่ใน `downloads/`
ถ้า Windows แสดงภาษาไทยเพี้ยน ให้รัน `chcp 65001` ก่อน

## Protocol (TCP, คำสั่งเป็นข้อความ 1 บรรทัด ลงท้าย `\n`)

| Request | Response |
|---|---|
| `LIST` | `FILE <name> <size>` หลายบรรทัด แล้วปิดด้วย `END` |
| `INFO <name>` | `SIZE <bytes>` หรือ `ERROR 404 File not found` |
| `HASH <name>` | `HASH <sha256-hex>` (ใช้ตรวจความถูกต้องหลังโหลด) |
| `GET <name> <offset> <length>` | `OK <length>\n` ตามด้วย payload `length` byte พอดี |
| ผิดพลาด | `ERROR 400` (รูปแบบผิด/คำสั่งไม่รู้จัก), `ERROR 404` (ไม่พบไฟล์), `ERROR 416` (ช่วง byte ผิด) |
| `QUIT` | ปิด connection |

1 connection ส่งได้หลายคำสั่งต่อเนื่อง

## การทำงาน

1. Client ขอ `INFO` (ขนาด) และ `HASH` จาก Server
2. Client คำนวณช่วง byte ให้ N workers (ค่าเริ่มต้น 10): `chunk = size / N` โดย worker สุดท้ายรับเศษ ช่วงไม่ซ้อนกัน
3. แต่ละ worker (Virtual Thread) เปิด connection ของตนเอง ส่ง `GET` พร้อม offset/length และรับเฉพาะช่วงนั้น
4. เขียนลง final file ตาม offset โดยแต่ละ worker เปิด `RandomAccessFile` (io) หรือ `FileChannel` (nio) ของตัวเอง
   (ไฟล์ถูกจองขนาดไว้ก่อนด้วย `setLength`)
5. โหลดครบแล้วตรวจขนาดไฟล์และ SHA-256 เทียบกับที่ Server รายงาน
6. Server: 1 connection = 1 Virtual Thread (`newVirtualThreadPerTaskExecutor`)

คำสั่ง `bench` วอร์มอัพ 1 รอบ (ไม่นับผล) ก่อน เพื่อให้ไฟล์อยู่ใน page cache เท่ากันทุกกรณี
เวลาที่วัดคือ จองไฟล์ + โหลดทุกช่วง (ไม่รวมเวลาคำนวณ hash)

## ตัวอย่างผลทดลอง (รันใน sandbox ของผู้เขียนโค้ด ไฟล์สุ่ม 190.73 MB, localhost — ให้รันซ้ำบนเครื่องตัวเองเพื่อใส่ในรายงาน)

| โหมด | workers | เวลาแต่ละรอบ (s) | เฉลี่ย (s) | MB/s | size/hash |
|---|---|---|---|---|---|
| IO | 1 | 0.702 0.561 0.485 | 0.583 | 327.2 | OK |
| IO | 10 | 0.516 0.489 0.565 | 0.523 | 364.6 | OK |
| NIO | 1 | 0.883 0.695 0.680 | 0.753 | 253.3 | OK |
| NIO | 10 | 0.576 0.766 0.639 | 0.660 | 289.0 | OK |

## ประเด็นสำหรับอธิบายในรายงาน

**ทำไม 10 workers ไม่เร็วขึ้น 10 เท่า**
- loopback, page cache, disk และ CPU เป็นทรัพยากรร่วมกัน เพิ่ม connection ไม่ได้เพิ่มเพดาน
- มี overhead ของการสร้าง connection, context switch และ worker ที่เสร็จก่อนต้องรอตัวที่ช้าสุด
- ไฟล์ขนาดเล็ก overhead มีสัดส่วนมากขึ้น

**ทำไม NIO ไม่ชนะทุกครั้ง**
- `transferTo` (sendfile) ลดการ copy ระหว่าง kernel กับ user space ได้เฉพาะฝั่ง Server
- ฝั่ง Client `transferFrom` จาก socket ไม่ใช่ zero-copy: JDK อ่านผ่าน buffer ชั่วคราวก่อนเขียนลงไฟล์
- บน loopback ที่ข้อมูลอยู่ใน page cache แล้ว ส่วนต่างเล็กและแกว่งตามรอบ จึงอาจไม่ชนะ
- มีรายละเอียดของ Virtual Thread: JDK ตั้ง socket เป็น non-blocking ภายในเมื่อใช้บน virtual thread ทำให้
  `transferTo` คืน 0 เมื่อ send buffer เต็ม โค้ด `NioConn.sendRange` จึงเขียนก้อนเล็กด้วย `write()` แล้วเรียก
  `transferTo` ต่อ (ในการทดสอบข้อมูลส่วนใหญ่ราว 96% ยังไปทาง sendfile)
สรุป Final Project MultiThread

ฝั่ง Server
1 connection = 1 virtual thread
(A.) I/O โหมด I/O
     อ่านไฟล์ด้วย RandomAccessFile.read  -- > เขียนผ่าน OutputStream 
    copy หลายต่อ disk -> page cache (kernel) -> buffer [byte [] ในโปรแกรม ] (user space) -> socket buffer (kernel) -> NIC + สลับ user/kernel mode หลายครั้ง 
    เมื่อครั้งต่อไปทำงานอีกก็จะดึงไฟล์นั้นออกจาก page cache (OS  เก็บไว้) ก็จะไวขึ้น
    write & read
อธิบายโค้ด สถาปัตยกรรม
    Thread ที่รอ accept() เป็น platform thread เพราะมีแค่สองตัวและทำงานตลอดอายุโปรแกรม
ทุก connection - - เข้า --> newVirtualThreadPerTaskExecutor() ได้ virtual thread หนึ่งตัวต่อหนึ่ง connection จึงเขียนโค้ดแบบ blocking ตรงๆ ได้ แต่รองรับ connection จำนวนมากได้
ทั้งสองพอร์ตเรียก serve() ตัวเดียวกัน ต่างกันแค่ว่า Conn ที่ส่งเข้าไปเป็นแบบไหน (Strategy pattern)
 interface Conn ซ่อนความต่างของสองโหมด
    java
    interface Conn extends Closeable {
        String readLine();                          // อ่านบรรทัดคำสั่งทีละ byte ถ้าไม่มี buffer จะเป็น system call read ต่อ 1 byte
        void send(String text);                     // ส่งข้อความ
        void sendRange(Path file, long off, long len); // ส่งข้อมูลไฟล์ช่วงหนึ่ง
        void flush();
    }
ตัวประมวลผลคำสั่ง (doList, doGet ฯลฯ) ไม่รู้เลยว่าใต้ดินเป็น I/O หรือ NIO สิ่งเดียวที่ต่างกันจริงๆ คือ sendRange
IOConn [Traditional I/O]

      in  = new BufferedInputStream(s.getInputStream()); // อ่านจาก kernel ทีละก้อนแล้วแจกทีละ byte จากหน่วยความจำ
      out = new BufferedOutputStream(s.getOutputStream(), BUF_SIZE);   // 64 KB  send() แค่เขียนลง buffer ในหน่วยความจำ
ยังไม่ออกเครือข่ายจนกว่าจะ flush() หรือ buffer เต็ม ลดจำนวน system call write
TCP_NODELAY: ปิด Nagle’s algorithm (ที่หน่วงข้อความเล็กๆ เพื่อรวมแพ็กเก็ต) ทำให้บรรทัด OK <len> ออกทันที

public void sendRange(Path file, long offset, long length) {
    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
        raf.seek(offset); //seek(offset) การเลื่อนตำแหน่งเคอร์เซอร์ (Pointer) ไฟล์ไปยังไบต์ที่กำหนด เพื่อเตรียมอ่านหรือเขียนข้อมูลต่อจากจุดนั้น
        byte[] buf = new byte[BUF_SIZE];
        long remaining = length;
        while (remaining > 0) {
            int n = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) throw new EOFException(...);
            out.write(buf, 0, n);
            remaining -= n;
        }
    }
}

read() system call: kernel คัดลอกจาก page cache เข้า buf (ถ้าไม่อยู่ใน cache ต้องไปอ่านดิสก์ก่อน)
out.write(): คัดลอก buf เข้า buffer ของ BufferedOutputStream
เมื่อเต็มจะเรียก write() ลง socket: kernel คัดลอกเข้า socket send buffer
NIC ส่งออก

----------------------------------------------------------------------------------------------------------------
(B.) NIO  FileChannel.transferTo ส่งจากไฟล์เข้า socket โดยตรง      
     Zero-copy  ทำการเรียก system call (send file) ส่งจาก page cache --> socket โดยตรง
    ใช้ NIO แบบ blocking หรือ virtual thread รับภาระการรอแท
    ไวกว่าเพราะ ? : ลดการคัดลอกและสลับ mode cpu แต่บนไฟล์เล็กหรือเครือข่ายช้า ความต่างจะน้อย  😥เพราะคอขวดอยู่ที่เครือข่าย
    ได้เปรียบตอน ✅: ส่งไฟล์ใหญ่บนเครือข่ายที่เร็ว

     sc.setOption(StandardSocketOptions.TCP_NODELAY, true);
     in = new BufferedInputStream(Channels.newInputStream(sc));  // เอาstreamครอบใช้อ่านแค่บรรทัดคำสั่ง
   // ไม่มี buffet ขาออก --> ไม่มีอะไรให้ flush
    while (remaining > 0) {
        long n = fc.transferTo(pos, remaining, sc);
        ...
        pos += n; remaining -= n;
    }
   ทำไมต้องวนลูป: transferTo การันตีแค่ "ส่งอย่างมากเท่าที่ขอ" ถ้า socket send buffer เต็ม (client รับไม่ทัน) มันส่งได้น้อยกว่าที่ขอ จึงต้อง pos += n แล้วขอต่อ

**Thread** 

     การทำ context switch นั้นมีการใช้ทรัพยากรเยอะ แต่ virtual thread มี JVM วางบนcarrier thread จำนวนน้อย เมื่อต้องรอก็จะปล่อย carrier ไปทำงานอื่นก่อน ไม่ได้หยุดการทำงาน
เนื้อหาที่เกี่ยว: concurrency, scheduling, context switch

**Socket \& TCP**
 Socket คือ file descriptor ชนิดหนึ่ง OS มี send/receive buffer ของตัวเอง

 TCP รับประกันลำดับและความครบถ้วน แต่เป็น byte stream ไม่มีขอบเขตข้อความ ---> ต้องมี header ที่บอกความยาว (OK <len>) และอ่านทีละ byte จนเจอ n

TCP_NODELAY ปิด Nagle’s algorithm ลดความหน่วงของข้อความสั้น


**ข้อจำกัดของการทดสอบผ่าน localhost**
- Page Cache: รอบหลังๆ อ่านจาก RAM ไม่ใช่ดิสก์ ผลเร็วเกินจริงและทำให้ส่วนต่างของ I/O แต่ละวิธีเล็กลง
- Loopback: ไม่ผ่าน NIC จริง ไม่มี latency/packet loss ของเครือข่ายจริง
- Storage cache: cache ของดิสก์/SSD ควบคุมได้ยาก
- Client กับ Server แย่ง CPU และ disk ของเครื่องเดียวกัน
