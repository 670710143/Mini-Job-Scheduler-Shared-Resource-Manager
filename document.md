#	Workload / Policy	Workers	Printer	DB	avg WT	avg TAT	Throughput	avg RW
1	standard / FCFS	3	1	2	2978 ms	5481 ms	1.15 jobs/second	87 ms
2	standard / Priority	3	1	2	2317 ms	4934 ms	1.09 jobs/second	250 ms
3	standard / Priority	1	1	2	8499 ms	10940 ms	0.41 jobs/second	0 ms
4	standard / Priority	5	1	2	1127 ms	4063 ms	1.30 jobs/second	707 ms
5	printer / Priority	3	1	2	1151 ms	4243 ms	0.80 jobs/second	1616 ms
6	printer / Priority	3	2	2	767 ms	2551 ms	1.43 jobs/second	307 ms
7	standard / Priority (Repeat)	3	1	2	2334 ms	4948 ms	1.08 jobs/second	245 ms



 
1. เพิ่ม Worker จาก 3 เป็น 5 แล้ว Throughput เพิ่มตามสัดส่วนหรือไม่ และ avg Resource Wait เปลี่ยนอย่างไร
เพราะอะไร?
>  Throughput เพิ่มขึ้นจาก 1.09 -> 1.30 
>  Avg Resource Wait 250ms -> 707 ms รอ Semapore นานขึ้น
2. FCFS กับ Priority ทำให้ Job กลุ่มใดได้ประโยชน์หรือเสียประโยชน์จาก jobs_standard.csv? ให้อ้างอิง Waiting Time
และลำดับ START ใน log และอธิบายด้วยว่าเหตุใด Priority Scheduling ในการทดลองนี้อาจมี Throughput ต ่ากว่า

> งานที่มี worker เยอะ หรือถ้าเท่ากันตัว Priority ดีกว่าFCFS แม้ค่าเฉลี่ย Waiting Time จะดีกว่า เห็นได้ว่าลำดับของการทำงาน
>  แบบ standard / Priority	3	1	2 และ  standard / Priority	5	1	2 มีลำดับการเริ่มที่ใกล้เคียงกันตามความสำคัญ
> เหตุผลที่มี Throughput ต่ำกว่าเนื่องจาก worker มีน้อยกว่า


3. เมื่อเพิ่ม Printer permit จาก 1 เป็น 2 แล้ว avg Resource Wait และ Throughput เปลี่ยนอย่างไร
และจุดคอขวดของระบบย้ายไปอยู่ที่ใด?
> ระยะเวลาการรองานน้อยกว่าเดิมเพราะมีสิทธฺเข้าถึง printer มากกว่าเดิม Throughput สูงทำงานไวขึ้น

4. เปรียบเทียบแถว 2 กับแถว 7: เหตุใด workload และชุดค่าที่ใช้รันเหมือนเดิม จึงอาจให้เวลาใน log ลำดับเหตุการณ์
และค่าที่วัดต่างกันเล็กน้อย?
>W orkload และค่าที่ใช้รันเหมือนเดิม แต่เวลาจริงของ Thread แกว่งได้ทุกรอบจาก JVM startup, Thread.sleep ที่ตื่นเกินเล็กน้อย
> เมื่อสอง Job ขอ PRINTER ในเวลาห่างกันเพียง ~18 ms (J07 กับ J08) การเลื่อนเวลาเริ่มของ J01 ไป ~70 ms ทำให้ลำดับได้ PRINTER สลับกัน Resource Wait
> จึงซึ่งส่งผลให้ Completion order, avg RW และ Throughput ต่างกันเล็กน้อย
>
### บันทึกการใช้ AI 
ใช้ Claude ในการวางโครงสร้างงาน ปรับอต่งโค้ดให้รันได้ถูกต้องและสรุปเนื้อหาเอกสารตามบทเรียน พร้อมกับช่วยคิดคำถามสำหรับเตรียมพรีเซนต์งาน
ในการตรวจสอบ ให้ AI  ทำการตรวจเช็คอีกครั้งว่างานได้ถูกต้องทั้งหมดตามเอกสารจริง จากนั้นทำการทดสอบรันจริงหลาย ๆ กรณีการทำงาน และทำการเทียบผลลัพํธ์กับสิ่งที่ต้องการว่าตรงกันไหม 
