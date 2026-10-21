import java.util.Objects;
import java.util.concurrent.BlockingQueue;

public class Scheduler extends Thread {

    private final BlockingQueue<Job> inbox;
    private final ReadyQueue readyQueue;
    private final Statistics statistics;
    private final ProjectLogger logger;

    public Scheduler(BlockingQueue<Job> inbox, ReadyQueue readyQueue,
                     Statistics statistics, ProjectLogger logger) {
        super("scheduler");
        this.inbox = Objects.requireNonNull(inbox);
        this.readyQueue = Objects.requireNonNull(readyQueue);
        this.statistics = Objects.requireNonNull(statistics);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public void run() {
        try {
            while (true) {
                Job job = inbox.take();
                if (job == JobGenerator.POISON) {
                    break;
                }

                statistics.jobReady(job);                // นับเป็น ready ก่อน
                try {
                    readyQueue.add(job);
                } catch (RuntimeException e) {
                    statistics.jobReadyCancelled(job);   // add ล้มเหลว ถอนออก
                    throw e;
                }

                log("[Scheduler] " + job.id + " added to -> ReadyQueue (size=" + readyQueue.size() + ")");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            readyQueue.close();
            log("[Scheduler] : Closed ReadyQueue ");
        }
    }

    private void log(String message) {
        logger.systemEvent(message);
    }
}