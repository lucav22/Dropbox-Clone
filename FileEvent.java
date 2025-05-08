import java.io.Serializable;

public class FileEvent implements Serializable {
    private static final long serialVersion = 1L;

    public enum EventType {
        CREATE,  // A new file is created
        MODIFY,  // A file is modified
        DELETE   // A file is deleted
    }
    
    private EventType eventType;
    private String filePath;    
    private byte[] fileData;    
}