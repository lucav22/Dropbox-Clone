import java.io.Serializable;

public class FileEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum EventType {
        CREATE, MODIFY, DELETE
    }

    private final EventType eventType;
    private final String relativePath;
    private final byte[] fileData;

    public FileEvent(EventType eventType, String relativePath, byte[] fileData) {
        this.eventType = eventType;
        this.relativePath = relativePath;
        this.fileData = fileData;
    }

    public EventType getEventType() {
        return eventType;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public byte[] getFileData() {
        return fileData;
    }

    @Override
    public String toString() {
        return "FileEvent{" +
                "eventType=" + eventType +
                ", relativePath='" + relativePath + "\'" +
                ", fileSize=" + (fileData != null ? fileData.length + " bytes" : "N/A") +
                '}';
    }
}