public class SequenceNumberG {

    //id
    private long seq = 1;

    public synchronized long next(){
        return seq++;
    }
}
