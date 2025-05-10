import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class FileSyncServer {
    public static final int DEFAULT_PORT = 8000; // Made public for GUI access
    public static final String SERVER_FILES_DIR = "server_files"; // Made public for GUI access
    private final int port;
    private ServerSocket serverSocket;
    private final ExecutorService clientHandlerPool;
    private final Map<String, ClientHandler> clientHandlersById = new ConcurrentHashMap<>();
    private FileSyncServerGUI gui; // Optional GUI

    public FileSyncServer(int port) {
        this.port = port;
        File serverDir = new File(SERVER_FILES_DIR);
        if (!serverDir.exists()) {
            serverDir.mkdirs();
        }
        this.clientHandlerPool = Executors.newCachedThreadPool();
    }

    public void setGui(FileSyncServerGUI gui) {
        this.gui = gui;
    }

    private void log(String message) {
        if (gui != null) {
            gui.addLogEntry(message);
        } else {
            System.out.println(message);
        }
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(port);
            log("Server started on port: " + port);
            log("Server files directory: " + new File(SERVER_FILES_DIR).getAbsolutePath());

            while (!serverSocket.isClosed()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    // Client ID will be read by the ClientHandler itself.
                    // Initial log before ID is known can be minimal or done in ClientHandler.
                    ClientHandler clientHandler = new ClientHandler(clientSocket, this);
                    clientHandlerPool.submit(clientHandler);
                } catch (SocketException se) { // More specific exception
                    if (serverSocket.isClosed()) {
                        log("Server socket closed, stopping accepting new clients.");
                    } else {
                        log("SocketException while accepting client connection: " + se.getMessage());
                    }
                } catch (IOException e) {
                    if (serverSocket.isClosed()) {
                        log("Server socket closed, stopping accepting new clients (IOException).");
                    } else {
                        log("IOException accepting client connection: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log("Could not start server on port " + port + ": " + e.getMessage());
        }
    }

    public void stop() {
        log("Stopping server...");
        clientHandlerPool.shutdown();
        try {
            if (!clientHandlerPool.awaitTermination(5, TimeUnit.SECONDS)) {
                clientHandlerPool.shutdownNow();
            }
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            for (ClientHandler handler : clientHandlersById.values()) { // Iterate over map values
                handler.closeConnection();
            }
            clientHandlersById.clear(); // Clear the map
            log("Server stopped.");
        } catch (IOException e) {
            log("Error closing server socket: " + e.getMessage());
        } catch (InterruptedException e) {
            log("Server stop interrupted: " + e.getMessage());
            clientHandlerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    void removeClient(ClientHandler clientHandler) {
        if (clientHandler.getClientId() != null) {
            clientHandlersById.remove(clientHandler.getClientId(), clientHandler); // More specific removal
            log("Client disconnected and removed: " + clientHandler.getClientAddress() + " (ID: " + clientHandler.getClientId() + ")");
        } else {
            // This case should be less common if ID is established early or client is always added with an ID.
            log("Client disconnected and removed: " + clientHandler.getClientAddress() + " (ID not available or not yet set in map)");
        }
    }

    synchronized void handleFileEvent(FileEvent event, ClientHandler sourceHandler) {
        String relativePathFromClient = event.getRelativePath();
        String relativePath = relativePathFromClient.replace('/', File.separatorChar);
        String fullPath = SERVER_FILES_DIR + File.separator + relativePath;
        File file = new File(fullPath);

        log("Received event: " + event.getEventType() + " for " + relativePathFromClient +
            " (normalized to " + relativePath + ") from " + sourceHandler.getClientAddress() +
            " (ID: " + (sourceHandler.getClientId() != null ? sourceHandler.getClientId() : "N/A") + ")");

        try {
            FileEvent.EventType eventType = event.getEventType();
            if (eventType == FileEvent.EventType.CREATE || eventType == FileEvent.EventType.MODIFY) {
                File parentDir = file.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    if (!parentDir.mkdirs()) {
                        log("Error creating parent directories for " + fullPath);
                        return;
                    }
                }
                Files.write(file.toPath(), event.getFileData(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                log("File " + (eventType == FileEvent.EventType.CREATE ? "created" : "modified") + ": " + relativePath);
                broadcastEvent(event, sourceHandler); // Broadcast after successful local write
            } else if (eventType == FileEvent.EventType.DELETE) {
                if (file.exists()) {
                    if (Files.deleteIfExists(file.toPath())) {
                        log("File deleted: " + relativePath);
                        broadcastEvent(event, sourceHandler); // Broadcast after successful local delete
                    } else {
                        log("Error deleting file: " + relativePath);
                    }
                } else {
                    log("File to delete not found: " + relativePath);
                }
            }
        } catch (IOException e) {
            log("Error processing file event for " + relativePath + ": " + e.getMessage());
        }
    }

    private void broadcastEvent(FileEvent event, ClientHandler sourceHandler) {
        for (ClientHandler handler : clientHandlersById.values()) {
            if (handler != sourceHandler && handler.getClientId() != null && !handler.getClientId().equals(sourceHandler.getClientId())) {
                handler.sendFileEvent(event);
            } else if (handler == sourceHandler) {
            } else if (handler.getClientId() == null) {
                log("Skipping broadcast to handler " + handler.getClientAddress() + " as Client ID is null.");
            }
        }
    }

    public static void main(String[] args) {
        int portArg = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                portArg = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number: " + args[0] + ". Using default port " + DEFAULT_PORT);
            }
        }
        FileSyncServer server = new FileSyncServer(portArg);
        server.start();
    }

    private static class ClientHandler implements Runnable {
        private final Socket clientSocket;
        private final FileSyncServer server;
        private ObjectInputStream input;
        private ObjectOutputStream output;
        private final String clientAddress;
        private volatile boolean streamsInitialized = false;
        private String clientId; // Unique ID for this client connection
        private final Object outputLock = new Object(); // Dedicated lock for output stream operations

        public ClientHandler(Socket socket, FileSyncServer server) {
            this.clientSocket = socket;
            this.server = server;
            this.clientAddress = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        }

        public String getClientId() {
            return clientId;
        }

        private boolean initializeStreamsAndReadClientId() {
            try {
                // Set a timeout for reading the client ID and initial handshake on the server side
                clientSocket.setSoTimeout(20000); // 20 seconds server-side timeout for handshake

                server.log("ClientHandler for " + clientAddress + ": Initializing streams. Creating OOS...");
                output = new ObjectOutputStream(clientSocket.getOutputStream());
                output.flush(); // Important to flush after creating OOS
                server.log("ClientHandler for " + clientAddress + ": Server OOS created and flushed. Creating OIS...");

                input = new ObjectInputStream(clientSocket.getInputStream());
                server.log("ClientHandler for " + clientAddress + ": Server OIS created.");

                server.log("ClientHandler for " + clientAddress + ": Attempting to read Client ID object (timeout: 20s)...");
                this.clientId = (String) input.readObject(); // Read client ID
                server.log("ClientHandler for " + clientAddress + " (ID: " + this.clientId + "): Client ID received: " + this.clientId);

                // Successfully read client ID, now add to server's map
                // This should be done before sending manifest, so server knows about client
                server.clientHandlersById.put(this.clientId, this);
                server.log("ClientHandler for " + clientAddress + " (ID: " + this.clientId + ") added to server's active handlers.");

                streamsInitialized = true; // Mark streams as initialized AFTER ID is read and handler is registered

                server.log("ClientHandler for " + clientAddress + " (ID: " + this.clientId + "): Sending initial file manifest to client...");
                sendExistingFilesToClient(); // This sends the Set<String> of filenames
                server.log("ClientHandler for " + clientAddress + " (ID: " + this.clientId + "): Initial file manifest sent.");

                // Reset timeout for general operations (0 means infinite timeout)
                clientSocket.setSoTimeout(0); 
                return true;
            } catch (SocketTimeoutException e) {
                server.log("ClientHandler for " + clientAddress + ": SocketTimeoutException during handshake. Client may not have sent ID or responded in time (20s). " + e.getMessage());
                // streamsInitialized remains false
                return false;
            } catch (EOFException e) {
                server.log("ClientHandler for " + clientAddress + ": EOFException during handshake. Client likely disconnected. " + e.getMessage());
                // streamsInitialized remains false
                return false;
            } catch (IOException | ClassNotFoundException e) {
                // Log includes clientAddress but clientId might be null if readObject failed
                String idForLog = (this.clientId != null) ? this.clientId : "N/A_at_exception";
                server.log("Error initializing streams or reading/sending data for " + clientAddress + " (ID: " + idForLog + "): " + e.getClass().getSimpleName() + " - " + e.getMessage());
                return false; // This causes run() to call closeConnection()
            }
        }

        public String getClientAddress() {
            return clientAddress;
        }

        public void sendFileEvent(FileEvent event) {
            if (!streamsInitialized || output == null) {
                server.log("Cannot send event to " + clientAddress + " (ID: " + clientId + "), streams not initialized or output is null.");
                return;
            }
            try {
                server.log("Attempting to send event " + event.getEventType() + " for " + event.getRelativePath() + " to " + clientAddress + " (ID: " + clientId + ")");
                synchronized (outputLock) { // Use the final lock object
                    output.writeObject(event);
                    output.flush();
                }
                server.log("Successfully sent event " + event.getEventType() + " for " + event.getRelativePath() + " to " + clientAddress + " (ID: " + clientId + ")");
            } catch (IOException e) {
                server.log("Error sending event to client " + clientAddress + " (ID: " + clientId + ") (" + event.getEventType() + " for " + event.getRelativePath() + "): " + e.getMessage());
            }
        }

        @Override
        public void run() {
            String logClientIdRunStart = (this.clientId != null) ? this.clientId : "N/A_at_run_start";
            server.log("ClientHandler for [" + clientAddress + "] (ID: [" + logClientIdRunStart + "]) run method started.");
            if (!initializeStreamsAndReadClientId()) {
                server.log("ClientHandler for [" + clientAddress + "] (ID: [" + logClientIdRunStart + "]) failed to initialize streams/read client ID. Exiting run method.");
                // Ensure removal if it was somehow partially added or if ID was read then failed.
                // The finally block will handle general cleanup and removal.
                return;
            }
            // At this point, clientId should be set.
            String currentId = (this.clientId != null) ? this.clientId : "ERROR_ID_NULL_AFTER_INIT";
            server.log("ClientHandler for [" + clientAddress + "] (ID: [" + currentId + "]) proceeding after successful init.");

            try {
                sendExistingFilesToClient();

                while (streamsInitialized && !clientSocket.isClosed() && clientSocket.isConnected()) {
                    server.log("ClientHandler for [" + clientAddress + "] (ID: [" + clientId + "]) waiting to read object...");
                    Object obj = input.readObject();
                    if (obj != null) {
                        server.log("ClientHandler for " + clientAddress + " (ID: " + clientId + ") received object of type: " + obj.getClass().getName());
                        if (obj instanceof FileEvent fileEvent) { // instanceof pattern
                            server.handleFileEvent(fileEvent, this);
                        } else {
                            server.log("Received unknown object type from " + clientAddress + " (ID: " + clientId + "): " + obj.getClass().getName());
                        }
                    } else {
                        server.log("ClientHandler for " + clientAddress + " (ID: " + clientId + ") received null object. Disconnecting.");
                        break;
                    }
                }
            } catch (EOFException e) {
                server.log("Client " + clientAddress + " (ID: " + clientId + ") disconnected (EOF).");
            } catch (SocketException e) {
                server.log("Client " + clientAddress + " (ID: " + clientId + ") connection issue (SocketException): " + e.getMessage());
            } catch (IOException e) {
                if ("Stream closed".equalsIgnoreCase(e.getMessage()) || "Socket closed".equalsIgnoreCase(e.getMessage()) || e.getMessage().contains("Connection reset")) {
                    server.log("Client " + clientAddress + " (ID: " + clientId + ") stream/socket closed: " + e.getMessage());
                } else {
                    server.log("IOException for client " + clientAddress + " (ID: " + clientId + "): " + e.getMessage());
                }
            } catch (ClassNotFoundException e) {
                server.log("ClassNotFoundException from client " + clientAddress + " (ID: " + clientId + "): " + e.getMessage());
            } finally {
                String logClientIdFinally = (this.clientId != null) ? this.clientId : "N/A_in_finally";
                server.log("ClientHandler for [" + clientAddress + "] (ID: [" + logClientIdFinally + "]) exiting run loop. Cleaning up.");
                closeConnection();
                server.removeClient(this); // Ensures client is removed from the active set/map
            }
        }

        private void sendExistingFilesToClient() {
            String clientDesc = clientAddress + " (ID: [" + (clientId != null ? clientId : "N/A") + "])";
            server.log("Preparing to send existing file list (manifest) to client [" + clientDesc + "] ...");

            // Changed from List<String> to Set<String>
            Set<String> filePaths = new HashSet<>(); 
            collectFilePathsRecursively(new File(server.SERVER_FILES_DIR), "", filePaths);

            server.log("Attempting to send file manifest (" + filePaths.size() + " paths) to client " + clientDesc);
            try {
                synchronized (outputLock) {
                    output.writeObject(filePaths); // Now sends a HashSet
                    output.flush();
                }
                server.log("Successfully sent file manifest to client " + clientDesc);
            } catch (IOException e) {
                server.log("IOException sending file manifest to " + clientDesc + ": " + e.getMessage());
                // Consider closing connection or other error handling
            }
        }

        // Changed parameter from List<String> to Set<String>
        private void collectFilePathsRecursively(File currentDir, String relativePath, Set<String> filePaths) {
            File[] files = currentDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    String newRelativePath = relativePath.isEmpty() ? file.getName() : relativePath + File.separator + file.getName();
                    if (file.isDirectory()) {
                        collectFilePathsRecursively(file, newRelativePath, filePaths);
                    } else {
                        filePaths.add(newRelativePath);
                    }
                }
            }
        }

        public void closeConnection() {
            String logClientId = (this.clientId != null) ? this.clientId : "N/A_at_close";
            if (!streamsInitialized && clientSocket != null && clientSocket.isClosed() && output == null && input == null) {
                return; 
            }
            server.log("Closing connection for client [" + clientAddress + "] (ID: [" + logClientId + "]) (Streams initialized: " + streamsInitialized + ", Socket closed: " + (clientSocket == null ? "null" : clientSocket.isClosed()) + ")");
            
            streamsInitialized = false; // Mark streams as unusable immediately

            try {
                if (output != null) {
                    output.close();
                }
            } catch (IOException e) {
            } finally {
                output = null;
            }

            try {
                if (input != null) {
                    input.close();
                }
            } catch (IOException e) {
                input = null;
            }

            try {
                if (clientSocket != null && !clientSocket.isClosed()) {
                    clientSocket.close();
                }
            } catch (IOException e) {
            }
        }
    }
}
