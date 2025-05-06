import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static java.nio.file.StandardWatchEventKinds.*;

public class FileSyncClient {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 8000;
    private static final String DIRECTORY = "client_files";    
    private Map<String, Long> fileModificationTimes = new HashMap<>(); //existing files
    private Socket socket;
    private ObjectInputStream input;
    private ObjectOutputStream output;
    
    public FileSyncClient() {
        File dirToWatch = new File(DIRECTORY);
        if (!dirToWatch.exists()) {
            dirToWatch.mkdir();
        }
        
        initializeFileMap();
    }
}