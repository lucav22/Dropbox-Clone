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
                File file = new File(WATCH_DIR + File.separator + filePath);
                
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
            
            Path watchPath = Paths.get(WATCH_DIR);
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
                    Path relativePath = Paths.get(WATCH_DIR).relativize(fullPath);
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
        // Register the directory and all its subdirectories
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}