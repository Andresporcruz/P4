package plc.project;

public final class ParseException extends Exception {

    private final int index;

    public ParseException(String message, int index) {
        super(message + " at index " + index);
        this.index = index;
    }

    public int getIndex() {
        return index;
    }
}
