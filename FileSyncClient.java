import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.UUID;

import static java.nio.file.StandardWatchEventKinds.*;

public class FileSyncClient {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 8000;
    private static final String DIRECTORY = "client_files";    
    private final Map<String, Long> fileModificationTimes = new HashMap<>();
    private Socket socket;
    private ObjectInputStream input;
    private ObjectOutputStream output;
    private final BlockingQueue<FileEvent> eventSendQueue = new LinkedBlockingQueue<>();
    private Thread eventSenderThread;
    private volatile boolean running = true;
    private final Set<String> serverKnownFilesAfterHandshake = new HashSet<>();
    private final String clientId; // Unique ID for this client instance
    private volatile boolean initialHandshakeComplete = false; // Controls event sending

    public FileSyncClient() {
        this.clientId = UUID.randomUUID().toString();
        System.out.println("FileSyncClient initialized with ID: " + this.clientId);
        File dirToWatch = new File(DIRECTORY);
        if (!dirToWatch.exists()) {
            if (dirToWatch.mkdirs()) {
                System.out.println("Created directory: " + DIRECTORY);
            } else {
                System.err.println("Failed to create directory: " + DIRECTORY);
            }
        }
        initializeFileMap();
        startEventSenderThread();
    }

    private void initializeFileMap() {
        File dirToWatch = new File(DIRECTORY);
        scanDirectory(dirToWatch, ""); // Populate fileModificationTimes
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
                System.err.println("Could not list files in directory: " + currentDir.getAbsolutePath());
                continue;
            }
            
