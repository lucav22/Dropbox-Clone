import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static java.nio.file.StandardWatchEventKinds.*;
import java.util.Queue;
import java.util.LinkedList;

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

    private void initialSync() {
        try {
            System.out.println("Performing initial synchronization...");
            
            for (String filePath : fileModificationTimes.keySet()) {
                File file = new File(DIRECTORY + File.separator + filePath);
                
                if (file.exists() && file.isFile()) {
                    byte[] fileData = Files.readAllBytes(file.toPath());
                    FileEvent event = new FileEvent(FileEvent.EventType.CREATE, filePath, fileData);
                    output.writeObject(event);
                    output.flush();
                    String ack = (String) input.readObject();
                    System.out.println("Initial sync for " + filePath + ": " + ack);
                }
            }
            
            System.out.println("Initial synchronization completed");
        } catch (Exception e) {
            System.err.println("Error during initial sync: " + e.getMessage());
        }
    }

    public void startWatching() {
        try {
            WatchService watchService = FileSystems.getDefault().newWatchService();
            
            Path watchPath = Paths.get(DIRECTORY);
            registerAll(watchPath, watchService);
            
            System.out.println("Watching directory: " + watchPath.toAbsolutePath());
            
            Thread pollingThread = new Thread(this::pollForModifications);
            pollingThread.setDaemon(true);
            pollingThread.start();
            
            while (true) {
                WatchKey key;
                try {
                    key = watchService.take();
                } catch (InterruptedException e) {
                    return;
                }
                
                Path dir = (Path) key.watchable();
                
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == OVERFLOW) {
                        continue;
                    }
                    
                    Path fileName = (Path) event.context();
                    Path fullPath = dir.resolve(fileName);
                    Path relativePath = Paths.get(DIRECTORY).relativize(fullPath);
                    String pathString = relativePath.toString().replace("\\", "/");
                    
                    if (Files.isDirectory(fullPath, LinkOption.NOFOLLOW_LINKS)) {
                        if (kind == ENTRY_CREATE) {
                            System.out.println("Directory created: " + pathString);
                            registerAll(fullPath, watchService);
                        }
                        continue;
                    }
                    
                    if (kind == ENTRY_CREATE) {
                        handleCreateEvent(fullPath, pathString);
                    } else if (kind == ENTRY_DELETE) {
                        handleDeleteEvent(pathString);
                    }
                }
                
                boolean valid = key.reset();
                if (!valid) {
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("Error watching directory: " + e.getMessage());
        }
    }

    private void registerAll(Path start, WatchService watchService) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void pollForModifications() {
        try {
            while (true) {
                Thread.sleep(1000);
                Map<String, Long> fileMapCopy = new HashMap<>(fileModificationTimes);
                
                for (Map.Entry<String, Long> entry : fileMapCopy.entrySet()) {
                    String filePath = entry.getKey();
                    long lastModifiedTime = entry.getValue();
                    
                    File file = new File(DIRECTORY + File.separator + filePath);
                    
                    if (file.exists() && file.isFile()) {
                        long currentModifiedTime = file.lastModified();
                        
                        if (currentModifiedTime > lastModifiedTime) {
                            fileModificationTimes.put(filePath, currentModifiedTime);
                            handleModifyEvent(file.toPath(), filePath);
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            // Thread interrupted
        }
    }

    private void handleCreateEvent(Path fullPath, String relativePath) {
        try {
            Thread.sleep(100);

            System.out.println("File created: " + relativePath);

            byte[] fileData = Files.readAllBytes(fullPath);
            FileEvent event = new FileEvent(FileEvent.EventType.CREATE, relativePath, fileData);
            sendEventToServer(event);
            fileModificationTimes.put(relativePath, fullPath.toFile().lastModified());
        } catch (Exception e) {
            System.err.println("Error handling create event: " + e.getMessage());
        }
    }
    
    private void handleModifyEvent(Path fullPath, String relativePath) {
        try {
            System.out.println("File modified: " + relativePath);

            byte[] fileData = Files.readAllBytes(fullPath);
            FileEvent event = new FileEvent(FileEvent.EventType.MODIFY, relativePath, fileData);
            sendEventToServer(event);
        } catch (Exception e) {
            System.err.println("Error handling modify event: " + e.getMessage());
        }
    }
    
    private void handleDeleteEvent(String relativePath) {
        try {
            System.out.println("File deleted: " + relativePath);
            
            FileEvent event = new FileEvent(FileEvent.EventType.DELETE, relativePath, null);
            sendEventToServer(event);
            fileModificationTimes.remove(relativePath);
        } catch (Exception e) {
            System.err.println("Error handling delete event: " + e.getMessage());
        }
    }

    private synchronized void sendEventToServer(FileEvent event) {
        try {
            output.writeObject(event);
            output.flush();
            
            String ack = (String) input.readObject();
            System.out.println("Server response: " + ack);
        } catch (Exception e) {
            System.err.println("Error sending event to server: " + e.getMessage());
            tryReconnect();
        }
    }
    
    private void tryReconnect() {
        System.out.println("Trying to reconnect to server...");
        
        try {
            if (socket != null) {
                socket.close();
            }
            
            connect();
            System.out.println("Reconnected to server");
        } catch (IOException e) {
            System.err.println("Failed to reconnect: " + e.getMessage());
        }
    }
    
    public void close() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing connection: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        FileSyncClient client = new FileSyncClient();
        
        try {
            client.connect();
            client.startWatching();
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
        } finally {
            client.close();
        }
    }
}