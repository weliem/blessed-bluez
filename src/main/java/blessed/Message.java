package blessed;

public class Message {

    private Runnable runnable;

    private long whenToRun;


    public Message(Runnable runnable, long whenToRun) {
        this.runnable = runnable;
        this.whenToRun = whenToRun;
    }

    public Runnable getRunnable() {
        return runnable;
    }

    public long getWhenToRun() {
        return whenToRun;
    }
}