            for (File file : files) {
                String relativeFilePath = currentPath.isEmpty() ? file.getName() : currentPath + "/" + file.getName();
                if (file.isDirectory()) {
                    directoryQueue.add(file);
                    pathQueue.add(relativeFilePath);
                } else {
                    synchronized (fileModificationTimes) {
                        fileModificationTimes.put(relativeFilePath.replace("\\\\", "/"), file.lastModified());
                    }
                }
            }
        }
    }

    private void startEventSenderThread() {
        eventSenderThread = new Thread(this::runEventSenderLoop);
        eventSenderThread.setDaemon(true); // Allow JVM to exit if this is the only thread
        eventSenderThread.start();
    }
    
    public void connect() throws IOException {
        initialHandshakeComplete = false; // Reset flag at the start of connection attempt
        try {
            socket = new Socket();
            // Set timeouts for socket operations
            socket.connect(new InetSocketAddress(SERVER_HOST, SERVER_PORT), 5000); // Connect with a timeout
            socket.setSoTimeout(10000); // Set a read timeout

            System.out.println("Client [" + this.clientId + "]: Socket connected to " + SERVER_HOST + ":" + SERVER_PORT);

            // Initialize streams: OOS first, then OIS.
            output = new ObjectOutputStream(socket.getOutputStream());
            output.flush(); // Send the stream header

            input = new ObjectInputStream(socket.getInputStream());
            System.out.println("Client [" + this.clientId + "]: ObjectInputStream created.");
            // At this point, the server should have also created its OOS and OIS.
            // Server is now waiting to read the client ID.

            // Send client ID to the server
            System.out.println("Client [" + this.clientId + "]: Sending client ID to server...");
            output.writeObject(this.clientId);
            output.flush(); // Ensure the client ID is sent immediately
            System.out.println("Client [" + this.clientId + "]: Client ID sent. Waiting for server file manifest...");

            // After sending client ID, expect server to send its manifest (list of known files)
            Object serverResponse = input.readObject(); // This call is subject to the SO_TIMEOUT
            if (serverResponse instanceof Set) {
                @SuppressWarnings("unchecked") // We expect a Set of Strings from the server
                Set<String> receivedManifest = (Set<String>) serverResponse;
                
                synchronized (serverKnownFilesAfterHandshake) { // Synchronize access if accessed by other threads
                    serverKnownFilesAfterHandshake.clear();
                    serverKnownFilesAfterHandshake.addAll(receivedManifest);
                }
                System.out.println("Client [" + this.clientId + "]: Received initial file manifest from server. " +
                                   serverKnownFilesAfterHandshake.size() + " files known by server.");
            } else {
                String responseType = (serverResponse != null) ? serverResponse.getClass().getName() : "null";
                System.err.println("Client [" + this.clientId + "]: Received unexpected object type from server for manifest: " + responseType);
                closeClientResources(); // Clean up
                throw new IOException("Unexpected response from server during handshake (manifest was type " + responseType + ").");
            }

            initialHandshakeComplete = true; // Mark handshake as complete
            System.out.println("Client [" + this.clientId + "]: Connected to server and initial handshake complete.");

            // Perform initial synchronization based on the received manifest
            initialSync();

        } catch (IOException | ClassNotFoundException e) {
            String effectiveClientId = (this.clientId != null) ? this.clientId : "UNINITIALIZED_ID";
            System.err.println("Client [" + effectiveClientId + "] connection or handshake error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            
            closeClientResources(); // Ensure resources are cleaned up on failed connection
            // Rethrow to allow calling code to handle the failed connection attempt
            throw new IOException("Failed to connect or complete handshake with server: " + e.getMessage(), e);
        }
    }

    private void runEventSenderLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                FileEvent event = eventSendQueue.take(); // Blocks until an event is available
                
                if (!initialHandshakeComplete) {
                    System.out.println("Event sender (Client ID: [" + this.clientId + "]): Initial handshake not complete. Re-queueing event: " + event.getRelativePath());
                    eventSendQueue.put(event); // Put it back at the end of the queue
                    try {
                        Thread.sleep(200); // Wait a bit before trying to process queue again
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        System.out.println("Event sender sleep interrupted while waiting for handshake.");
                        break;
                    }
                    continue; // Skip to next iteration to re-check handshake status
                }
                
                System.out.println("Event sender (Client ID: [" + this.clientId + "]) processing event: " + event.getEventType() + " for " + event.getRelativePath());
                performActualSend(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Preserve interrupt status
                System.out.println("Event sender thread interrupted, stopping.");
                break; 
            } catch (Exception e) { // Catch unexpected errors in the loop
                 System.err.println("Unexpected error in event sender loop: " + e.getMessage());
                 // Depending on error, might need to pause or attempt recovery
            }
        }
        System.out.println("Event sender thread has finished.");
    }

    private void initialSync() {
        System.out.println("Performing initial synchronization...");
        Set<String> localFilePaths;
        synchronized (fileModificationTimes) { // Synchronize access to fileModificationTimes
            localFilePaths = new HashSet<>(fileModificationTimes.keySet());
        }
        
        // serverKnownFilesAfterHandshake is initialized as new HashSet<>() and populated by connect/tryReconnect.
        System.out.println("Initial sync: Comparing " + localFilePaths.size() + " local files against " + 
                           serverKnownFilesAfterHandshake.size() + 
                           " server-known files (from manifest).");

        int filesQueued = 0;
        for (String localFilePath : localFilePaths) {
            if (!running) break; // Check running flag

            // Send file only if it's not in the server's known files manifest.
            if (!serverKnownFilesAfterHandshake.contains(localFilePath)) {
                File file = new File(DIRECTORY + File.separator + localFilePath);
                if (file.exists() && file.isFile()) {
                    try {
                        byte[] fileData = Files.readAllBytes(file.toPath());
                        FileEvent event = new FileEvent(FileEvent.EventType.CREATE, localFilePath, fileData);
                        eventSendQueue.put(event); // Queue for sending
                        System.out.println("Queued initial sync (client-unique) for " + localFilePath);
                        filesQueued++;
                    } catch (IOException e) {
                        System.err.println("Error reading file for initial sync " + localFilePath + ": " + e.getMessage());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        System.err.println("Initial sync interrupted while queuing event for " + localFilePath);
                        break; 
                    }
                }
            } else {
                System.out.println("Skipping initial sync for " + localFilePath + " (present in server manifest).");
            }
        }
        if (running) {
            System.out.println("Initial synchronization file queuing completed. Queued " + filesQueued + " client-unique file(s).");
        } else {
            System.out.println("Initial synchronization interrupted.");
        }
    }

    public void startWatching() {
        Path watchPath = Paths.get(DIRECTORY);
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            registerAll(watchPath, watchService);
            System.out.println("Watching directory: " + watchPath.toAbsolutePath());

            Thread pollingThread = new Thread(this::pollForModifications);
            pollingThread.setDaemon(true);
            pollingThread.start();
            
            while (running) {
                WatchKey key;
                try {
                    key = watchService.take(); 
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("WatchService interrupted, stopping watch.");
                    break;
                } catch (ClosedWatchServiceException e) {
                    System.out.println("WatchService closed, stopping watch.");
                    break; 
                }
                
                Path dir = (Path) key.watchable();
                
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (!running) break;
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == OVERFLOW) {
                        System.err.println("WatchService OVERFLOW: Some events may have been lost. Consider re-sync or full scan.");
                        continue;
                    }
                    
                    Path fileName = (Path) event.context();
                    Path fullPath = dir.resolve(fileName);
                    Path relativePath = watchPath.relativize(fullPath);
                    String pathString = relativePath.toString().replace("\\\\", "/");
                    
                    if (Files.isDirectory(fullPath, LinkOption.NOFOLLOW_LINKS)) {
                        if (kind == ENTRY_CREATE) {
                            System.out.println("Directory created: " + pathString);
                            try {
                                registerAll(fullPath, watchService); // Register new directory
                            } catch (IOException e) {
                                System.err.println("Error registering new directory " + fullPath + ": " + e.getMessage());
                            }
                        }
                        continue; 
                    }
                    
                    if (kind == ENTRY_CREATE) {
                        handleCreateEvent(fullPath, pathString);
                    } else if (kind == ENTRY_MODIFY) {
                        // WatchService MODIFY events can be unreliable. Poller is preferred.
                        System.out.println("WatchService detected MODIFY for: " + pathString + ". Poller will primarily handle actual content changes.");
                    } else if (kind == ENTRY_DELETE) {
                        handleDeleteEvent(pathString);
                    }
                }
                if (!running) break;
                
                boolean valid = key.reset();
                if (!valid) {
                    System.err.println("WatchKey no longer valid for: " + dir + ". Directory might be deleted or inaccessible.");
                    break; 
                }
            }
        } catch (IOException e) {
            System.err.println("IOException in WatchService: " + e.getMessage());
        } finally {
            running = false; // Signal other loops to terminate
            System.out.println("File watching stopped.");
        }
    }

    private void registerAll(Path start, WatchService watchService) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                // Only register if 'running' is true, to avoid issues during shutdown
                if (running) {
                    dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
                } else {
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void pollForModifications() {
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                Thread.sleep(2000); // Polling interval (e.g., 2 seconds)
                
                Map<String, Long> fileMapSnapshot;
                synchronized (fileModificationTimes) {
                    fileMapSnapshot = new HashMap<>(fileModificationTimes);
                }
                
                for (Map.Entry<String, Long> entry : fileMapSnapshot.entrySet()) {
                    if (!running) break; 
                    String filePath = entry.getKey();
                    long lastKnownModifiedTime = entry.getValue();
                    File file = new File(DIRECTORY + File.separator + filePath);
                    
                    if (file.exists() && file.isFile()) {
                        long currentModifiedTime = file.lastModified();
                        // Add a small threshold to avoid reacting to minor timestamp fluctuations
                        if (currentModifiedTime > (lastKnownModifiedTime + 1000L)) { 
                            synchronized (fileModificationTimes) {
                                // Re-check if still in map, as WatchService might have handled a delete
                                if(fileModificationTimes.containsKey(filePath)) {
                                    fileModificationTimes.put(filePath, currentModifiedTime);
                                } else {
                                    continue; // File was deleted and removed from map
                                }
                            }
                            System.out.println("Poller detected MODIFY for: " + filePath);
                            handleModifyEvent(file.toPath(), filePath);
                        }
                    } else {
                        // File doesn't exist. Deletion is primarily handled by WatchService.
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Polling thread interrupted.");
        }
        System.out.println("Polling for modifications stopped.");
    }

    private void handleCreateEvent(Path fullPath, String relativePath) {
        final int MAX_RETRIES = 5;
        final long RETRY_DELAY_MS = 100;

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                // Brief pause for file system to stabilize, especially for larger files or slower systems
                Thread.sleep(RETRY_DELAY_MS * (attempt + 1)); // Increase delay slightly with each attempt

                if (!Files.exists(fullPath)) {
                    System.out.println("File " + relativePath + " no longer exists after create event (possibly deleted quickly). Attempt: " + (attempt + 1));
                    return; // File was deleted, no point in retrying
                }

                long lastModified = fullPath.toFile().lastModified();
                synchronized(fileModificationTimes) {
                    if (fileModificationTimes.getOrDefault(relativePath, -1L) >= lastModified && attempt == 0) {
                        // Only skip on the first attempt if already processed to allow retries for access issues
                        System.out.println("Skipping create event for " + relativePath + ", already processed or newer version known.");
                        return;
                    }
                }

                System.out.println("Attempt " + (attempt + 1) + " to read file " + relativePath + " (create event)");
                byte[] fileData = Files.readAllBytes(fullPath); // Attempt to read the file

                // If read is successful, process the event
                System.out.println("Successfully read file " + relativePath + " on attempt " + (attempt + 1));
                FileEvent event = new FileEvent(FileEvent.EventType.CREATE, relativePath, fileData);
                System.out.println("Queueing CREATE event for: " + relativePath); // Added log
                eventSendQueue.put(event);
                synchronized (fileModificationTimes) {
                    fileModificationTimes.put(relativePath, lastModified);
                }
                return; // Successfully processed, exit the retry loop

            } catch (IOException e) {
                System.err.println("IOException in handleCreateEvent for " + relativePath + " (attempt " + (attempt + 1) + "/" + MAX_RETRIES + "): " + e.getMessage());
                if (attempt >= MAX_RETRIES - 1) {
                    System.err.println("Failed to process create event for " + relativePath + " after " + MAX_RETRIES + " attempts.");
                }
                // Wait before retrying, unless it's the last attempt
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("InterruptedException in handleCreateEvent for " + relativePath + ": " + e.getMessage());
                return; // Exit if interrupted
            }
        }
    }
    
    private void handleModifyEvent(Path fullPath, String relativePath) {
        try {
             Thread.sleep(100); 
            if (!Files.exists(fullPath)) {
                 System.out.println("File " + relativePath + " no longer exists after modify event.");
                return;
            }
            System.out.println("File modified: " + relativePath);
            byte[] fileData = Files.readAllBytes(fullPath);
            FileEvent event = new FileEvent(FileEvent.EventType.MODIFY, relativePath, fileData);
            eventSendQueue.put(event);
            // fileModificationTimes is updated by the poller or create handler
        } catch (IOException e) {
            System.err.println("IOException in handleModifyEvent for " + relativePath + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("InterruptedException in handleModifyEvent for " + relativePath + ": " + e.getMessage());
        }
    }
    
    private void handleDeleteEvent(String relativePath) {
        try {
            System.out.println("File deleted: " + relativePath);
            FileEvent event = new FileEvent(FileEvent.EventType.DELETE, relativePath, null);
            eventSendQueue.put(event);
            synchronized (fileModificationTimes) {
                 fileModificationTimes.remove(relativePath);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("InterruptedException in handleDeleteEvent for " + relativePath + ": " + e.getMessage());
        }
    }

    private synchronized void performActualSend(FileEvent event) {
        if (!running) {
            System.out.println("Not sending event, client is shutting down: " + event.getRelativePath());
            return;
        }

        // Safeguard: if handshake is not complete, event should have been re-queued by runEventSenderLoop.
        if (!initialHandshakeComplete) {
            System.err.println("PerformActualSend (Client ID: [" + this.clientId + "]): Safeguard: Handshake not complete. Re-queueing event: " + event.getRelativePath());
            try {
                eventSendQueue.put(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted while re-queueing event in performActualSend safeguard: " + event.getRelativePath());
            }
            return;
        }

        if (socket == null || socket.isClosed() || output == null) {
            System.err.println("Cannot send event (Client ID: [" + this.clientId + "]), socket or output stream is not available. Attempting to reconnect...");
            if (!tryReconnect()) { 
                System.err.println("Failed to reconnect. Event for " + event.getRelativePath() + " will be lost if not re-queued or handled otherwise.");
                return;
            } else {
                System.out.println("Reconnected. Re-queueing event for " + event.getRelativePath());
                try {
                    eventSendQueue.put(event); // Re-queue the event to try with the new connection
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("Interrupted while re-queueing event after reconnect: " + event.getRelativePath());
                }
                return; // Exit and let the sender loop pick it up again
            }
        }
        
        try {
            System.out.println("Attempting to send event to server: " + event.getEventType() + " for " + event.getRelativePath());
            output.writeObject(event);
            output.flush();
            System.out.println("Successfully sent event: " + event.getEventType() + " for " + event.getRelativePath());
        } catch (IOException e) {
            System.err.println("IOException during send for " + event.getRelativePath() + ": " + e.getMessage());
            closeClientResources(); 
            System.err.println("Attempting to reconnect after send failure...");
            if (!tryReconnect()) {
                System.err.println("Failed to reconnect after send failure. Event for " + event.getRelativePath() + " may be lost.");
            } else {
                System.out.println("Reconnected successfully after send failure. Re-queueing event: " + event.getRelativePath());
                try {
                    eventSendQueue.put(event); // Re-queue the event
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    System.err.println("Interrupted while re-queueing event after send failure and reconnect: " + event.getRelativePath());
                }
            }
        } 
    }
    
    private synchronized boolean tryReconnect() {
        if (!running) return false;
        System.out.println("Client ID: [" + this.clientId + "]: Closing existing client resources before attempting reconnect...");
        closeClientResources(); // This will set initialHandshakeComplete = false

        try {
            System.out.println("Client ID: [" + this.clientId + "]: Attempting to reconnect to server...");
            socket = new Socket();
            socket.connect(new InetSocketAddress(SERVER_HOST, SERVER_PORT), 5000);
            System.out.println("Client ID: [" + this.clientId + "]: Reconnect: Socket connection established.");

            System.out.println("Client ID: [" + this.clientId + "]: Reconnect: Creating ObjectOutputStream...");
            output = new ObjectOutputStream(socket.getOutputStream());
            output.flush();
            System.out.println("Client ID: [" + this.clientId + "]: Reconnect: ObjectOutputStream created and flushed.");

            try {
                System.out.println("Client ID: [" + this.clientId + "]: Reconnect: PREPARING to write client ID object to server...");
                output.writeObject(this.clientId);
                System.out.println("Client ID: [" + this.clientId + "]: Reconnect: Client ID object WRITTEN to OOS (pre-flush).");

                System.out.println("Client ID: [" + this.clientId + "]: Reconnect: PREPARING to flush OOS after writing client ID...");
                output.flush();
                System.out.println("Client ID: [" + this.clientId + "]: Reconnect: OOS FLUSHED after writing client ID.");
            } catch (IOException e) {
                System.err.println("Client ID: [" + this.clientId + "]: Reconnect: CRITICAL IOException during client ID send/flush: " + e.getMessage());
                throw e; // Re-throw to be caught by the method's main try-catch
            }

            System.out.println("Client ID: [" + this.clientId + "]: Reconnect: Creating ObjectInputStream...");
            input = new ObjectInputStream(socket.getInputStream());
            System.out.println("Client ID: [" + this.clientId + "]: Reconnect: ObjectInputStream created.");

            System.out.println("Client ID: [" + this.clientId + "]: Reconnect: Streams established. Attempting to read initial messages from server (server manifest)...");

            boolean initialHandshakeCompletedThisAttempt = false;
            serverKnownFilesAfterHandshake.clear();

            try {
                while (!initialHandshakeCompletedThisAttempt && running) {
                    System.out.println("Client ID: [" + this.clientId + "]: Reconnect: Waiting to read object from server for initial handshake...");
                    Object serverMessage = input.readObject();
                    if (serverMessage == null) {
                        System.err.println("Client ID: [" + this.clientId + "]: Reconnect: Received null message from server during initial sync. Ending initial read.");
                        break;
                    }
                    System.out.println("Client ID: [" + this.clientId + "]: Reconnect: Received initial object from server of type: " + serverMessage.getClass().getName());
                    switch (serverMessage) {
                        case FileEvent fe -> {
                            System.out.println("Client (reconnect): Processing initial FileEvent for: " + fe.getRelativePath() + " Type: " + fe.getEventType());
                            processInitialFileEvent(fe);
                        }
                        case java.util.List<?> serverObjectList -> {
                            try {
                                @SuppressWarnings("unchecked")
                                java.util.List<String> serverFileList = (java.util.List<String>) serverObjectList;
                                serverKnownFilesAfterHandshake.addAll(serverFileList);
                                System.out.println("Client ID: [" + this.clientId + "]: Reconnect: Received initial file list manifest. Count: " + serverKnownFilesAfterHandshake.size());
                                initialHandshakeCompletedThisAttempt = true;
                                this.initialHandshakeComplete = true; // Handshake fully complete on reconnect
                            } catch (ClassCastException cce) {
                                System.err.println("Client ID: [" + this.clientId + "]: Reconnect: Received java.util.List, but it was not List<String>. Manifest ignored. " + cce.getMessage());
                                initialHandshakeCompletedThisAttempt = true;
                                this.initialHandshakeComplete = false;
                            }
                        }
                        default -> {
                            System.err.println("Client ID: [" + this.clientId + "]: Reconnect: Received unexpected initial object type from server: " + serverMessage.getClass().getName() + ". Stopping initial read.");
                            initialHandshakeCompletedThisAttempt = true;
                            this.initialHandshakeComplete = false;
                        }
                    }
                }
            } catch (ClassNotFoundException e) {
                System.err.println("Client ID: [" + this.clientId + "]: Reconnect: Error reading initial messages - ClassNotFoundException: " + e.getMessage());
                closeClientResources();
                return false;
            } catch (EOFException e) {
                System.err.println("Client ID: [" + this.clientId + "]: Reconnect: EOFException while reading initial messages from server. " + e.getMessage());
                initialHandshakeCompletedThisAttempt = true; // Assume handshake ended or failed
                this.initialHandshakeComplete = false;
            } catch (IOException e) {
                System.err.println("Client ID: [" + this.clientId + "]: Reconnect: Error reading initial messages from server - IOException: " + e.getMessage());
                closeClientResources(); // Close resources on error, also sets initialHandshakeComplete = false
                return false; // Reconnect failed
            }
            System.out.println("Client ID: [" + this.clientId + "]: Reconnect: Finished processing initial messages from server.");

            if (this.initialHandshakeComplete) { // Check the instance flag
                 System.out.println("Client ID: [" + this.clientId + "]: Reconnected successfully to server.");
                 initialSync(); // Perform initial sync after successful reconnect and handshake
                 return true;
            } else {
                System.err.println("Client ID: [" + this.clientId + "]: Reconnect: Handshake not completed after establishing streams.");
                closeClientResources();
                return false;
            }

        } catch (IOException e) {
            System.err.println("Client ID: [" + this.clientId + "]: Reconnect failed: " + e.getMessage());
            closeClientResources();
            return false;
        }
    }

    private void processInitialFileEvent(FileEvent fe) {
        String relativePath = fe.getRelativePath();
        File localFile = new File(DIRECTORY, relativePath);

        System.out.println("Processing initial server event: " + fe.getEventType() + " for " + relativePath);

        try {
            switch (fe.getEventType()) {
                case CREATE,
                     MODIFY -> { // Server's MODIFY implies client should update or create the file
                    if (fe.getFileData() == null) {
                        System.err.println("Error: Server sent " + fe.getEventType() + " event for " + relativePath + " with no file data.");
                        return;
                    }
                    File parentDir = localFile.getParentFile();
                    if (parentDir != null && !parentDir.exists()) {
                        if (!parentDir.mkdirs()) {
                            System.err.println("Could not create parent directories for " + localFile.getAbsolutePath());
                            return; // Cannot write file if parent dirs fail
                        }
                    }
                    Files.write(localFile.toPath(), fe.getFileData(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                    long newTimestamp = localFile.lastModified(); 
                    synchronized (fileModificationTimes) {
                        fileModificationTimes.put(relativePath, newTimestamp);
                    }
                    System.out.println("Applied server " + fe.getEventType() + " to " + relativePath + ". New local timestamp: " + newTimestamp);
                }
                case DELETE -> {
                    if (localFile.exists()) {
                        boolean wasDeleted = localFile.delete(); // Explicitly assign to satisfy linter
                        if (wasDeleted) {
                            System.out.println("Deleted local file " + relativePath + " as per server event.");
                        } else {
                            System.err.println("Failed to delete local file " + relativePath + " as per server event.");
                        }
                    } else {
                        System.out.println("Local file " + relativePath + " for server DELETE event already absent.");
                    }
                    synchronized (fileModificationTimes) {
                        fileModificationTimes.remove(relativePath);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("IOException while processing initial server event for " + relativePath + " (" + fe.getEventType() + "): " + e.getMessage());
        }
    }

    public synchronized void closeClientResources() {
        System.out.println("Client ID: [" + this.clientId + "]: Closing client resources...");
        this.initialHandshakeComplete = false; // Critical: reset handshake flag
        running = false;

        if (eventSenderThread != null && eventSenderThread.isAlive()) {
            eventSenderThread.interrupt();
            System.out.println("Client ID: [" + this.clientId + "]: Event sender thread interrupted.");
            try {
                eventSenderThread.join(1000); // Wait for sender thread to die
            } catch (InterruptedException e) {
                System.err.println("Client ID: [" + this.clientId + "]: Interrupted while waiting for event sender thread to join.");
                Thread.currentThread().interrupt();
            }
        }

        try {
            if (input != null) {
                input.close();
                System.out.println("Client ID: [" + this.clientId + "]: ObjectInputStream closed.");
            }
        } catch (IOException e) {
            System.err.println("Client ID: [" + this.clientId + "]: Error closing ObjectInputStream: " + e.getMessage());
        } finally {
            input = null;
        }

        try {
            if (output != null) {
                output.close();
                System.out.println("Client ID: [" + this.clientId + "]: ObjectOutputStream closed.");
            }
        } catch (IOException e) {
            System.err.println("Client ID: [" + this.clientId + "]: Error closing ObjectOutputStream: " + e.getMessage());
        } finally {
            output = null;
        }

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
                System.out.println("Client ID: [" + this.clientId + "]: Socket closed.");
            }
        } catch (IOException e) {
            System.err.println("Client ID: [" + this.clientId + "]: Error closing socket: " + e.getMessage());
        } finally {
            socket = null;
        }
        System.out.println("Client ID: [" + this.clientId + "]: Client resources closed.");
    }

    public void shutdown() {
        System.out.println("Shutting down FileSyncClient...");
        running = false; // Signal all loops to stop

        if (eventSenderThread != null) {
            eventSenderThread.interrupt(); // Interrupt the sender thread (it handles InterruptedException)
        }
        // WatchService and Polling threads will break due to 'running' flag or InterruptedException

        // Give threads a moment to shut down
        try {
            if (eventSenderThread != null && eventSenderThread.isAlive()) {
                eventSenderThread.join(5000); // Wait up to 5s for sender thread
                 if (eventSenderThread.isAlive()) {
                    System.err.println("Event sender thread did not terminate in time.");
                }
            }
        } catch (InterruptedException e) {
            System.err.println("Interrupted while waiting for threads to finish.");
            Thread.currentThread().interrupt();
        }
        
        closeClientResources(); // Final cleanup
        System.out.println("FileSyncClient shutdown complete.");
    }

    // For compatibility
    public void close() {
        shutdown();
    }

    // Main method for basic standalone testing
    public static void main(String[] args) {
        FileSyncClient client = new FileSyncClient();
        try {
            // Assuming the following calls were intended based on typical client startup
            // and previous "cannot find symbol" errors when 'client' was removed.
            client.connect();
            Thread watchThread = new Thread(client::startWatching);
            watchThread.setDaemon(true);
            watchThread.start();

        } catch (IOException e) {
            System.err.println("Client startup failed: " + e.getMessage());
            // Ensure client resources are cleaned up if initialization failed.
            if (client != null) {
                client.shutdown();
            }
        }
    }
}