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

    private void initializeFileMap() {
        File dirToWatch = new File(DIRECTORY);
        scanDirectory(dirToWatch, "");
    }

    private void scanDirectory(File rootDirectory, String rootRelativePath) {
        Queue<File> directoryQueue = new LinkedList<>();
        Queue<String> pathQueue = new LinkedList<>();

        directoryQueue.add(rootDirectory);
        pathQueue.add(rootRelativePath);
    
        while (!directoryQueue.isEmpty()) {
            File currentDir = directoryQueue.poll();
            String currentPath = pathQueue.poll();
            File[] files = currentDir.listFiles();
            
            if (files == null) {
                continue;
            }
            
            for (File file : files) {
                String path = currentPath.isEmpty() ? 
                        file.getName() : currentPath + File.separator + file.getName();
                
                if (file.isDirectory()) {
                    directoryQueue.add(file);
                    pathQueue.add(path);
                } else {
                    fileModificationTimes.put(path, file.lastModified());
                }
            }
        }
    }

    public void connect() throws IOException {
        socket = new Socket(SERVER_HOST, SERVER_PORT);
        System.out.println("Connected to server at " + SERVER_HOST + ":" + SERVER_PORT);
        
        output = new ObjectOutputStream(socket.getOutputStream());
        input = new ObjectInputStream(socket.getInputStream());
        
        initialSync();
    }
}